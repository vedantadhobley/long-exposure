package com.longexposure.scoring.scorers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.longexposure.scoring.EventScorer;
import com.longexposure.scoring.ScoredEvent;
import com.longexposure.scoring.ScoringContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Halt detector. Reads {@code status_events} for {@code event_kind='H'}
 * (Trading Status messages) on the trading date and emits one
 * {@link ScoredEvent} per halt → resume pair.
 *
 * <p>Detection: a {@code sub_type='H'} row starts a halt for the symbol;
 * the next {@code status_events} row for that same symbol with
 * {@code sub_type IN ('T','O','P')} closes it. The halt duration is
 * {@code resume.ts - halt.ts}.
 *
 * <p>Score for v1: halt duration in seconds. Liquidity-tier weighting is
 * a TODO — needs a symbol-metadata source we don't have wired yet.
 *
 * <p>Breakdown JSON shape:
 * <pre>
 *   {
 *     "halt_reason":      "T1",
 *     "halt_duration_s":  243,
 *     "halt_start_iso":   "2026-05-08T14:23:14.847291000Z",
 *     "halt_end_iso":     "2026-05-08T14:27:17.521003000Z",
 *     "halt_end_sub_type": "T"
 *   }
 * </pre>
 *
 * <p>Source refs: a single entry pointing at the halt-start row in
 * {@code status_events}, keyed by {@code (symbol, ts_nanos)}.
 */
public final class HaltScorer implements EventScorer {

    private static final Logger LOG = LoggerFactory.getLogger(HaltScorer.class);

    @Override
    public String id() { return "halt"; }

    @Override
    public Stream<ScoredEvent> score(final ScoringContext ctx) {
        // Window-function pairing of halt rows with their resume rows
        // (next non-'H' status for the same symbol). NULL halt_end means
        // the halt was still open at the next session boundary; for v1
        // we emit those with halt_duration_s = null and let the narrator
        // handle it (score = 0 → low-ranked, but visible).
        String sql = """
                SELECT halt_symbol,
                       halt_ts,
                       halt_ts_nanos,
                       halt_reason,
                       next_ts,
                       next_sub_type
                FROM (
                    SELECT symbol      AS halt_symbol,
                           ts          AS halt_ts,
                           ts_nanos    AS halt_ts_nanos,
                           reason      AS halt_reason,
                           sub_type    AS halt_sub_type,
                           LEAD(ts)       OVER (PARTITION BY symbol ORDER BY ts) AS next_ts,
                           LEAD(sub_type) OVER (PARTITION BY symbol ORDER BY ts) AS next_sub_type
                    FROM status_events
                    WHERE feed_source = 'DPLS'
                      AND event_kind  = 'H'
                      AND symbol IS NOT NULL
                      AND ts >= ? AND ts < ?
                ) s
                WHERE halt_sub_type = 'H'
                ORDER BY halt_ts
                """;

        Timestamp from = Timestamp.from(ctx.tradingDate().atStartOfDay().toInstant(ZoneOffset.UTC));
        Timestamp to   = Timestamp.from(ctx.tradingDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        List<ScoredEvent> out = new ArrayList<>();
        try (PreparedStatement st = ctx.conn().prepareStatement(sql)) {
            st.setTimestamp(1, from);
            st.setTimestamp(2, to);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(buildEvent(ctx, rs));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("HaltScorer query failed for date=" + ctx.tradingDate(), e);
        }

        LOG.info("HaltScorer  date={} halts_emitted={}", ctx.tradingDate(), out.size());
        return out.stream();
    }

    private static ScoredEvent buildEvent(final ScoringContext ctx, final ResultSet rs) throws Exception {
        String symbol      = rs.getString("halt_symbol");
        Instant haltStart  = rs.getTimestamp("halt_ts").toInstant();
        long    haltNanos  = rs.getLong("halt_ts_nanos");
        String  reason     = rs.getString("halt_reason");
        Timestamp nextTsTs = rs.getTimestamp("next_ts");
        String  nextSub    = rs.getString("next_sub_type");

        Instant haltEnd = (nextTsTs != null) ? nextTsTs.toInstant() : null;
        Long durationS  = (haltEnd != null) ? (haltEnd.getEpochSecond() - haltStart.getEpochSecond()) : null;

        ObjectMapper json = ctx.json();
        ObjectNode breakdown = json.createObjectNode();
        breakdown.put("halt_reason",      reason);
        if (durationS != null) breakdown.put("halt_duration_s", durationS); else breakdown.putNull("halt_duration_s");
        breakdown.put("halt_start_iso",   haltStart.toString());
        if (haltEnd != null)   breakdown.put("halt_end_iso", haltEnd.toString()); else breakdown.putNull("halt_end_iso");
        if (nextSub != null)   breakdown.put("halt_end_sub_type", nextSub); else breakdown.putNull("halt_end_sub_type");

        ArrayNode sourceRefs = json.createArrayNode();
        ObjectNode ref = json.createObjectNode();
        ref.put("table",    "status_events");
        ref.put("symbol",   symbol);
        ref.put("ts_nanos", haltNanos);
        sourceRefs.add(ref);

        // Score = duration in seconds. Unbounded halts (no resume in the
        // window) score 0 for now — they're visible but ranked low until
        // we figure out the right policy.
        double score = (durationS != null) ? durationS.doubleValue() : 0.0;

        return new ScoredEvent(
                ctx.tradingDate(),
                symbol,
                haltStart,
                haltEnd,
                "halt",
                score,
                breakdown,
                sourceRefs);
    }
}
