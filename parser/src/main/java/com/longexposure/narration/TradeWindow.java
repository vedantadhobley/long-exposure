package com.longexposure.narration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;

/**
 * Aggregate of trades on a symbol within a time window. The INTERPRET
 * stage queries this for the ±60-sec pre / post event windows, then
 * passes the summary into the LLM prompt so the model can identify
 * sequential / causal context the breakdown alone can't reveal
 * ("the block trade was followed by another similar block 47 sec
 * later", "the layering preceded a post-event volume doubling").
 *
 * <p>Aggregation happens in SQL so we don't pull row-by-row data into
 * Java. The trade window for a single symbol over 60 sec is small
 * even on the busiest names — typically under 500 rows.
 */
public record TradeWindow(
        long tradeCount,
        long totalShares,
        long totalNotionalRaw,   // SUM(size × price_raw), still in 1/10000 dollars
        Long minPriceRaw,        // nullable when count = 0
        Long maxPriceRaw,
        Timestamp firstTs,
        Timestamp lastTs) {

    /** Empty window — no trades on the symbol during the queried interval. */
    public static TradeWindow empty() {
        return new TradeWindow(0, 0, 0, null, null, null, null);
    }

    /**
     * Aggregate trades on a symbol in the half-open interval {@code [from, to)}.
     * Uses {@code feed_source = 'DPLS'} to stay on the IEX DEEP+ feed and
     * avoid TOPS validation-oracle rows leaking into product output.
     */
    public static TradeWindow query(final Connection conn,
                                    final String symbol,
                                    final Timestamp from,
                                    final Timestamp to) throws Exception {
        String sql = """
                SELECT
                  COUNT(*)                                AS trade_count,
                  COALESCE(SUM(size), 0)                  AS total_shares,
                  COALESCE(SUM(size::bigint * price_raw), 0) AS total_notional_raw,
                  MIN(price_raw)                          AS min_price_raw,
                  MAX(price_raw)                          AS max_price_raw,
                  MIN(ts)                                 AS first_ts,
                  MAX(ts)                                 AS last_ts
                FROM trades
                WHERE symbol = ?
                  AND ts >= ?
                  AND ts <  ?
                  AND feed_source = 'DPLS'
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, symbol);
            st.setTimestamp(2, from);
            st.setTimestamp(3, to);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) return empty();
                return new TradeWindow(
                        rs.getLong("trade_count"),
                        rs.getLong("total_shares"),
                        rs.getLong("total_notional_raw"),
                        (Long) rs.getObject("min_price_raw"),
                        (Long) rs.getObject("max_price_raw"),
                        rs.getTimestamp("first_ts"),
                        rs.getTimestamp("last_ts"));
            }
        }
    }

    /**
     * Human-readable single-line summary suitable for dropping into the
     * LLM prompt. Empty windows surface explicitly as "no trades" so the
     * model can narrate isolation as a valid observation rather than
     * fabricating context.
     */
    public String toPromptLine() {
        if (tradeCount == 0) return "no trades";
        double totalNotionalDollars = totalNotionalRaw / 10_000.0;
        double vwap = (totalShares > 0) ? totalNotionalDollars / totalShares : 0.0;
        double minPrice = (minPriceRaw != null) ? minPriceRaw / 10_000.0 : 0.0;
        double maxPrice = (maxPriceRaw != null) ? maxPriceRaw / 10_000.0 : 0.0;
        return String.format("%d trades, %,d shares, $%,.2f notional, VWAP $%.4f, range $%.4f–$%.4f",
                tradeCount, totalShares, totalNotionalDollars, vwap, minPrice, maxPrice);
    }

    /**
     * Structured-JSON form for persistence in {@code interpretations.pre_window_summary}
     * / {@code post_window_summary}. Lets downstream consumers (frontend,
     * SYNTHESIZE) re-render the summary in their own format.
     */
    public ObjectNode toJson(final ObjectMapper json) {
        ObjectNode n = json.createObjectNode();
        n.put("trade_count", tradeCount);
        n.put("total_shares", totalShares);
        if (tradeCount == 0) return n;
        double totalNotionalDollars = totalNotionalRaw / 10_000.0;
        n.put("total_notional_dollars", round2(totalNotionalDollars));
        // Million-dollar form: large-trade post-windows commonly carry blocks
        // worth millions. Without this, the LLM converts dollars to millions
        // inline ("$6.8M") and the verifier flags the converted value.
        n.put("total_notional_million_dollars", round2(totalNotionalDollars / 1_000_000.0));
        n.put("vwap", (totalShares > 0) ? round4(totalNotionalDollars / totalShares) : 0.0);
        if (minPriceRaw != null) n.put("min_price_dollars", round4(minPriceRaw / 10_000.0));
        if (maxPriceRaw != null) n.put("max_price_dollars", round4(maxPriceRaw / 10_000.0));
        // Pre-compute price range so the LLM never has to subtract max - min at
        // inference time. Without this, the model emits "$0.97 price range"
        // (computed from 20.18 - 19.21) and the verifier flags 0.97 as not in
        // the haystack — exactly the failure mode L1 enrichment is supposed
        // to eliminate.
        if (minPriceRaw != null && maxPriceRaw != null) {
            n.put("price_range_dollars", round4((maxPriceRaw - minPriceRaw) / 10_000.0));
        }
        if (firstTs != null) n.put("first_ts_iso", firstTs.toInstant().toString());
        if (lastTs != null)  n.put("last_ts_iso",  lastTs.toInstant().toString());
        return n;
    }

    private static double round2(final double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round4(final double v) { return Math.round(v * 10_000.0) / 10_000.0; }
}
