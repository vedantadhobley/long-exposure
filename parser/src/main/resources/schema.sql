-- Long Exposure — events + narratives schema for TimescaleDB.
--
-- Design notes (full reasoning in docs/decisions.md):
--   * Per-message-type hypertables, not one big polymorphic events table.
--     12 message types with very different field sets; nullable columns
--     across a single wide table waste storage and obscure intent.
--   * Both TIMESTAMPTZ and BIGINT nanos kept on every event row.
--     - TIMESTAMPTZ is the hypertable partition column (microsecond precision)
--     - ts_nanos preserves the spec-level nanosecond ordering for tie-breakers
--       and for sub-microsecond analysis the Postgres timestamp can't represent.
--   * `feed_source` column on every event lets phase-2 DEEP+ data land in the
--     same tables without schema churn (TOPS today; 'DEEP+' / 'DPLS' later).
--   * IEX prices stored as BIGINT (4 implied decimals). Divide by 10000 only
--     at presentation — preserves precision, avoids float-rounding bugs in
--     aggregates.
--
-- This file is idempotent: re-running it on an already-initialized DB is a
-- no-op. The SchemaManager applies it on worker startup.

CREATE EXTENSION IF NOT EXISTS timescaledb;

-- ─── Trades (T) ──────────────────────────────────────────────────────────────
-- Every individual fill on the IEX Order Book. Trade ID is unique within a
-- trading day and is the join key for Trade Break messages.

CREATE TABLE IF NOT EXISTS trades (
    ts                          TIMESTAMPTZ NOT NULL,
    ts_nanos                    BIGINT      NOT NULL,
    feed_source                 TEXT        NOT NULL,
    symbol                      TEXT        NOT NULL,
    size                        INTEGER     NOT NULL,
    price_raw                   BIGINT      NOT NULL,
    trade_id                    BIGINT      NOT NULL,
    sale_condition_flags        SMALLINT    NOT NULL
);

SELECT create_hypertable('trades', 'ts',
    chunk_time_interval => INTERVAL '1 day',
    if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS trades_symbol_ts_idx ON trades (symbol, ts DESC);

-- ─── Trade Breaks (B) ────────────────────────────────────────────────────────
-- Same wire shape as Trade Report; broken_trade_id references trades.trade_id.

CREATE TABLE IF NOT EXISTS trade_breaks (
    ts                          TIMESTAMPTZ NOT NULL,
    ts_nanos                    BIGINT      NOT NULL,
    feed_source                 TEXT        NOT NULL,
    symbol                      TEXT        NOT NULL,
    size                        INTEGER     NOT NULL,
    price_raw                   BIGINT      NOT NULL,
    broken_trade_id             BIGINT      NOT NULL,
    sale_condition_flags        SMALLINT    NOT NULL
);

SELECT create_hypertable('trade_breaks', 'ts',
    chunk_time_interval => INTERVAL '1 day',
    if_not_exists => TRUE);

-- ─── Quote Updates (Q) ───────────────────────────────────────────────────────
-- Dominant volume: ~97% of TOPS messages. Finer chunk interval to keep
-- per-chunk row counts manageable; TimescaleDB compression will be turned on
-- in operations once we've seen a few days of real data.

CREATE TABLE IF NOT EXISTS quotes (
    ts                          TIMESTAMPTZ NOT NULL,
    ts_nanos                    BIGINT      NOT NULL,
    feed_source                 TEXT        NOT NULL,
    symbol                      TEXT        NOT NULL,
    bid_size                    INTEGER     NOT NULL,
    bid_price_raw               BIGINT      NOT NULL,
    ask_size                    INTEGER     NOT NULL,
    ask_price_raw               BIGINT      NOT NULL,
    flags                       SMALLINT    NOT NULL
);

SELECT create_hypertable('quotes', 'ts',
    chunk_time_interval => INTERVAL '1 hour',
    if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS quotes_symbol_ts_idx ON quotes (symbol, ts DESC);

-- ─── Status / lifecycle events (S, H, O, P, E) ───────────────────────────────
-- Rare events — halts, market open/close markers, short-sale restriction
-- changes, security open/close markers (DEEP/DEEP+ only). Polymorphic by
-- event_kind because they're closely related shapes and joining them in
-- queries is common ("show me everything that happened to AAPL").

CREATE TABLE IF NOT EXISTS status_events (
    ts                          TIMESTAMPTZ NOT NULL,
    ts_nanos                    BIGINT      NOT NULL,
    feed_source                 TEXT        NOT NULL,
    event_kind                  CHAR(1)     NOT NULL,  -- 'S' system, 'H' trading status, 'O' op halt, 'P' SSPT, 'E' security event
    symbol                      TEXT,                   -- null only for 'S' SystemEvent
    sub_type                    CHAR(1)     NOT NULL,   -- 'O'/'S'/'R'/'M'/'E'/'C' for S; 'H'/'O'/'P'/'T' for H; ...
    reason                      TEXT,                   -- TradingStatus.reason ('T1', 'MCB3', ...)
    detail                      CHAR(1)                 -- ShortSalePriceTestStatus.detail
);

SELECT create_hypertable('status_events', 'ts',
    chunk_time_interval => INTERVAL '1 day',
    if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS status_events_symbol_ts_idx ON status_events (symbol, ts DESC) WHERE symbol IS NOT NULL;
CREATE INDEX IF NOT EXISTS status_events_kind_ts_idx ON status_events (event_kind, ts DESC);

-- ─── Auction Information (A) ─────────────────────────────────────────────────
-- Broadcast every second between Lock-in Time and the auction match for
-- Opening / Closing / IPO / Halt / Volatility Auctions, per IEX-listed symbol.

CREATE TABLE IF NOT EXISTS auction_info (
    ts                                 TIMESTAMPTZ NOT NULL,
    ts_nanos                           BIGINT      NOT NULL,
    feed_source                        TEXT        NOT NULL,
    symbol                             TEXT        NOT NULL,
    auction_type                       CHAR(1)     NOT NULL,  -- O, C, I, H, V
    paired_shares                      INTEGER     NOT NULL,
    reference_price_raw                BIGINT      NOT NULL,
    indicative_clearing_price_raw      BIGINT      NOT NULL,
    imbalance_shares                   INTEGER     NOT NULL,
    imbalance_side                     CHAR(1)     NOT NULL,  -- B, S, N
    extension_number                   SMALLINT    NOT NULL,
    scheduled_auction_time_seconds     BIGINT      NOT NULL,  -- Event Time (uint32 seconds since POSIX epoch UTC)
    auction_book_clearing_price_raw    BIGINT      NOT NULL,
    collar_reference_price_raw         BIGINT      NOT NULL,
    lower_collar_raw                   BIGINT      NOT NULL,
    upper_collar_raw                   BIGINT      NOT NULL
);

SELECT create_hypertable('auction_info', 'ts',
    chunk_time_interval => INTERVAL '1 day',
    if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS auction_info_symbol_ts_idx ON auction_info (symbol, ts DESC);

-- ─── Official Prices (X) ─────────────────────────────────────────────────────
-- IEX-listed securities only. Two rows per (symbol, day) max: opening + closing.

CREATE TABLE IF NOT EXISTS official_prices (
    ts                          TIMESTAMPTZ NOT NULL,
    ts_nanos                    BIGINT      NOT NULL,
    feed_source                 TEXT        NOT NULL,
    symbol                      TEXT        NOT NULL,
    price_type                  CHAR(1)     NOT NULL,  -- 'Q' opening, 'M' closing
    price_raw                   BIGINT      NOT NULL
);

SELECT create_hypertable('official_prices', 'ts',
    chunk_time_interval => INTERVAL '1 day',
    if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS official_prices_symbol_idx ON official_prices (symbol, ts DESC);

-- ─── Security Directory (D) ──────────────────────────────────────────────────
-- Symbol metadata: round lot, prior-close-adjusted POC price, LULD tier, flags.
-- Pre-market spin + intraday updates. Low volume (~thousands per day).

CREATE TABLE IF NOT EXISTS securities (
    ts                          TIMESTAMPTZ NOT NULL,
    ts_nanos                    BIGINT      NOT NULL,
    feed_source                 TEXT        NOT NULL,
    symbol                      TEXT        NOT NULL,
    flags                       SMALLINT    NOT NULL,
    round_lot_size              INTEGER     NOT NULL,
    adjusted_poc_price_raw      BIGINT      NOT NULL,
    luld_tier                   SMALLINT    NOT NULL
);

SELECT create_hypertable('securities', 'ts',
    chunk_time_interval => INTERVAL '1 day',
    if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS securities_symbol_ts_idx ON securities (symbol, ts DESC);

-- ─── Retail Liquidity Indicator (I) ──────────────────────────────────────────
-- ~1.8M messages per day. Indicator state changes per symbol. Useful as a
-- secondary signal for retail interest narratives.

CREATE TABLE IF NOT EXISTS retail_liquidity (
    ts                          TIMESTAMPTZ NOT NULL,
    ts_nanos                    BIGINT      NOT NULL,
    feed_source                 TEXT        NOT NULL,
    symbol                      TEXT        NOT NULL,
    indicator                   CHAR(1)     NOT NULL   -- ' ' (space), 'A', 'B', 'C'
);

SELECT create_hypertable('retail_liquidity', 'ts',
    chunk_time_interval => INTERVAL '1 day',
    if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS retail_liquidity_symbol_ts_idx ON retail_liquidity (symbol, ts DESC);

-- ─── Continuous aggregates for rolling 30-day baselines ──────────────────────
-- TimescaleDB continuous aggregates refresh incrementally as new events land,
-- so the scorer doesn't recompute baselines from raw trades on every run.

CREATE MATERIALIZED VIEW IF NOT EXISTS daily_volume_by_symbol
WITH (timescaledb.continuous) AS
SELECT
    time_bucket(INTERVAL '1 day', ts) AS day,
    symbol,
    SUM(size)::BIGINT                AS total_volume,
    COUNT(*)::BIGINT                 AS trade_count,
    AVG(price_raw)::BIGINT           AS avg_price_raw,
    MIN(price_raw)::BIGINT           AS min_price_raw,
    MAX(price_raw)::BIGINT           AS max_price_raw
FROM trades
GROUP BY day, symbol
WITH NO DATA;

-- Refresh policy: every hour, refresh the previous 30 days. Tuned later if
-- the trading-day cadence (T+1 ingest) makes a daily refresh sufficient.
SELECT add_continuous_aggregate_policy('daily_volume_by_symbol',
    start_offset => INTERVAL '30 days',
    end_offset   => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour',
    if_not_exists => TRUE);

-- Convenience view for the rolling 30-trading-day average per symbol.
-- Computed at query time over the continuous aggregate; cheap because
-- daily_volume_by_symbol is small (one row per symbol per day).
CREATE OR REPLACE VIEW symbol_baseline_30d AS
SELECT
    symbol,
    day,
    AVG(total_volume) OVER w  AS avg_volume_30d,
    AVG(trade_count)  OVER w  AS avg_trade_count_30d
FROM daily_volume_by_symbol
WINDOW w AS (PARTITION BY symbol ORDER BY day ROWS BETWEEN 30 PRECEDING AND 1 PRECEDING);

-- ─── Narratives (LLM output cache) ───────────────────────────────────────────
-- Keyed by event_hash so re-running a day's pipeline doesn't re-narrate
-- identical events. The score breakdown is JSONB so we can store whatever
-- the scorer wants without schema changes.

CREATE TABLE IF NOT EXISTS narratives (
    event_hash                  BYTEA       PRIMARY KEY,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    trading_date                DATE        NOT NULL,
    event_type                  TEXT        NOT NULL,
    event_ts                    TIMESTAMPTZ NOT NULL,
    symbol                      TEXT,
    score                       DOUBLE PRECISION NOT NULL,
    score_breakdown             JSONB       NOT NULL,
    narrative                   TEXT        NOT NULL,
    model_id                    TEXT        NOT NULL
);

CREATE INDEX IF NOT EXISTS narratives_trading_date_idx   ON narratives (trading_date DESC);
CREATE INDEX IF NOT EXISTS narratives_symbol_idx         ON narratives (symbol);
CREATE INDEX IF NOT EXISTS narratives_event_ts_idx       ON narratives (event_ts DESC);

-- ─── Pipeline runs (Temporal workflow bookkeeping) ───────────────────────────
-- One row per workflow execution. Lets the API surface "this day's data is
-- verified vs unverified" and lets the validator flag a run as failed without
-- mutating individual event rows.

CREATE TABLE IF NOT EXISTS pipeline_runs (
    run_id                      UUID        PRIMARY KEY,
    trading_date                DATE        NOT NULL,
    feed_source                 TEXT        NOT NULL,
    started_at                  TIMESTAMPTZ NOT NULL,
    completed_at                TIMESTAMPTZ,
    status                      TEXT        NOT NULL,  -- 'running' | 'completed' | 'failed' | 'unverified'
    parser_message_count        BIGINT,
    validator_status            TEXT,
    notes                       JSONB
);

CREATE INDEX IF NOT EXISTS pipeline_runs_date_idx ON pipeline_runs (trading_date DESC);
