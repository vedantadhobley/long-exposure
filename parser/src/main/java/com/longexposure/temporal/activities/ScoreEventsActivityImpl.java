package com.longexposure.temporal.activities;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.longexposure.scoring.EventScorer;
import com.longexposure.scoring.EventScorerRegistry;
import com.longexposure.scoring.ScoredEvent;
import com.longexposure.scoring.ScoringContext;
import com.longexposure.scoring.SymbolMetadata;
import com.longexposure.storage.SchemaManager;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ScoreEventsActivityImpl implements ScoreEventsActivity {

    private static final Logger LOG = LoggerFactory.getLogger(ScoreEventsActivityImpl.class);

    /** COPY columns in order. event_id (BIGSERIAL) and scored_at (DEFAULT NOW()) are omitted. */
    private static final String COPY_COLUMNS =
            "trading_date, symbol, ts, ts_end, scorer_id, score, breakdown, source_refs, pipeline_run";

    @Override
    public long scoreEvents(final LocalDate tradingDate, final UUID pipelineRunId) {
        ActivityExecutionContext actx = Activity.getExecutionContext();
        LOG.info("score start  date={} pipeline_run={} scorers={}",
                tradingDate, pipelineRunId, EventScorerRegistry.ALL.size());

        long t0 = System.nanoTime();
        long totalWritten = 0;
        ObjectMapper json = new ObjectMapper();

        // Background heartbeat thread: keeps the activity alive while
        // long-running SQL (e.g. the PostCancelCluster join over 78M × 78M
        // rows) is executing on the Postgres side and the JDBC stream
        // hasn't yielded any rows yet — so the per-row heartbeats inside
        // each scorer wouldn't fire.
        ScheduledExecutorService keepAlive = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "score-events-heartbeat");
            t.setDaemon(true);
            return t;
        });
        AtomicReference<String> currentStage = new AtomicReference<>("starting");
        keepAlive.scheduleAtFixedRate(
                () -> {
                    try { actx.heartbeat("keep_alive:" + currentStage.get()); } catch (Exception ignored) {}
                },
                60, 60, TimeUnit.SECONDS);

        try (Connection conn = openConnection()) {
            // Idempotent — ensures scored_events table exists when this
            // activity runs without a preceding parse activity (e.g. via
            // ScoreWorkflow). SchemaManager.apply internally flips
            // autoCommit=true (DDL wants per-statement commits), so we
            // set our autoCommit=false AFTER it completes.
            SchemaManager.apply(conn);

            // CRITICAL: Postgres's JDBC driver silently ignores setFetchSize()
            // when autoCommit=true (the default) and instead buffers the
            // ENTIRE result set into Java memory before yielding the first
            // row. With multi-million-row joins (PostCancelCluster) that's
            // an instant OOM. Setting autoCommit=false enables cursor-based
            // streaming and the per-statement fetchSize starts working.
            // We commit explicitly after pre-clean and after each scorer's
            // COPY so the writes are durable.
            conn.setAutoCommit(false);

            // Modest work_mem bump for the per-symbol GROUP BYs in
            // Iceberg + LiquidityWithdrawal scorers. The PostCancel +
            // Layering JOINs that used to need 2 GB+ are gone now —
            // both read order_lifecycle as a sequential partial-index
            // scan instead. 256 MB is comfortable for the remaining
            // scorer queries; the heavy memory tuning that the JOIN
            // version needed (work_mem='2GB' + hash_mem_multiplier=2)
            // is no longer required.
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("SET work_mem = '256MB'");
            }
            conn.commit();

            // Pre-clean: scored_events for this date.
            long deleted;
            try (PreparedStatement st = conn.prepareStatement(
                    "DELETE FROM scored_events WHERE trading_date = ?")) {
                st.setObject(1, tradingDate);
                deleted = st.executeLargeUpdate();
            }
            conn.commit();
            LOG.info("score pre-clean  date={} rows_deleted={}", tradingDate, deleted);
            actx.heartbeat("preclean_done:" + deleted);

            // Load symbol metadata cache once for the whole run.
            // ~12 000 rows from a tiny reference table — sub-second load,
            // O(1) lookups for every event afterward. Empty map if the
            // RefreshSymbolMetadataActivity has never run yet (scorers
            // null-check and degrade gracefully).
            Map<String, SymbolMetadata> symbols = loadSymbols(conn);
            LOG.info("symbols loaded  count={}", symbols.size());
            actx.heartbeat("symbols_loaded:" + symbols.size());

            ScoringContext sctx = new ScoringContext(
                    tradingDate, conn, pipelineRunId, json,
                    detail -> actx.heartbeat("scorer_progress:" + detail),
                    symbols);
            CopyManager copyManager = conn.unwrap(PGConnection.class).getCopyAPI();

            for (EventScorer scorer : EventScorerRegistry.ALL) {
                currentStage.set(scorer.id());
                try {
                    long scoredHere = runOne(scorer, sctx, copyManager);
                    totalWritten += scoredHere;
                    LOG.info("scorer done  id={} rows_written={} running_total={}",
                            scorer.id(), scoredHere, totalWritten);
                    actx.heartbeat("scorer_done:" + scorer.id() + ":" + scoredHere);
                } catch (OutOfMemoryError oom) {
                    // Critical — re-throw so the JVM's -XX:+ExitOnOutOfMemoryError
                    // fires and the activity fails cleanly. Other scorers
                    // can't safely continue with the heap exhausted.
                    LOG.error("OOM in scorer {} — re-throwing for clean activity fail", scorer.id(), oom);
                    throw oom;
                } catch (Exception e) {
                    // Per-scorer isolation: one failed scorer shouldn't kill the run.
                    LOG.error("scorer failed  id={} err={} — continuing with next scorer",
                            scorer.id(), e.getMessage(), e);
                    actx.heartbeat("scorer_failed:" + scorer.id());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("scoreEvents failed for date=" + tradingDate, e);
        } finally {
            keepAlive.shutdownNow();
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        LOG.info("score done  date={} total_rows={} elapsed_ms={}", tradingDate, totalWritten, elapsedMs);
        return totalWritten;
    }

    /** Flush COPY every N rows to keep the buffer bounded. */
    private static final long COPY_FLUSH_EVERY = 10_000L;

    private long runOne(final EventScorer scorer, final ScoringContext ctx, final CopyManager copyManager) throws Exception {
        // Push model: scorer emits events one at a time via the Consumer
        // we pass in. We accumulate at most COPY_FLUSH_EVERY rows of COPY
        // text before draining to Postgres, then reset. Memory is bounded
        // regardless of how many events the scorer produces over the day.
        final StringBuilder buf = new StringBuilder();
        final long[] count = {0};
        final String sql = "COPY scored_events (" + COPY_COLUMNS + ") FROM STDIN WITH (FORMAT text)";

        Consumer<ScoredEvent> emit = se -> {
            appendCopyRow(buf, se, ctx.pipelineRunId());
            count[0]++;
            if (count[0] % COPY_FLUSH_EVERY == 0) {
                try {
                    copyManager.copyIn(sql, new StringReader(buf.toString()));
                    buf.setLength(0);
                } catch (Exception e) {
                    throw new RuntimeException("incremental COPY flush failed", e);
                }
            }
        };

        scorer.score(ctx, emit);

        // Final flush — whatever's left in the buffer.
        if (buf.length() > 0) {
            copyManager.copyIn(sql, new StringReader(buf.toString()));
        }
        // We're in autoCommit=false mode — commit so the rows are durable
        // before the next scorer starts.
        ctx.conn().commit();
        return count[0];
    }

    /**
     * Append one COPY TEXT-format row to {@code buf}. Column order matches
     * {@link #COPY_COLUMNS}. Nulls written as {@code \N}.
     */
    private static void appendCopyRow(final StringBuilder buf, final ScoredEvent se, final UUID pipelineRunId) {
        // trading_date
        buf.append(se.tradingDate().toString()); buf.append('\t');
        // symbol
        appendEscaped(buf, se.symbol()); buf.append('\t');
        // ts (TIMESTAMPTZ)
        appendInstant(buf, se.ts()); buf.append('\t');
        // ts_end (nullable)
        if (se.tsEnd() != null) appendInstant(buf, se.tsEnd()); else buf.append("\\N");
        buf.append('\t');
        // scorer_id
        appendEscaped(buf, se.scorerId()); buf.append('\t');
        // score
        buf.append(se.score()); buf.append('\t');
        // breakdown JSONB (escape tab/newline/cr/backslash)
        appendEscaped(buf, se.breakdown().toString()); buf.append('\t');
        // source_refs JSONB
        appendEscaped(buf, se.sourceRefs().toString()); buf.append('\t');
        // pipeline_run (nullable)
        if (pipelineRunId != null) buf.append(pipelineRunId.toString()); else buf.append("\\N");
        buf.append('\n');
    }

    private static void appendInstant(final StringBuilder sb, final Instant i) {
        // Postgres accepts ISO-8601 with offset; Instant.toString() yields
        // an 'Z'-suffixed UTC representation which Postgres parses as TIMESTAMPTZ.
        sb.append(i.toString());
    }

    private static void appendEscaped(final StringBuilder sb, final String s) {
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default   -> sb.append(c);
            }
        }
    }

    /**
     * Load the {@code symbols} reference table into an in-memory map.
     * Returns an empty map if the table is missing/empty — scorers + the
     * SymbolFields helper are null-safe, so missing symbols just means the LLM
     * gets the ticker without company-name etc. (same as today).
     */
    private static Map<String, SymbolMetadata> loadSymbols(final Connection conn) {
        Map<String, SymbolMetadata> out = new HashMap<>(20_000);
        String sql = """
                SELECT symbol, company_name, listing_exchange, is_etf,
                       round_lot, prev_close_dollars, luld_tier
                FROM symbols
                """;
        try (PreparedStatement st = conn.prepareStatement(sql);
             java.sql.ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                String  symbol           = rs.getString("symbol");
                String  companyName      = rs.getString("company_name");
                String  listingExchange  = rs.getString("listing_exchange");
                Boolean isEtf            = (Boolean) rs.getObject("is_etf");
                Integer roundLot         = (Integer) rs.getObject("round_lot");
                Double  prevClose        = (Double)  rs.getObject("prev_close_dollars");
                String  luldTier         = rs.getString("luld_tier");
                out.put(symbol, new SymbolMetadata(
                        symbol, companyName, listingExchange, isEtf,
                        roundLot, prevClose, luldTier));
            }
        } catch (Exception e) {
            // Likely "table doesn't exist yet" — log and proceed empty.
            LOG.warn("symbols table not readable, scoring will proceed without enrichment: {}", e.getMessage());
        }
        return out;
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
