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

    @Override
    public String id() { return "large_trade"; }

    @Override
    public Stream<ScoredEvent> score(final ScoringContext ctx) {
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

        List<ScoredEvent> out = new ArrayList<>();
        try (PreparedStatement st = ctx.conn().prepareStatement(sql)) {
            st.setTimestamp(1, from);
            st.setTimestamp(2, to);
            st.setLong(3, NOTIONAL_CUTOFF_RAW);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(buildEvent(ctx, rs));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("LargeTradeScorer query failed for date=" + ctx.tradingDate(), e);
        }

        LOG.info("LargeTradeScorer  date={} cutoff=${} trades_emitted={}",
                ctx.tradingDate(), NOTIONAL_CUTOFF_DOLLARS, out.size());
        return out.stream();
    }

    private static ScoredEvent buildEvent(final ScoringContext ctx, final ResultSet rs) throws Exception {
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
        breakdown.put("size_shares",          size);
        breakdown.put("price_dollars",        priceDollars);
        breakdown.put("notional_dollars",     notionalDollars);
        breakdown.put("trade_id",             tradeId);
        breakdown.put("sale_condition_flags", flags);

        ArrayNode sourceRefs = json.createArrayNode();
        ObjectNode ref = json.createObjectNode();
        ref.put("table",    "trades");
        ref.put("symbol",   symbol);
        ref.put("ts_nanos", tsNanos);
        ref.put("trade_id", tradeId);
        sourceRefs.add(ref);

        double score = Math.log10(notionalDollars);

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
