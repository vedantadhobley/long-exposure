package com.longexposure.temporal.activities;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.longexposure.scoring.EventScorer;
import com.longexposure.scoring.EventScorerRegistry;
import com.longexposure.scoring.ScoredEvent;
import com.longexposure.scoring.ScoringContext;
import com.longexposure.storage.SchemaManager;
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
            // ScoreOnlyWorkflow). SchemaManager.apply internally flips
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

            ScoringContext sctx = new ScoringContext(
                    tradingDate, conn, pipelineRunId, json,
                    detail -> actx.heartbeat("scorer_progress:" + detail));
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

    private long runOne(final EventScorer scorer, final ScoringContext ctx, final CopyManager copyManager) throws Exception {
        // Buffer this scorer's output and COPY it in one shot. Output
        // volume per scorer is small (typically hundreds, not millions),
        // so one buffer per scorer is fine.
        StringBuilder buf = new StringBuilder();
        long[] count = {0};

        scorer.score(ctx).forEach(se -> {
            appendCopyRow(buf, se, ctx.pipelineRunId());
            count[0]++;
        });

        if (count[0] == 0) return 0;

        String sql = "COPY scored_events (" + COPY_COLUMNS + ") FROM STDIN WITH (FORMAT text)";
        copyManager.copyIn(sql, new StringReader(buf.toString()));
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
