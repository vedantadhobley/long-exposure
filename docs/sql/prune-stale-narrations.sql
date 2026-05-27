-- Prune stale / orphaned narration rows.
--
-- WHY: narratives + interpretations are keyed by a content hash (event_hash /
-- interpretation_hash). Re-runs (prompt iterations, re-scores, the dollar-
-- formatting cache-bust, verifier retries that change the breakdown) insert a
-- NEW row per changed hash and leave the old one behind. Normal daily operation
-- creates no orphans (each event is narrated once); they only accumulate from
-- re-runs. The public API already dedupes by content-key (it serves the latest
-- verifier_passed row per (trading_date, symbol, event_type, event_ts) that is
-- still reachable from selected_events), so orphans never reach the UI — this
-- prune is housekeeping to keep the tables lean and the dataset auditable.
--
-- KEEP RULE (mirrors the API's selection):
--   A row is KEPT iff:
--     (1) its content-key matches a CURRENT selected_events row (reachable), AND
--     (2) it is the winner for that content-key — ranked by
--         (verifier_passed DESC, created_at DESC), so the latest *passing* row
--         wins, or the latest row overall if none passed (never lose an event's
--         only narration).
--   Everything else is deleted: unreachable content-keys (events no longer
--   selected after a re-score) and superseded duplicates (older hashes for a
--   still-selected event).
--
-- Content-key join: narratives/interpretations (trading_date, symbol,
-- event_type, event_ts) == selected_events (trading_date, symbol, scorer_id,
-- ts). selected_id is NOT used — it goes stale on re-score (BIGSERIAL is
-- reassigned), which is exactly why the API switched to the content-key.
--
-- USAGE: run the DRY-RUN block first and eyeball the counts. Only then run the
-- DELETE block (wrap in BEGIN/COMMIT so you can ROLLBACK if the counts surprise
-- you). Idempotent — running it twice deletes nothing the second time.

-- ─────────────────────────────────────────────────────────────────────────────
-- DRY RUN — counts only, no mutation. Review before deleting.
-- ─────────────────────────────────────────────────────────────────────────────

WITH ranked AS (
    SELECT n.event_hash,
           n.verifier_passed,
           row_number() OVER (
               PARTITION BY n.trading_date, n.symbol, n.event_type, n.event_ts
               ORDER BY n.verifier_passed DESC, n.created_at DESC
           ) AS rn,
           EXISTS (
               SELECT 1 FROM selected_events se
               WHERE se.trading_date = n.trading_date
                 AND se.symbol       = n.symbol
                 AND se.scorer_id    = n.event_type
                 AND se.ts           = n.event_ts
           ) AS reachable
    FROM narratives n
)
SELECT 'narratives' AS tbl,
       count(*)                                         AS total,
       count(*) FILTER (WHERE reachable AND rn = 1)     AS keep,
       count(*) FILTER (WHERE NOT reachable)            AS del_unreachable,
       count(*) FILTER (WHERE reachable AND rn > 1)     AS del_superseded
FROM ranked
UNION ALL
SELECT 'interpretations',
       count(*),
       count(*) FILTER (WHERE reachable AND rn = 1),
       count(*) FILTER (WHERE NOT reachable),
       count(*) FILTER (WHERE reachable AND rn > 1)
FROM (
    SELECT i.interpretation_hash,
           row_number() OVER (
               PARTITION BY i.trading_date, i.symbol, i.event_type, i.event_ts
               ORDER BY i.verifier_passed DESC, i.created_at DESC
           ) AS rn,
           EXISTS (
               SELECT 1 FROM selected_events se
               WHERE se.trading_date = i.trading_date
                 AND se.symbol       = i.symbol
                 AND se.scorer_id    = i.event_type
                 AND se.ts           = i.event_ts
           ) AS reachable
    FROM interpretations i
) ri;

-- ─────────────────────────────────────────────────────────────────────────────
-- DELETE — run inside a transaction so you can ROLLBACK if needed.
-- Uncomment to execute.
-- ─────────────────────────────────────────────────────────────────────────────

-- BEGIN;
--
-- WITH ranked AS (
--     SELECT n.event_hash,
--            row_number() OVER (
--                PARTITION BY n.trading_date, n.symbol, n.event_type, n.event_ts
--                ORDER BY n.verifier_passed DESC, n.created_at DESC
--            ) AS rn,
--            EXISTS (
--                SELECT 1 FROM selected_events se
--                WHERE se.trading_date = n.trading_date
--                  AND se.symbol       = n.symbol
--                  AND se.scorer_id    = n.event_type
--                  AND se.ts           = n.event_ts
--            ) AS reachable
--     FROM narratives n
-- )
-- DELETE FROM narratives n USING ranked r
-- WHERE n.event_hash = r.event_hash AND (NOT r.reachable OR r.rn > 1);
--
-- WITH ranked AS (
--     SELECT i.interpretation_hash,
--            row_number() OVER (
--                PARTITION BY i.trading_date, i.symbol, i.event_type, i.event_ts
--                ORDER BY i.verifier_passed DESC, i.created_at DESC
--            ) AS rn,
--            EXISTS (
--                SELECT 1 FROM selected_events se
--                WHERE se.trading_date = i.trading_date
--                  AND se.symbol       = i.symbol
--                  AND se.scorer_id    = i.event_type
--                  AND se.ts           = i.event_ts
--            ) AS reachable
--     FROM interpretations i
-- )
-- DELETE FROM interpretations i USING ranked r
-- WHERE i.interpretation_hash = r.interpretation_hash AND (NOT r.reachable OR r.rn > 1);
--
-- COMMIT;
