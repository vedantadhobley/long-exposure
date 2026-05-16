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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class NarrateEventsActivityImpl implements NarrateEventsActivity {

    private static final Logger LOG = LoggerFactory.getLogger(NarrateEventsActivityImpl.class);

    private static final String MODEL_ID = System.getenv()
            .getOrDefault("LLAMA_MODEL", "Qwen3.5-122B-A10B");

    @Override
    public long narrateEvents(final LocalDate tradingDate) {
        ActivityExecutionContext actx = Activity.getExecutionContext();
        LOG.info("narrate start  date={} model={}", tradingDate, MODEL_ID);
        long t0 = System.nanoTime();

        // Background keep-alive heartbeat — same pattern as the scoring
        // activity. LLM calls take 5-10 sec each, so per-call latency
        // doesn't trip the heartbeat alone, but the per-day total
        // (15+ min) needs to stay alive even during slow stretches.
        ScheduledExecutorService keepAlive = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "narrate-events-heartbeat");
            t.setDaemon(true);
            return t;
        });
        keepAlive.scheduleAtFixedRate(
                () -> { try { actx.heartbeat("alive"); } catch (Exception ignored) {} },
                60, 60, TimeUnit.SECONDS);

        long narrated = 0;
        long verifierPassed = 0;
        long verifierFailed = 0;

        ObjectMapper json = new ObjectMapper();
        LlamaClient llama = LlamaClient.fromEnv();
        NarrationPipeline pipeline = new NarrationPipeline(llama, MODEL_ID);

        try (Connection conn = openConnection()) {
            SchemaManager.apply(conn);

            // Stream selected events for the day, ordered by narration_rank so
            // the top-scoring of each scorer gets narrated first.
            String pickSql = """
                    SELECT selected_id, trading_date, scorer_id, symbol, ts, score, breakdown::text
                    FROM selected_events
                    WHERE trading_date = ?
                    ORDER BY scorer_id, narration_rank
                    """;

            try (PreparedStatement st = conn.prepareStatement(pickSql)) {
                st.setObject(1, tradingDate);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        long   selectedId = rs.getLong("selected_id");
                        String scorerId   = rs.getString("scorer_id");
                        String symbol     = rs.getString("symbol");
                        Timestamp tsTs    = rs.getTimestamp("ts");
                        double score      = rs.getDouble("score");
                        JsonNode breakdown = json.readTree(rs.getString("breakdown"));

                        NarrationPipeline.NarrationInput in = new NarrationPipeline.NarrationInput(
                                selectedId,
                                tradingDate.toString(),
                                scorerId,
                                symbol,
                                tsTs.toInstant(),
                                score,
                                breakdown);

                        try {
                            NarrationPipeline.Result result = pipeline.narrate(in);
                            upsertNarrative(conn, in, result);
                            narrated++;
                            if (result.verify().passed()) verifierPassed++; else verifierFailed++;
                            LOG.info("narrated  selected_id={} scorer={} symbol={} elapsed_ms={} verifier_passed={} mismatches={}",
                                    selectedId, scorerId, symbol, result.elapsedMs(),
                                    result.verify().passed(), result.verify().mismatches().size());
                            actx.heartbeat("narrated:" + narrated);
                        } catch (Exception e) {
                            LOG.error("narrate failed  selected_id={} scorer={} symbol={} err={}",
                                    selectedId, scorerId, symbol, e.getMessage(), e);
                            // Per-event isolation: keep going. We'd rather have
                            // 89 narrations + 1 known failure than 0.
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("narrateEvents failed for date=" + tradingDate, e);
        } finally {
            keepAlive.shutdownNow();
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        LOG.info("narrate done  date={} narrated={} verifier_passed={} verifier_failed={} elapsed_ms={}",
                tradingDate, narrated, verifierPassed, verifierFailed, elapsedMs);
        return narrated;
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
                                 final NarrationPipeline.Result result) throws Exception {
        ObjectMapper json = new ObjectMapper();
        String verifierNotes = json.createObjectNode()
                .put("numbers_checked", result.verify().numbersChecked())
                .set("mismatches", json.valueToTree(result.verify().mismatches()))
                .toString();

        String sql = """
                INSERT INTO narratives (
                    event_hash, trading_date, event_type, event_ts, symbol,
                    score, score_breakdown, narrative, model_id,
                    selected_id, blueprint, verifier_passed, verifier_notes
                )
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?::jsonb)
                ON CONFLICT (event_hash) DO UPDATE SET
                    narrative       = EXCLUDED.narrative,
                    blueprint       = EXCLUDED.blueprint,
                    verifier_passed = EXCLUDED.verifier_passed,
                    verifier_notes  = EXCLUDED.verifier_notes,
                    model_id        = EXCLUDED.model_id,
                    created_at      = NOW()
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
