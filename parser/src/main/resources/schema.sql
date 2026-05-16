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
--   * `feed_source` column on every event lets phase-2 DPLS data land in the
--     same tables without schema churn (TOPS today; 'DPLS' later).
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
-- changes, security open/close markers (DEEP/DPLS only). Polymorphic by
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

-- ─── DPLS order-level events ────────────────────────────────────────────────
-- DPLS publishes one record per displayed-order lifecycle event:
--   * a  AddOrder       new displayed order on the book (issued Order ID)
--   * M  OrderModify    size/price change (Modify Flags bit 0 = maintain priority)
--   * R  OrderDelete    order removed
--   * L  OrderExecuted  displayed order executes (carries Trade ID + sale flags)
--   * C  ClearBook      drop the entire book for the symbol (halt / session boundary)
--   * T  Trade          non-displayed × non-displayed execution → trades table (shared with TOPS)
--   * B  TradeBreak     cancelled trade → trade_breaks table (shared with TOPS)
-- The first five get their own tables here. Trade / TradeBreak reuse the
-- existing TOPS hypertables via feed_source = 'DPLS'.
--
-- Volume: 2026-05-08 day produced ~364 M DPLS messages, of which Add and
-- Delete dominate (~140 M each empirically), Executed ~7-8 M (matches trade
-- count), Modify rare, ClearBook negligible. Hour-grained chunks for the
-- two high-volume tables; day-grained for the rest.

CREATE TABLE IF NOT EXISTS orders_add (
    ts                          TIMESTAMPTZ NOT NULL,
    ts_nanos                    BIGINT      NOT NULL,
    feed_source                 TEXT        NOT NULL,
    symbol                      TEXT        NOT NULL,
    side                        CHAR(1)     NOT NULL,   -- '8' buy, '5' sell
    order_id                    BIGINT      NOT NULL,
    size                        INTEGER     NOT NULL,
    price_raw                   BIGINT      NOT NULL
);

SELECT create_hypertable('orders_add', 'ts',
    chunk_time_interval => INTERVAL '1 hour',
    if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS orders_add_symbol_ts_idx ON orders_add (symbol, ts DESC);
CREATE INDEX IF NOT EXISTS orders_add_order_id_idx  ON orders_add (order_id);

CREATE TABLE IF NOT EXISTS orders_modify (
    ts                          TIMESTAMPTZ NOT NULL,
    ts_nanos                    BIGINT      NOT NULL,
    feed_source                 TEXT        NOT NULL,
    symbol                      TEXT        NOT NULL,
    order_id                    BIGINT      NOT NULL,
    size                        INTEGER     NOT NULL,
    price_raw                   BIGINT      NOT NULL,
    modify_flags                SMALLINT    NOT NULL    -- bit 0: 1 = maintain priority, 0 = reset
);

SELECT create_hypertable('orders_modify', 'ts',
    chunk_time_interval => INTERVAL '1 day',
    if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS orders_modify_symbol_ts_idx ON orders_modify (symbol, ts DESC);
CREATE INDEX IF NOT EXISTS orders_modify_order_id_idx  ON orders_modify (order_id);

CREATE TABLE IF NOT EXISTS orders_delete (
    ts                          TIMESTAMPTZ NOT NULL,
    ts_nanos                    BIGINT      NOT NULL,
    feed_source                 TEXT        NOT NULL,
    symbol                      TEXT        NOT NULL,
    order_id                    BIGINT      NOT NULL
);

SELECT create_hypertable('orders_delete', 'ts',
    chunk_time_interval => INTERVAL '1 hour',
    if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS orders_delete_symbol_ts_idx ON orders_delete (symbol, ts DESC);
CREATE INDEX IF NOT EXISTS orders_delete_order_id_idx  ON orders_delete (order_id);

CREATE TABLE IF NOT EXISTS orders_executed (
    ts                          TIMESTAMPTZ NOT NULL,
    ts_nanos                    BIGINT      NOT NULL,
    feed_source                 TEXT        NOT NULL,
    symbol                      TEXT        NOT NULL,
    order_id                    BIGINT      NOT NULL,
    size                        INTEGER     NOT NULL,
    price_raw                   BIGINT      NOT NULL,
    trade_id                    BIGINT      NOT NULL,
    sale_condition_flags        SMALLINT    NOT NULL
);

SELECT create_hypertable('orders_executed', 'ts',
    chunk_time_interval => INTERVAL '1 day',
    if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS orders_executed_symbol_ts_idx ON orders_executed (symbol, ts DESC);
CREATE INDEX IF NOT EXISTS orders_executed_order_id_idx  ON orders_executed (order_id);
CREATE INDEX IF NOT EXISTS orders_executed_trade_id_idx  ON orders_executed (trade_id);

CREATE TABLE IF NOT EXISTS clear_books (
    ts                          TIMESTAMPTZ NOT NULL,
    ts_nanos                    BIGINT      NOT NULL,
    feed_source                 TEXT        NOT NULL,
    symbol                      TEXT        NOT NULL
);

SELECT create_hypertable('clear_books', 'ts',
    chunk_time_interval => INTERVAL '1 day',
    if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS clear_books_symbol_ts_idx ON clear_books (symbol, ts DESC);

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
-- verified vs unverified" and lets downstream activities (scoring, narration)
-- decide whether to operate on a date.
--
-- Status values written by the daily pipeline workflow:
--   'running'                  workflow has started, no terminal state yet
--   'completed'                parse + validate succeeded; data is verified
--   'unverified'               parse succeeded but validation match rates are
--                              below threshold; data is in DB but downstream
--                              activities should treat as suspect
--   'parse_failed'             ParseAndWriteDpls retries exhausted
--   'validation_failed_data_ok' ValidateTriangle retries exhausted but parse
--                              succeeded; data is in DB
--   'skipped_weekend'          short-circuit exit before any activity
--   'skipped_no_data'          listing returned empty after polling budget
--                              (likely holiday, possibly IEX outage)
--   'skipped_already_ingested' date already in DB and force_reingest=false

CREATE TABLE IF NOT EXISTS pipeline_runs (
    run_id                      UUID        PRIMARY KEY,
    trading_date                DATE        NOT NULL,
    feed_source                 TEXT        NOT NULL,
    started_at                  TIMESTAMPTZ NOT NULL,
    completed_at                TIMESTAMPTZ,
    status                      TEXT        NOT NULL,
    parser_message_count        BIGINT,
    validator_status            TEXT,
    notes                       JSONB
);

CREATE INDEX IF NOT EXISTS pipeline_runs_date_idx ON pipeline_runs (trading_date DESC);

-- ─── Validation runs (one row per trading date) ──────────────────────────────
-- Cross-feed triangle validation results. Written by ValidateTriangleActivity
-- in the daily pipeline. PRIMARY KEY on trading_date — re-running validation
-- for a date (e.g. after force_reingest) overwrites the prior row.
--
-- The load-bearing column is dpls_deep_match_pct: 100% on every trading day
-- we've validated. Any drift below threshold flags the date as unverified
-- and downstream narration skips it.

CREATE TABLE IF NOT EXISTS validation_runs (
    trading_date                DATE             PRIMARY KEY,
    run_at                      TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    dpls_tops_match_pct         DOUBLE PRECISION,        -- DPLS-derived BBO vs TOPS QU
    deep_tops_match_pct         DOUBLE PRECISION,        -- DEEP-derived BBO vs TOPS QU
    dpls_deep_match_pct         DOUBLE PRECISION,        -- DPLS↔DEEP price-level (the 100% leg)
    trade_volume_match          BOOLEAN,                  -- per-symbol DPLS vs TOPS, 0 share delta
    elapsed_seconds             INTEGER,
    status                      TEXT             NOT NULL,  -- 'passed' | 'below_threshold' | 'failed'
    notes                       JSONB                    -- mismatch counts, sample mismatches, etc.
);

CREATE INDEX IF NOT EXISTS validation_runs_run_at_idx ON validation_runs (run_at DESC);

-- ─── Scored events (per-event scoring output) ────────────────────────────────
-- Written by ScoreEventsActivity after the validate triangle passes. Each row
-- represents one "thing worth narrating" — could be a single source row
-- (halt, large trade) or a synthesized pattern across many rows (sweep,
-- post-cancel cluster). One row per (scorer × detection).
--
-- Not a hypertable — typical day produces hundreds-to-low-thousands of scored
-- events across all scorers, well within standard B-tree range.
--
-- See docs/scoring-and-narration.md "Scoring architecture" for the full
-- interface model. The breakdown JSONB is the grounding contract with the
-- LLM narrator: every claim in narration must trace to a field here.

CREATE TABLE IF NOT EXISTS scored_events (
    event_id           BIGSERIAL        PRIMARY KEY,
    trading_date       DATE             NOT NULL,
    symbol             TEXT             NOT NULL,
    ts                 TIMESTAMPTZ      NOT NULL,
    ts_end             TIMESTAMPTZ,                       -- null for instantaneous events
    scorer_id          TEXT             NOT NULL,          -- 'halt' | 'large_trade' | 'sweep' | ...
    score              DOUBLE PRECISION NOT NULL,
    breakdown          JSONB            NOT NULL,          -- transparency: facts the narrator can use
    source_refs        JSONB            NOT NULL,          -- array of {"table":..., "ts_nanos":...}
    scored_at          TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    pipeline_run       UUID                                -- nullable; ties to pipeline_runs.run_id
);

CREATE INDEX IF NOT EXISTS scored_events_date_score_idx ON scored_events (trading_date, score DESC);
CREATE INDEX IF NOT EXISTS scored_events_symbol_idx     ON scored_events (symbol, ts DESC);
CREATE INDEX IF NOT EXISTS scored_events_scorer_idx     ON scored_events (scorer_id, trading_date);

-- ─── Selected events (narration-eligible subset of scored_events) ────────────
-- Written by SelectTopEventsActivity after ScoreEventsActivity. Per-scorer
-- top-N pull from scored_events, with narration_rank=1 being the
-- highest-scoring event of that scorer kind on that trading date.
--
-- The narration layer reads from this table, never from scored_events
-- directly. Keeps the narration input set small (~30-40 events per day)
-- regardless of how many raw events were scored.
--
-- Re-written on every pipeline run: DELETE WHERE trading_date=? then
-- INSERT. Idempotent.

CREATE TABLE IF NOT EXISTS selected_events (
    selected_id         BIGSERIAL    PRIMARY KEY,
    event_id            BIGINT       NOT NULL,   -- ref to scored_events.event_id (no FK to make DELETE cheap)
    trading_date        DATE         NOT NULL,
    symbol              TEXT         NOT NULL,
    ts                  TIMESTAMPTZ  NOT NULL,
    ts_end              TIMESTAMPTZ,
    scorer_id           TEXT         NOT NULL,
    score               DOUBLE PRECISION NOT NULL,
    breakdown           JSONB        NOT NULL,
    source_refs         JSONB        NOT NULL,
    narration_rank      INTEGER      NOT NULL,    -- 1 = top-scoring within its scorer for the day
    selected_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS selected_events_date_scorer_rank_idx
    ON selected_events (trading_date, scorer_id, narration_rank);
CREATE INDEX IF NOT EXISTS selected_events_date_score_idx
    ON selected_events (trading_date, score DESC);
