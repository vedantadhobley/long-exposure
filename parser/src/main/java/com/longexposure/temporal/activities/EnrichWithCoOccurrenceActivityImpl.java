package com.longexposure.temporal.activities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class EnrichWithCoOccurrenceActivityImpl implements EnrichWithCoOccurrenceActivity {

    private static final Logger LOG = LoggerFactory.getLogger(EnrichWithCoOccurrenceActivityImpl.class);

    /**
     * Parent-candidate selection: same percentile-rank rule that
     * {@link SelectTopEventsActivityImpl} uses. Events whose rank within
     * their scorer falls inside the budget are candidates to be parents
     * (absorb nested children). Events outside the budget aren't worth
     * enriching (they won't be selected anyway).
     */
    private static final double PARENT_PERCENTILE = 0.05;
    private static final int    PARENT_FLOOR      = 1;
    private static final int    PARENT_CEILING    = 30;

    @Override
    public long enrichWithCoOccurrence(final LocalDate tradingDate) {
        ActivityExecutionContext actx = Activity.getExecutionContext();
        LOG.info("enrich start  date={} percentile={} floor={} ceiling={}",
                tradingDate, PARENT_PERCENTILE, PARENT_FLOOR, PARENT_CEILING);
        long t0 = System.nanoTime();

        long parentsEnriched;
        try (Connection conn = openConnection()) {
            SchemaManager.apply(conn);
            conn.setAutoCommit(false);

            // Pre-clean: remove any prior enrichment so this run is idempotent.
            preClean(conn, tradingDate);
            actx.heartbeat("preclean_done");

            // Identify candidate parents (events that will pass selection).
            List<Candidate> candidates = identifyCandidates(conn, tradingDate);
            LOG.info("candidates identified  date={} count={}", tradingDate, candidates.size());

            // Process in score-descending order so larger events claim
            // children first if multiple candidates' intervals nest.
            candidates.sort((a, b) -> Double.compare(b.score, a.score));

            parentsEnriched = 0;
            int processed = 0;
            for (Candidate parent : candidates) {
                int childrenAbsorbed = enrichOneParent(conn, parent);
                if (childrenAbsorbed > 0) parentsEnriched++;
                if (++processed % 25 == 0) actx.heartbeat("enriched:" + processed);
            }
            conn.commit();
        } catch (Exception e) {
            throw new RuntimeException("enrichWithCoOccurrence failed for date=" + tradingDate, e);
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        LOG.info("enrich done  date={} parents_enriched={} elapsed_ms={}",
                tradingDate, parentsEnriched, elapsedMs);
        return parentsEnriched;
    }

    /**
     * Reset all enrichment artifacts for the date — drop the
     * {@code co_occurring} block from any breakdown that has it, and
     * clear every {@code subsumed_by_event_id}. Makes the activity
     * idempotent under retry.
     */
    private void preClean(final Connection conn, final LocalDate date) throws Exception {
        // Note: avoid the JSONB `?` "key exists" operator in prepared
        // statements — JDBC treats it as a parameter placeholder and
        // throws "No value specified for parameter N". Use the `->`
        // accessor instead (returns NULL if key is absent).
        try (PreparedStatement st = conn.prepareStatement(
                "UPDATE scored_events SET breakdown = breakdown - 'co_occurring' " +
                "WHERE trading_date = ? AND breakdown->'co_occurring' IS NOT NULL")) {
            st.setObject(1, date);
            long n = st.executeLargeUpdate();
            LOG.info("preclean co_occurring  date={} rows={}", date, n);
        }
        try (PreparedStatement st = conn.prepareStatement(
                "UPDATE scored_events SET subsumed_by_event_id = NULL " +
                "WHERE trading_date = ? AND subsumed_by_event_id IS NOT NULL")) {
            st.setObject(1, date);
            long n = st.executeLargeUpdate();
            LOG.info("preclean subsumed markers  date={} rows={}", date, n);
        }
        conn.commit();
    }

    /**
     * Run the same percentile-rank query selection uses, but only as a
     * candidate finder — events that will pass selection. Pulls them
     * with full metadata so per-parent processing doesn't need a
     * second round-trip.
     */
    private List<Candidate> identifyCandidates(final Connection conn, final LocalDate date) throws Exception {
        String sql = """
                WITH ranked AS (
                    SELECT event_id, scorer_id, symbol, ts, ts_end, score,
                           row_number() OVER (PARTITION BY scorer_id ORDER BY score DESC, ts) AS rk,
                           count(*)    OVER (PARTITION BY scorer_id) AS scorer_count
                    FROM scored_events
                    WHERE trading_date = ? AND subsumed_by_event_id IS NULL
                )
                SELECT event_id, scorer_id, symbol, ts, ts_end, score
                FROM ranked
                WHERE rk <= GREATEST(?, LEAST(?, CEIL(? * scorer_count)::int))
                """;
        List<Candidate> out = new ArrayList<>(256);
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, date);
            st.setInt(2, PARENT_FLOOR);
            st.setInt(3, PARENT_CEILING);
            st.setDouble(4, PARENT_PERCENTILE);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    Candidate c = new Candidate();
                    c.eventId  = rs.getLong("event_id");
                    c.scorerId = rs.getString("scorer_id");
                    c.symbol   = rs.getString("symbol");
                    c.ts       = rs.getTimestamp("ts").toInstant();
                    Timestamp end = rs.getTimestamp("ts_end");
                    c.tsEnd    = (end != null) ? end.toInstant() : c.ts;  // instantaneous events
                    c.score    = rs.getDouble("score");
                    out.add(c);
                }
            }
        }
        return out;
    }

    /**
     * For one candidate parent: find same-symbol DIFFERENT-scorer events
     * whose interval is contained inside the parent's interval. If any
     * exist, aggregate them into a {@code co_occurring} block on the
     * parent's breakdown and mark them subsumed.
     *
     * @return count of children absorbed (0 if none — parent breakdown
     *         is left untouched in that case).
     */
    private int enrichOneParent(final Connection conn, final Candidate parent) throws Exception {
        // Find candidate children: same symbol, different scorer, nested
        // inside parent's interval, not already subsumed by a bigger
        // event we processed earlier in this run.
        List<Child> children = new ArrayList<>();
        String findChildren = """
                SELECT event_id, scorer_id, score, breakdown::text
                FROM scored_events
                WHERE symbol = ?
                  AND trading_date::date = ?::date
                  AND scorer_id <> ?
                  AND event_id <> ?
                  AND subsumed_by_event_id IS NULL
                  AND ts      >= ?
                  AND ts_end  <= ?
                """;
        ObjectMapper json = new ObjectMapper();
        try (PreparedStatement st = conn.prepareStatement(findChildren)) {
            st.setString(1, parent.symbol);
            st.setObject(2, parent.ts.atZone(java.time.ZoneOffset.UTC).toLocalDate());
            st.setString(3, parent.scorerId);
            st.setLong(4, parent.eventId);
            st.setTimestamp(5, Timestamp.from(parent.ts));
            st.setTimestamp(6, Timestamp.from(parent.tsEnd));
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    Child child = new Child();
                    child.eventId   = rs.getLong("event_id");
                    child.scorerId  = rs.getString("scorer_id");
                    child.score     = rs.getDouble("score");
                    child.breakdown = json.readTree(rs.getString("breakdown"));
                    children.add(child);
                }
            }
        }

        if (children.isEmpty()) return 0;

        // Aggregate by scorer_id. Per-scorer-type we count occurrences +
        // sum well-known numeric fields where they exist.
        Map<String, ScorerAggregate> aggregates = new HashMap<>();
        for (Child c : children) {
            ScorerAggregate agg = aggregates.computeIfAbsent(c.scorerId, k -> new ScorerAggregate());
            agg.count++;
            agg.scoreSum += c.score;
            // Best-effort field aggregation. The fields differ per scorer
            // type; we accumulate whichever are present.
            accumulateNumeric(agg.fieldSums, agg.fieldCounts, c.breakdown, "total_shares");
            accumulateNumeric(agg.fieldSums, agg.fieldCounts, c.breakdown, "orders");
            accumulateNumeric(agg.fieldSums, agg.fieldCounts, c.breakdown, "deletes");
            accumulateNumeric(agg.fieldSums, agg.fieldCounts, c.breakdown, "distinct_levels");
            accumulateNumeric(agg.fieldSums, agg.fieldCounts, c.breakdown, "notional_dollars");
        }

        // Build the co_occurring JSON
        ObjectNode coOccurring = json.createObjectNode();
        ObjectNode duringEvent = coOccurring.putObject("during_event");
        int totalChildren = 0;
        for (Map.Entry<String, ScorerAggregate> e : aggregates.entrySet()) {
            String scorerId = e.getKey();
            ScorerAggregate agg = e.getValue();
            ObjectNode per = duringEvent.putObject(scorerId);
            per.put("count", com.longexposure.scoring.Humanize.formatCount(agg.count));
            // Per-field sums where they were observed. Sums that are integer-valued
            // counts (shares, orders, levels) get thousand-separator formatting so
            // narrations render "565,131 shares" not "565131 shares". Currency
            // sums (notional_dollars) keep the rounded numeric form.
            for (Map.Entry<String, Double> fs : agg.fieldSums.entrySet()) {
                String field = fs.getKey();
                int n = agg.fieldCounts.getOrDefault(field, 0);
                if (n == 0) continue;
                double sum = fs.getValue();
                if (isIntegerCountField(field)) {
                    per.put("sum_" + field, com.longexposure.scoring.Humanize.formatCount((long) sum));
                } else {
                    per.put("sum_" + field, com.longexposure.scoring.Humanize.round2(sum));
                }
            }
            totalChildren += agg.count;
        }
        coOccurring.put("total_children", com.longexposure.scoring.Humanize.formatCount(totalChildren));

        // Update parent: merge the co_occurring block into the breakdown.
        try (PreparedStatement st = conn.prepareStatement(
                "UPDATE scored_events " +
                "SET breakdown = jsonb_set(breakdown, '{co_occurring}', ?::jsonb, true) " +
                "WHERE event_id = ?")) {
            st.setString(1, coOccurring.toString());
            st.setLong(2, parent.eventId);
            st.executeUpdate();
        }

        // Mark children as subsumed.
        StringBuilder updateSql = new StringBuilder(
                "UPDATE scored_events SET subsumed_by_event_id = ? WHERE event_id IN (");
        for (int i = 0; i < children.size(); i++) {
            updateSql.append(i == 0 ? "?" : ",?");
        }
        updateSql.append(")");
        try (PreparedStatement st = conn.prepareStatement(updateSql.toString())) {
            st.setLong(1, parent.eventId);
            for (int i = 0; i < children.size(); i++) {
                st.setLong(2 + i, children.get(i).eventId);
            }
            st.executeUpdate();
        }

        return children.size();
    }

    private static void accumulateNumeric(
            final Map<String, Double> sums,
            final Map<String, Integer> counts,
            final JsonNode breakdown,
            final String field) {
        JsonNode v = breakdown.get(field);
        if (v == null) return;
        Double parsed = parseNumeric(v);
        if (parsed == null) return;
        sums.merge(field, parsed, Double::sum);
        counts.merge(field, 1, Integer::sum);
    }

    /**
     * Parse a JSON value as a double. Handles both native numeric form
     * and the comma-formatted string form that scorers now emit for
     * integer counts (e.g. {@code "4,895"} from {@code Humanize.formatCount}).
     * Returns null if the value isn't parseable as a number.
     */
    private static Double parseNumeric(final JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.asDouble();
        if (v.isTextual()) {
            String s = v.asText().replace(",", "").trim();
            if (s.isEmpty()) return null;
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Which co_occurring sum_* fields are integer counts (shares, orders,
     * levels) and should be formatted with thousand separators when
     * written into the co_occurring block. Currency fields (notional_dollars)
     * keep the rounded float form for consistency with the parent
     * scorer's breakdown.
     */
    private static boolean isIntegerCountField(final String field) {
        return switch (field) {
            case "total_shares", "orders", "distinct_levels" -> true;
            default -> false;
        };
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

    private static final class Candidate {
        long    eventId;
        String  scorerId;
        String  symbol;
        Instant ts;
        Instant tsEnd;
        double  score;
    }

    private static final class Child {
        long     eventId;
        String   scorerId;
        double   score;
        JsonNode breakdown;
    }

    /** Per-scorer-type aggregator for the co_occurring summary. */
    private static final class ScorerAggregate {
        int count = 0;
        double scoreSum = 0.0;
        final Map<String, Double>  fieldSums   = new HashMap<>();
        final Map<String, Integer> fieldCounts = new HashMap<>();
    }
}
