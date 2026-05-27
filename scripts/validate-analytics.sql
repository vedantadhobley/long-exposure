-- Validate the pre-launch analytics tranche landed in scored_events breakdowns.
-- Usage (after ScoreWorkflow for the date):
--   docker exec -i long-exposure-dev-postgres psql -U leuser -d longexposure \
--     -v d=2026-05-22 < scripts/validate-analytics.sql
-- Test on a day WITH trailing baseline (NOT 05-08, the earliest) so the
-- baseline-dependent stats (pct_of_baseline_volume / robust_z / percentile_rank)
-- are actually exercised.

\set d '2026-05-22'

\echo === sweep: slippage_bps coverage (not baseline-dependent; works any day) ===
SELECT count(*)                                                           AS sweeps,
       count(*) FILTER (WHERE breakdown ? 'slippage_bps')                 AS with_slippage,
       round(avg((breakdown->>'slippage_bps')::numeric), 2)              AS avg_bps,
       round(max((breakdown->>'slippage_bps')::numeric), 2)              AS max_bps,
       count(*) FILTER (WHERE breakdown->>'slippage_direction'
                              NOT IN ('up','down','flat'))                AS bad_direction
FROM scored_events WHERE trading_date = :'d' AND scorer_id = 'sweep';

\echo === large_trade: pct_of_baseline_volume coverage (needs trailing baseline) ===
SELECT count(*)                                                           AS large_trades,
       count(*) FILTER (WHERE breakdown ? 'pct_of_baseline_volume')       AS with_pct_adv,
       round(min((breakdown->>'pct_of_baseline_volume')::numeric), 2)     AS min_pct,
       round(max((breakdown->>'pct_of_baseline_volume')::numeric), 2)     AS max_pct
FROM scored_events WHERE trading_date = :'d' AND scorer_id = 'large_trade';

\echo === volume_deviation: robust_z + percentile_rank coverage (needs baseline) ===
SELECT count(*)                                                           AS vol_dev_events,
       count(*) FILTER (WHERE breakdown ? 'robust_z')                     AS with_z,
       count(*) FILTER (WHERE breakdown ? 'percentile_rank')              AS with_prank,
       round(min((breakdown->>'percentile_rank')::numeric), 1)            AS min_prank,
       round(max((breakdown->>'percentile_rank')::numeric), 1)            AS max_prank,
       count(*) FILTER (WHERE (breakdown->>'percentile_rank')::numeric
                              NOT BETWEEN 0 AND 100)                       AS bad_prank
FROM scored_events WHERE trading_date = :'d' AND scorer_id = 'volume_deviation';

\echo === sample rows (the new fields, top by score per scorer) ===
SELECT scorer_id, symbol,
       breakdown->>'slippage_bps'           AS slip_bps,
       breakdown->>'slippage_direction'     AS slip_dir,
       breakdown->>'pct_of_baseline_volume' AS pct_adv,
       breakdown->>'robust_z'               AS robust_z,
       breakdown->>'percentile_rank'        AS prank
FROM scored_events
WHERE trading_date = :'d' AND scorer_id IN ('sweep','large_trade','volume_deviation')
ORDER BY scorer_id, score DESC
LIMIT 18;
