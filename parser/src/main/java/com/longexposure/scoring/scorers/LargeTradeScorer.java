package com.longexposure.scoring.scorers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.longexposure.scoring.BaselineProvider;
import com.longexposure.scoring.EventScorer;
import com.longexposure.scoring.BreakdownFmt;
import com.longexposure.scoring.ScoredEvent;
import com.longexposure.scoring.ScoringContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Large-trade detector. Reads {@code trades} for DPLS rows on the trading
 * date and emits one {@link ScoredEvent} per trade whose notional
 * exceeds {@link #NOTIONAL_CUTOFF_DOLLARS}.
 *
 * <p>Notional = size × price (price decoded from {@code price_raw} per
 * IEX's 4-implied-decimals convention). Score = {@code log10(notional)}
 * so a $1 M trade scores 6.0, a $100 M block scores 8.0.
 *
 * <p>Breakdown JSON shape:
 * <pre>
 *   {
 *     "size_shares":       1_500_000,
 *     "price_dollars":     247.83,
 *     "notional_dollars":  371_745_000.0,
 *     "trade_id":          12345,
 *     "sale_condition_flags": 0
 *   }
 * </pre>
 *
 * <p>Source refs: a single entry pointing at the trade row in
 * {@code trades}, keyed by {@code (symbol, ts_nanos, trade_id)}.
 *
 * <p>Cutoff is hardcoded at $1 M for v1. A future revision could switch
 * to a percentile (top 0.1% by notional today) once we know the scoring
 * pipeline's output volume budget.
 */
public final class LargeTradeScorer implements EventScorer {

    private static final Logger LOG = LoggerFactory.getLogger(LargeTradeScorer.class);

    /** Notional cutoff in dollars. Trades above this surface as scored events. */
    private static final long NOTIONAL_CUTOFF_DOLLARS = 1_000_000L;

    /** Threshold for the SQL filter, in price_raw units (notional × 10_000). */
    private static final long NOTIONAL_CUTOFF_RAW = NOTIONAL_CUTOFF_DOLLARS * 10_000L;

    /** Trailing window for the per-symbol baseline (median daily volume). Matches VolumeDeviationScorer. */
    private static final int BASELINE_WINDOW_DAYS = 14;

    @Override
    public String id() { return "large_trade"; }

    @Override
    public void score(final ScoringContext ctx, final Consumer<ScoredEvent> emit) {
        // size × price_raw / 10_000 = notional in dollars. We filter in
        // raw units (size * price_raw) > cutoff × 10_000 to avoid float
        // ops in the WHERE clause.
        String sql = """
                SELECT ts, ts_nanos, symbol, size, price_raw, trade_id, sale_condition_flags
                FROM trades
                WHERE feed_source = 'DPLS'
                  AND ts >= ? AND ts < ?
                  AND (CAST(size AS BIGINT) * price_raw) > ?
                ORDER BY ts
                """;

        Timestamp from = Timestamp.from(ctx.tradingDate().atStartOfDay().toInstant(ZoneOffset.UTC));
        Timestamp to   = Timestamp.from(ctx.tradingDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        // Per-symbol trailing baseline (median daily IEX volume), loaded once —
        // lets each block report its size as a % of the symbol's typical day
        // ("a fifth of the day in one print"). Empty map degrades gracefully
        // (the pct field is simply omitted) when no baseline exists yet.
        Map<String, BaselineProvider.TrailingVolume> baselines =
                ctx.baselines().trailingVolumeBaselines(ctx.tradingDate(), BASELINE_WINDOW_DAYS);

        long emitted = 0;
        try (PreparedStatement st = ctx.conn().prepareStatement(sql)) {
            st.setTimestamp(1, from);
            st.setTimestamp(2, to);
            st.setLong(3, NOTIONAL_CUTOFF_RAW);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    emit.accept(buildEvent(ctx, rs, baselines));
                    emitted++;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("LargeTradeScorer query failed for date=" + ctx.tradingDate(), e);
        }

        LOG.info("LargeTradeScorer  date={} cutoff=${} trades_emitted={}",
                ctx.tradingDate(), NOTIONAL_CUTOFF_DOLLARS, emitted);
    }

    private static ScoredEvent buildEvent(final ScoringContext ctx, final ResultSet rs,
                                          final Map<String, BaselineProvider.TrailingVolume> baselines) throws Exception {
        Instant ts        = rs.getTimestamp("ts").toInstant();
        long    tsNanos   = rs.getLong("ts_nanos");
        String  symbol    = rs.getString("symbol");
        int     size      = rs.getInt("size");
        long    priceRaw  = rs.getLong("price_raw");
        long    tradeId   = rs.getLong("trade_id");
        int     flags     = rs.getInt("sale_condition_flags");

        double priceDollars    = priceRaw / 10_000.0;
        double notionalDollars = size * priceDollars;

        ObjectMapper json = ctx.json();
        ObjectNode breakdown = json.createObjectNode();
        breakdown.put("size_shares",          BreakdownFmt.formatCount(size));
        breakdown.put("price_dollars",        priceDollars);
        breakdown.put("notional_dollars",     BreakdownFmt.formatDollars(notionalDollars));

        // % of the symbol's median daily IEX volume — "a fifth of the day in one
        // print". Omitted when there's no baseline for the symbol (NaN guard).
        BaselineProvider.TrailingVolume base = baselines.get(symbol);
        if (base != null) {
            double pct = com.longexposure.analytics.Analytics.pctOfBaseline(size, base.medianVolume());
            if (!Double.isNaN(pct)) breakdown.put("pct_of_baseline_volume", BreakdownFmt.round(pct, 1));
        }
        // trade_id and sale_condition_flags are intentionally NOT in the
        // breakdown — they're wire-format metadata that leaked into prose
        // as "trade ID 173670060632532234" / "flags 0". Still preserved in
        // sourceRefs below for joins back to the trades table.

        // ─── derived fields (DETECT enrichment, 2026-05-22) ──────────────
        // Pre-format the readable units the LLM tends to want — without
        // these it would try to convert notional to millions / shares to
        // thousands inline (a known arithmetic-failure mode).
        breakdown.put("notional_million_dollars", BreakdownFmt.round(notionalDollars / 1_000_000.0, 2));
        breakdown.put("size_thousand_shares",     BreakdownFmt.round(size / 1_000.0, 1));
        breakdown.put("event_session_phase",      BreakdownFmt.sessionPhase(ts));
        breakdown.put("event_phase_label",        BreakdownFmt.sessionPhaseLabel(ts));

        ArrayNode sourceRefs = json.createArrayNode();
        ObjectNode ref = json.createObjectNode();
        ref.put("table",    "trades");
        ref.put("symbol",   symbol);
        ref.put("ts_nanos", tsNanos);
        ref.put("trade_id", tradeId);
        sourceRefs.add(ref);

        com.longexposure.scoring.SymbolFields.apply(breakdown, ctx, symbol);

        // Derived fields that depend on SymbolFields populating prev_close /
        // round_lot. Done AFTER SymbolFields.apply so we can read what it
        // wrote into the breakdown.
        if (breakdown.has("prev_close_dollars") && breakdown.get("prev_close_dollars").asDouble() > 0) {
            double prevClose = breakdown.get("prev_close_dollars").asDouble();
            double pctVsPrevClose = (priceDollars - prevClose) / prevClose * 100.0;
            breakdown.put("price_vs_prev_close_pct", BreakdownFmt.round(pctVsPrevClose, 2));
        }
        if (breakdown.has("round_lot") && breakdown.get("round_lot").asInt() > 0) {
            int roundLot = breakdown.get("round_lot").asInt();
            breakdown.put("implied_round_lots", size / roundLot);
        }

        // Time-of-day weight (Phase 7c): gentle ±15% multiplier weighting
        // open/close events higher than midday/overnight. Applies uniformly
        // across all intraday scorers.
        double score = Math.log10(notionalDollars) * BreakdownFmt.timeOfDayWeight(ts);

        return new ScoredEvent(
                ctx.tradingDate(),
                symbol,
                ts,
                null,             // trades are instantaneous
                "large_trade",
                score,
                breakdown,
                sourceRefs);
    }
}
