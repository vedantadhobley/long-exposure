-- Post-ingest spot-check queries. Run via Adminer at
--   http://long-exposure-dev-adminer.luv
-- or psql in the postgres container.
--
-- Expected counts for 2026-05-08 TOPS (compare to the parser's in-memory
-- histogram from the first end-to-end run before DB writes):
--   trades            ~7,750,799
--   quotes          ~285,154,722
--   retail_liquidity  ~1,843,964
--   securities                ~3
--   official_prices           ~6
--   auction_info          ~1,998
--   status_events (S)         ~6   (start/end markers, 2 sessions)
--   status_events (H)     ~12,755  (TradingStatus)
--   status_events (O)     ~12,616  (OperationalHalt)
--   status_events (P)     ~13,536  (ShortSalePriceTest)
--   status_events (E)          0   (DEEP/DEEP+ only)
--   trade_breaks               0   (rare)

-- ─── Per-table row counts ───────────────────────────────────────────────────

SELECT 'trades'           AS table_name, COUNT(*) AS rows FROM trades
UNION ALL SELECT 'trade_breaks',     COUNT(*) FROM trade_breaks
UNION ALL SELECT 'quotes',           COUNT(*) FROM quotes
UNION ALL SELECT 'status_events',    COUNT(*) FROM status_events
UNION ALL SELECT 'auction_info',     COUNT(*) FROM auction_info
UNION ALL SELECT 'official_prices',  COUNT(*) FROM official_prices
UNION ALL SELECT 'securities',       COUNT(*) FROM securities
UNION ALL SELECT 'retail_liquidity', COUNT(*) FROM retail_liquidity
ORDER BY rows DESC;

-- ─── status_events broken down by kind ──────────────────────────────────────

SELECT event_kind, COUNT(*) FROM status_events GROUP BY event_kind ORDER BY COUNT(*) DESC;

-- ─── Time range covered ─────────────────────────────────────────────────────

SELECT
    MIN(ts) AS first_event,
    MAX(ts) AS last_event,
    MAX(ts) - MIN(ts) AS span
FROM trades;

-- ─── Top 20 most-traded symbols by share volume ─────────────────────────────

SELECT symbol, SUM(size)::BIGINT AS volume, COUNT(*) AS trades
FROM trades
GROUP BY symbol
ORDER BY volume DESC
LIMIT 20;

-- ─── Auction prints by type ─────────────────────────────────────────────────

SELECT auction_type, COUNT(*), COUNT(DISTINCT symbol) AS symbols
FROM auction_info
GROUP BY auction_type
ORDER BY COUNT(*) DESC;

-- ─── Official open/close prints (should be 2 per IEX-listed symbol) ─────────

SELECT symbol, price_type, price_raw / 10000.0 AS price, ts
FROM official_prices
ORDER BY symbol, price_type;

-- ─── Halt events ────────────────────────────────────────────────────────────

SELECT ts, symbol, sub_type, reason
FROM status_events
WHERE event_kind = 'H' AND sub_type = 'H'   -- 'H' = trading halted
ORDER BY ts;

-- ─── Sanity check the continuous aggregate refreshes ────────────────────────
-- After running the workload, kick the continuous aggregate refresh manually
-- the first time (subsequent runs happen on the policy schedule):

CALL refresh_continuous_aggregate('daily_volume_by_symbol', NULL, NULL);

SELECT day, COUNT(DISTINCT symbol) AS symbols_with_trades,
       SUM(total_volume)::BIGINT  AS total_market_volume,
       SUM(trade_count)::BIGINT   AS total_market_trades
FROM daily_volume_by_symbol
GROUP BY day
ORDER BY day;
