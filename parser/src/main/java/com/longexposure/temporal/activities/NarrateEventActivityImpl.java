package com.longexposure.temporal.activities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.longexposure.llm.LlamaClient;
import com.longexposure.narration.NarrationPipeline;
import com.longexposure.storage.SchemaManager;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;

/**
 * Per-event narration. Each activity invocation handles exactly one
 * selected event: pulls its row from {@code selected_events}, runs
 * the two-pass pipeline, upserts a {@code narratives} row.
 *
 * <p>Concurrency: the worker is configured with
 * {@code setMaxConcurrentActivityExecutionSize(2)} for this activity
 * type, so even though the workflow fans out 90 activities the worker
 * never dispatches more than 2 concurrently. The {@link LlamaClient}
 * semaphore is a second-line defense.
 *
 * <p>The {@link LlamaClient} + {@link NarrationPipeline} are created
 * per-invocation. Acceptable because: LLM calls dominate wall-clock
 * (~16 sec per event), and {@link LlamaClient} is a thin OkHttp
 * wrapper with no expensive init.
 */
public final class NarrateEventActivityImpl implements NarrateEventActivity {

    private static final Logger LOG = LoggerFactory.getLogger(NarrateEventActivityImpl.class);

    private static final String MODEL_ID = System.getenv()
            .getOrDefault("LLAMA_MODEL", "Qwen3.5-122B-A10B");

    @Override
    public long narrate(final LocalDate tradingDate, final long selectedId) {
        ActivityExecutionContext actx = Activity.getExecutionContext();

        ObjectMapper json = new ObjectMapper();
        LlamaClient llama = LlamaClient.fromEnv();
        NarrationPipeline pipeline = new NarrationPipeline(llama, MODEL_ID);

        // Phase 7b: BackgroundHeartbeat daemon thread fires every 30 sec for
        // the activity's full duration, so even if a single LLM call blocks
        // longer than the 1-min heartbeat-timeout (joi transient slow path),
        // Temporal sees liveness and doesn't kill the activity. Replaces the
        // explicit-checkpoint pattern (heartbeat-before / heartbeat-after the
        // LLM call) which couldn't cover the call's own duration.
        try (BackgroundHeartbeat hb = BackgroundHeartbeat.start(actx, "narrate-heartbeat", 30);
             Connection conn = openConnection()) {
            hb.setStage("schema:" + selectedId);
            SchemaManager.apply(conn);

            hb.setStage("load:" + selectedId);
            NarrationPipeline.NarrationInput in = loadSelectedEvent(conn, tradingDate, selectedId, json);
            if (in == null) {
                LOG.warn("selected event not found  date={} selected_id={}", tradingDate, selectedId);
                return 0;
            }

            // Content-addressed skip. event_hash = SHA256(scorer_id + breakdown +
            // prompt versions); if a verified narrative already exists for these
            // exact inputs, reuse it and skip the 2 LLM calls. Makes re-scoring /
            // backfill cheap — only genuinely new or changed events hit the LLM.
            //
            // FK orphaning fix (2026-05-28 evening): re-scoring a day deletes +
            // re-inserts selected_events with new BIGSERIAL selected_ids. The
            // cached narrative row's selected_id then points at a deleted
            // selected_events row, so the API's join returns nothing for the
            // new selected_id. Before this fix the cache hit was a no-op
            // write — frontend showed no narration. Now we UPDATE the row's
            // selected_id to point at the current selected_events row. Same
            // content, fresh FK.
            hb.setStage("cache_check:" + selectedId);
            byte[] eventHash = pipeline.eventHash(in);
            if (verifiedExists(conn, "narratives", "event_hash", eventHash)) {
                relinkNarrative(conn, eventHash, selectedId);
                LOG.info("narrate skip (cached, relinked)  selected_id={} scorer={} symbol={}",
                        selectedId, in.scorerId(), in.symbol());
                return 1;
            }

            hb.setStage("llm:" + selectedId);
            NarrationPipeline.Result result = pipeline.narrate(in);

            hb.setStage("upsert:" + selectedId);
            upsertNarrative(conn, in, result, json);

            LOG.info("narrated  selected_id={} scorer={} symbol={} elapsed_ms={} verifier_passed={} mismatches={}",
                    selectedId, in.scorerId(), in.symbol(), result.elapsedMs(),
                    result.verify().passed(), result.verify().mismatches().size());
            return 1;
        } catch (Exception e) {
            // Surface as activity failure so Temporal records it +
            // per-event retry policy in the workflow can decide what to
            // do. Most narration failures are transient (LLM timeout,
            // network blip).
            throw new RuntimeException("narrate failed for selected_id=" + selectedId, e);
        }
    }

    private NarrationPipeline.NarrationInput loadSelectedEvent(
            final Connection conn, final LocalDate tradingDate, final long selectedId, final ObjectMapper json)
            throws Exception {
        String sql = """
                SELECT selected_id, trading_date, scorer_id, symbol, ts, score, breakdown::text
                FROM selected_events
                WHERE trading_date = ? AND selected_id = ?
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, tradingDate);
            st.setLong(2, selectedId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) return null;
                String scorerId   = rs.getString("scorer_id");
                String symbol     = rs.getString("symbol");
                Timestamp tsTs    = rs.getTimestamp("ts");
                double score      = rs.getDouble("score");
                JsonNode breakdown = json.readTree(rs.getString("breakdown"));
                return new NarrationPipeline.NarrationInput(
                        selectedId,
                        tradingDate.toString(),
                        scorerId,
                        symbol,
                        tsTs.toInstant(),
                        score,
                        breakdown);
            }
        }
    }

    /**
     * Upsert by event_hash. ON CONFLICT updates the prose + verifier
     * status — useful when re-running with a tweaked verifier or a
     * prompt revision (the PROMPT_VERSION strings would normally
     * change the hash, but if only the verifier changes the hash stays
     * the same and we update in place).
     */
    private void upsertNarrative(final Connection conn,
                                 final NarrationPipeline.NarrationInput in,
                                 final NarrationPipeline.Result result,
                                 final ObjectMapper json) throws Exception {
        String verifierNotes = json.createObjectNode()
                .put("numbers_checked", result.verify().numbersChecked())
                .set("mismatches", json.valueToTree(result.verify().mismatches()))
                .toString();

        String sql = """
                INSERT INTO narratives (
                    event_hash, trading_date, event_type, event_ts, symbol,
                    score, score_breakdown, narrative, model_id,
                    selected_id, blueprint, verifier_passed, verifier_notes,
                    render_structured
                )
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb)
                ON CONFLICT (event_hash) DO UPDATE SET
                    narrative         = EXCLUDED.narrative,
                    blueprint         = EXCLUDED.blueprint,
                    verifier_passed   = EXCLUDED.verifier_passed,
                    verifier_notes    = EXCLUDED.verifier_notes,
                    render_structured = EXCLUDED.render_structured,
                    model_id          = EXCLUDED.model_id,
                    created_at        = NOW()
                """;

        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setBytes(1, result.eventHash());
            st.setObject(2, java.time.LocalDate.parse(in.tradingDate()));
            st.setString(3, in.scorerId());
            st.setTimestamp(4, Timestamp.from(in.ts()));
            if (in.symbol() != null) st.setString(5, in.symbol()); else st.setNull(5, Types.VARCHAR);
            st.setDouble(6, in.score());
            st.setString(7, in.breakdown().toString());
            st.setString(8, result.prose());
            st.setString(9, result.modelId());
            st.setLong(10, in.selectedId());
            st.setString(11, result.blueprint().toString());
            st.setBoolean(12, result.verify().passed());
            st.setString(13, verifierNotes);
            st.setString(14, result.rendered().toJson(json).toString());
            st.executeUpdate();
        }
    }

    /**
     * Content-addressed freshness check: does a {@code verifier_passed} row with
     * this hash already exist? {@code table} / {@code hashCol} are code
     * constants (never user input) — no injection surface.
     */
    static boolean verifiedExists(final Connection conn, final String table,
                                  final String hashCol, final byte[] hash) throws Exception {
        String sql = "SELECT 1 FROM " + table + " WHERE " + hashCol + " = ? AND verifier_passed = true LIMIT 1";
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setBytes(1, hash);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Update cached narrative rows' {@code selected_id} to point at the
     * current selected_events row. The narrative content is unchanged (same
     * event_hash means same breakdown means same prose); only the FK reference
     * is refreshed. Without this, re-scoring leaves the cached row pointing at
     * a deleted selected_events row — frontend joins fail.
     *
     * <p>Updates ALL rows matching this event_hash (there can be multiple from
     * past re-runs that all share content). The API picks latest-by-created_at
     * elsewhere; here we just ensure at least one row anchors at the current
     * selected_id.
     */
    private static void relinkNarrative(final Connection conn, final byte[] eventHash,
                                        final long currentSelectedId) throws Exception {
        String sql = "UPDATE narratives SET selected_id = ? "
                   + "WHERE event_hash = ? AND verifier_passed = true";
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setLong(1, currentSelectedId);
            st.setBytes(2, eventHash);
            st.executeUpdate();
        }
    }

    private static Connection openConnection() throws Exception {
        String host = System.getenv().getOrDefault("POSTGRES_HOST", "localhost");
        String port = System.getenv().getOrDefault("POSTGRES_PORT", "5432");
        String db   = System.getenv().getOrDefault("POSTGRES_DB", "longexposure");
        String user = System.getenv().getOrDefault("POSTGRES_USER", "leuser");
        String pwd  = System.getenv().getOrDefault("POSTGRES_PASSWORD", "lepass");
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
        return DriverManager.getConnection(url, user, pwd);
    }
}
