package com.longexposure.temporal.activities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * See {@link CombineRelatedEventsActivity}. Per-symbol interval-overlap
 * sweep, no time windows, no pair tables. If two events' intervals
 * don't literally overlap they stay as separate scored events.
 */
public final class CombineRelatedEventsActivityImpl implements CombineRelatedEventsActivity {

    private static final Logger LOG = LoggerFactory.getLogger(CombineRelatedEventsActivityImpl.class);

    @Override
    public long combineRelatedEvents(final LocalDate tradingDate) {
        ActivityExecutionContext actx = Activity.getExecutionContext();
        LOG.info("combine start  date={}", tradingDate);
        long t0 = System.nanoTime();

        long combinedWritten;
        try (Connection conn = openConnection()) {
            SchemaManager.apply(conn);
            conn.setAutoCommit(false);

            // Pre-clean for idempotency. Any prior combine run for this
            // date gets undone: delete combined rows, clear the
            // subsumed-by markers on constituents.
            try (PreparedStatement st = conn.prepareStatement(
                    "DELETE FROM scored_events WHERE trading_date = ? AND scorer_id = 'combined'")) {
                st.setObject(1, tradingDate);
                long deleted = st.executeLargeUpdate();
                LOG.info("combine pre-clean  date={} combined_rows_deleted={}", tradingDate, deleted);
            }
            try (PreparedStatement st = conn.prepareStatement(
                    "UPDATE scored_events SET subsumed_by_event_id = NULL WHERE trading_date = ? AND subsumed_by_event_id IS NOT NULL")) {
                st.setObject(1, tradingDate);
                long cleared = st.executeLargeUpdate();
                LOG.info("combine pre-clean  date={} subsumed_markers_cleared={}", tradingDate, cleared);
            }
            conn.commit();
            actx.heartbeat("preclean_done");

            // Pull all events for the date, ordered by (symbol, ts).
            // The per-symbol stream is processed by the sweep below; no
            // per-symbol query needed.
            combinedWritten = runSweep(conn, tradingDate, actx);
            conn.commit();
        } catch (Exception e) {
            throw new RuntimeException("combineRelatedEvents failed for date=" + tradingDate, e);
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        LOG.info("combine done  date={} combined_written={} elapsed_ms={}",
                tradingDate, combinedWritten, elapsedMs);
        return combinedWritten;
    }

    /**
     * Per-symbol interval-overlap sweep. Reads all events for the date
     * ordered by {@code (symbol, ts)}. As long as the next event's
     * symbol matches the cluster's AND its {@code ts} is <= the
     * cluster's running {@code max(ts_end)}, it joins the cluster.
     * Else, close the previous cluster, start a new one with this event.
     */
    private long runSweep(final Connection conn, final LocalDate date, final ActivityExecutionContext actx)
            throws Exception {
        ObjectMapper json = new ObjectMapper();

        // Read all non-subsumed scored events ordered by (symbol, ts).
        // After pre-clean, subsumed_by_event_id is NULL for all, but
        // we keep the WHERE filter for safety on partial pre-cleans.
        String pickSql = """
                SELECT event_id, trading_date, symbol, ts, ts_end, scorer_id, score,
                       breakdown::text, source_refs::text
                FROM scored_events
                WHERE trading_date = ? AND subsumed_by_event_id IS NULL AND scorer_id <> 'combined'
                ORDER BY symbol, ts
                """;

        long combinedCount = 0;
        long rowsRead = 0;

        try (PreparedStatement st = conn.prepareStatement(pickSql)) {
            st.setFetchSize(10_000);
            st.setObject(1, date);
            try (ResultSet rs = st.executeQuery()) {
                List<ScoredEventRow> cluster = new ArrayList<>(8);
                String clusterSymbol = null;
                Instant clusterMaxTsEnd = null;

                while (rs.next()) {
                    ScoredEventRow row = readRow(rs, json);
                    rowsRead++;
                    if (rowsRead % 25_000 == 0) actx.heartbeat("sweep:" + rowsRead);

                    Instant rowEnd = (row.tsEnd != null) ? row.tsEnd : row.ts;

                    boolean sameSymbol = row.symbol.equals(clusterSymbol);
                    boolean overlaps = clusterMaxTsEnd != null
                            && !row.ts.isAfter(clusterMaxTsEnd);

                    if (sameSymbol && overlaps) {
                        cluster.add(row);
                        if (rowEnd.isAfter(clusterMaxTsEnd)) clusterMaxTsEnd = rowEnd;
                    } else {
                        if (cluster.size() >= 2) {
                            writeCombined(conn, cluster, json);
                            combinedCount++;
                        }
                        cluster.clear();
                        cluster.add(row);
                        clusterSymbol = row.symbol;
                        clusterMaxTsEnd = rowEnd;
                    }
                }
                // Final cluster
                if (cluster.size() >= 2) {
                    writeCombined(conn, cluster, json);
                    combinedCount++;
                }
            }
        }
        LOG.info("sweep done  rows_read={} clusters_combined={}", rowsRead, combinedCount);
        return combinedCount;
    }

    /**
     * Write one combined event row and mark the constituent rows as
     * subsumed. Combined event takes the max-score constituent's
     * {@code ts} for ordering, {@code max(ts_end)} as its end, and
     * {@code max(score)} as its score.
     */
    private void writeCombined(final Connection conn, final List<ScoredEventRow> cluster, final ObjectMapper json)
            throws Exception {
        // Sort cluster by score DESC so the primary constituent is first.
        cluster.sort((a, b) -> Double.compare(b.score, a.score));
        ScoredEventRow primary = cluster.get(0);

        Instant minTs = cluster.stream().map(r -> r.ts).min(Instant::compareTo).orElse(primary.ts);
        Instant maxTsEnd = cluster.stream()
                .map(r -> r.tsEnd != null ? r.tsEnd : r.ts)
                .max(Instant::compareTo)
                .orElse(primary.ts);
        long timeSpanMs = (maxTsEnd.toEpochMilli() - minTs.toEpochMilli());

        // Build combined breakdown
        ObjectNode breakdown = json.createObjectNode();
        breakdown.put("kind", "combined");
        breakdown.put("symbol", primary.symbol);
        breakdown.put("primary_scorer", primary.scorerId);
        breakdown.put("constituent_count", cluster.size());
        breakdown.put("time_span_ms", timeSpanMs);

        ArrayNode scorerIds = breakdown.putArray("constituent_scorers");
        for (ScoredEventRow r : cluster) scorerIds.add(r.scorerId);

        ArrayNode constituents = breakdown.putArray("constituents");
        for (ScoredEventRow r : cluster) {
            ObjectNode c = constituents.addObject();
            c.put("scorer_id", r.scorerId);
            c.put("score", r.score);
            c.set("breakdown", r.breakdown);
        }

        // Inherit enrichment fields from primary's breakdown (if present)
        // so the LLM sees them at the top level alongside `constituents`.
        for (String enrichKey : new String[]{
                "company_name", "listing_exchange", "is_etf",
                "round_lot", "prev_close_dollars", "luld_tier"}) {
            JsonNode v = primary.breakdown.get(enrichKey);
            if (v != null && !v.isNull()) breakdown.set(enrichKey, v);
        }

        // Build aggregated source_refs (concatenation)
        ArrayNode sourceRefs = json.createArrayNode();
        for (ScoredEventRow r : cluster) {
            if (r.sourceRefs.isArray()) r.sourceRefs.forEach(sourceRefs::add);
        }

        // INSERT the combined row, get its event_id back
        long combinedEventId;
        String insertSql = """
                INSERT INTO scored_events (
                    trading_date, symbol, ts, ts_end, scorer_id, score,
                    breakdown, source_refs, pipeline_run, subsumed_by_event_id
                )
                VALUES (?, ?, ?, ?, 'combined', ?, ?::jsonb, ?::jsonb, NULL, NULL)
                RETURNING event_id
                """;
        try (PreparedStatement st = conn.prepareStatement(insertSql)) {
            st.setObject(1, primary.tradingDate);
            st.setString(2, primary.symbol);
            st.setTimestamp(3, Timestamp.from(minTs));
            st.setTimestamp(4, Timestamp.from(maxTsEnd));
            st.setDouble(5, primary.score);
            st.setString(6, breakdown.toString());
            st.setString(7, sourceRefs.toString());
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("INSERT ... RETURNING returned no row");
                combinedEventId = rs.getLong(1);
            }
        }

        // Mark all constituent rows as subsumed
        StringBuilder updateSql = new StringBuilder(
                "UPDATE scored_events SET subsumed_by_event_id = ? WHERE event_id IN (");
        for (int i = 0; i < cluster.size(); i++) {
            updateSql.append(i == 0 ? "?" : ",?");
        }
        updateSql.append(")");
        try (PreparedStatement st = conn.prepareStatement(updateSql.toString())) {
            st.setLong(1, combinedEventId);
            for (int i = 0; i < cluster.size(); i++) {
                st.setLong(2 + i, cluster.get(i).eventId);
            }
            st.executeUpdate();
        }
    }

    private static ScoredEventRow readRow(final ResultSet rs, final ObjectMapper json) throws Exception {
        ScoredEventRow r = new ScoredEventRow();
        r.eventId      = rs.getLong("event_id");
        r.tradingDate  = rs.getObject("trading_date", LocalDate.class);
        r.symbol       = rs.getString("symbol");
        r.ts           = rs.getTimestamp("ts").toInstant();
        Timestamp end  = rs.getTimestamp("ts_end");
        r.tsEnd        = (end != null) ? end.toInstant() : null;
        r.scorerId     = rs.getString("scorer_id");
        r.score        = rs.getDouble("score");
        r.breakdown    = json.readTree(rs.getString("breakdown"));
        r.sourceRefs   = json.readTree(rs.getString("source_refs"));
        return r;
    }

    private static final class ScoredEventRow {
        long eventId;
        LocalDate tradingDate;
        String symbol;
        Instant ts;
        Instant tsEnd;
        String scorerId;
        double score;
        JsonNode breakdown;
        JsonNode sourceRefs;
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
