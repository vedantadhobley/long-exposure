package com.longexposure.scoring.scorers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.longexposure.scoring.BreakdownFmt;
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
import java.util.function.Consumer;

/**
 * Inter-day volume-deviation detector — the first scorer that reads beyond
 * a single trading day. For each symbol, compares the day's total traded
 * volume against the median of that symbol's volume over the trailing
 * window, and emits when today is a significant surge.
 *
 * <p>Source: the {@code daily_volume_by_symbol} continuous aggregate (one
 * row per symbol per day: {@code total_volume = SUM(size)} over the
 * {@code trades} table, {@code trade_count}). The baseline is the median
 * of prior days' {@code total_volume} within {@link #BASELINE_WINDOW_DAYS}
 * (median, not mean, so a single prior spike doesn't wash out the
 * baseline). Aligns naturally with the week-aligned 2-week retention —
 * the window holds whatever complete days are retained.
 *
 * <p>Emission gates (all must hold), tuned so the events are narratable
 * rather than illiquid-ticker noise:
 * <ul>
 *   <li>{@code >= MIN_BASELINE_DAYS} prior days, so the median is meaningful
 *   <li>{@code baseline_median >= MIN_BASELINE_SHARES} — the load-bearing
 *       one: without a baseline floor the ratio explodes for near-zero
 *       baselines (a 10-share/day name showing 117k reads as 11,706×)
 *   <li>{@code today_volume >= MIN_VOLUME_SHARES} absolute floor
 *   <li>{@code deviation_x >= MIN_DEVIATION} (today / baseline-median)
 * </ul>
 *
 * <p>Score = {@code deviation_x} (ranks the most-anomalous surge first
 * within this scorer; cross-scorer comparison is handled by the selector's
 * per-scorer percentile rank, so the raw unit need not match other
 * scorers).
 *
 * <p>Breakdown carries BOTH today's number and the baseline (the grounding
 * contract for an inter-day claim — the narrator must be able to say
 * "4.7× its 2-week median" with both figures present), plus the
 * pre-computed {@code deviation_x} so the LLM never divides at inference.
 *
 * <p>v1 flags surges only (high volume). Volume *droughts* (today far below
 * baseline) are a separate, less-narratable signal; deferred.
 */
public final class VolumeDeviationScorer implements EventScorer {

    private static final Logger LOG = LoggerFactory.getLogger(VolumeDeviationScorer.class);

    /** Trailing calendar-day window the baseline median is computed over. */
    private static final int BASELINE_WINDOW_DAYS = 14;

    /** Minimum prior days within the window for the median to be meaningful. */
    private static final int MIN_BASELINE_DAYS = 3;

    /** Absolute floor on today's volume — keeps illiquid-ticker noise out. */
    private static final long MIN_VOLUME_SHARES = 100_000L;

    /**
     * Floor on the BASELINE median. Load-bearing: ranking by ratio explodes
     * for near-zero baselines — a name that normally trades 10 shares/day on
     * IEX showing 117k once reads as an 11,706× "surge", which is noise, not a
     * story. Requiring the baseline itself to be a real volume (≥25k IEX
     * shares/day ≈ a genuinely-traded small/mid-cap, since IEX is ~3% of
     * consolidated volume) keeps the divide-by-tiny artifacts out. Validated
     * against 2026-05-22: without it the top 8 were sub-5k-baseline noise;
     * with it the top events are FUTU/TIGR/SPCE/PTON/NTAP-class real surges.
     */
    private static final long MIN_BASELINE_SHARES = 25_000L;

    /** Minimum today/baseline ratio to surface as an event. */
    private static final double MIN_DEVIATION = 3.0;

    @Override
    public String id() { return "volume_deviation"; }

    @Override
    public void score(final ScoringContext ctx, final Consumer<ScoredEvent> emit) {
        // today's volume vs the median of prior days in [D-window, D).
        // percentile_cont(0.5) is the median; computed over the small cagg
        // (one row per symbol per day), so this is cheap.
        String sql = """
                WITH today AS (
                    SELECT symbol, total_volume AS today_vol, trade_count AS today_tc
                    FROM daily_volume_by_symbol
                    WHERE day = ?
                ),
                baseline AS (
                    SELECT symbol,
                           percentile_cont(0.5) WITHIN GROUP (ORDER BY total_volume) AS med_vol,
                           percentile_cont(0.5) WITHIN GROUP (ORDER BY trade_count)  AS med_tc,
                           count(*) AS days
                    FROM daily_volume_by_symbol
                    WHERE day >= ? AND day < ?
                    GROUP BY symbol
                )
                SELECT t.symbol, t.today_vol, t.today_tc,
                       b.med_vol, b.med_tc, b.days
                FROM today t
                JOIN baseline b USING (symbol)
                WHERE b.days >= ?
                  AND b.med_vol >= ?
                  AND t.today_vol >= ?
                  AND t.today_vol >= ? * b.med_vol
                ORDER BY (t.today_vol::float8 / b.med_vol) DESC
                """;

        Instant dayStart = ctx.tradingDate().atStartOfDay().toInstant(ZoneOffset.UTC);
        Timestamp dayBucket    = Timestamp.from(dayStart);
        Timestamp windowStart  = Timestamp.from(
                ctx.tradingDate().minusDays(BASELINE_WINDOW_DAYS).atStartOfDay().toInstant(ZoneOffset.UTC));

        long emitted = 0;
        try (PreparedStatement st = ctx.conn().prepareStatement(sql)) {
            st.setTimestamp(1, dayBucket);     // today's bucket
            st.setTimestamp(2, windowStart);   // baseline window start (inclusive)
            st.setTimestamp(3, dayBucket);     // baseline window end (exclusive = today)
            st.setInt(4, MIN_BASELINE_DAYS);
            st.setLong(5, MIN_BASELINE_SHARES);
            st.setLong(6, MIN_VOLUME_SHARES);
            st.setDouble(7, MIN_DEVIATION);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    emit.accept(buildEvent(ctx, rs, dayStart));
                    emitted++;
                    if (emitted % 50 == 0) ctx.heartbeat().send("volume_deviation:" + emitted);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("VolumeDeviationScorer query failed for date=" + ctx.tradingDate(), e);
        }

        LOG.info("VolumeDeviationScorer  date={} window_days={} min_dev={}x emitted={}",
                ctx.tradingDate(), BASELINE_WINDOW_DAYS, MIN_DEVIATION, emitted);
    }

    private static ScoredEvent buildEvent(final ScoringContext ctx, final ResultSet rs,
                                          final Instant dayStart) throws Exception {
        String symbol   = rs.getString("symbol");
        long   todayVol  = rs.getLong("today_vol");
        long   todayTc   = rs.getLong("today_tc");
        double medVol    = rs.getDouble("med_vol");
        double medTc     = rs.getDouble("med_tc");
        int    days      = rs.getInt("days");

        double deviationX = todayVol / medVol;

        ObjectMapper json = ctx.json();
        ObjectNode breakdown = json.createObjectNode();
        breakdown.put("todays_volume_shares",         BreakdownFmt.formatCount(todayVol));
        breakdown.put("baseline_median_shares",       BreakdownFmt.formatCount(Math.round(medVol)));
        breakdown.put("baseline_window_trading_days",  days);
        breakdown.put("deviation_x",                   BreakdownFmt.round(deviationX, 1));
        breakdown.put("todays_trade_count",            BreakdownFmt.formatCount(todayTc));
        breakdown.put("baseline_median_trade_count",   BreakdownFmt.formatCount(Math.round(medTc)));

        ArrayNode sourceRefs = json.createArrayNode();
        ObjectNode ref = json.createObjectNode();
        ref.put("table",  "daily_volume_by_symbol");
        ref.put("symbol", symbol);
        ref.put("day",    ctx.tradingDate().toString());
        sourceRefs.add(ref);

        com.longexposure.scoring.SymbolFields.apply(breakdown, ctx, symbol);

        return new ScoredEvent(
                ctx.tradingDate(),
                symbol,
                dayStart,         // whole-day event — anchor at the day's UTC start
                null,
                "volume_deviation",
                deviationX,       // score = the deviation ratio
                breakdown,
                sourceRefs);
    }
}
