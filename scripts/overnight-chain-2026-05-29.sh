#!/usr/bin/env bash
# Autonomous post-catchup chain. Run with nohup; survives terminal-close.
#
# 1. Wait for catchup-2026-05-29.sh to log "==== catchup-a DONE ===="
# 2. Run scripts/post-catchup-2026-05-29.sh (worker restart + audit)
# 3. Run the orphan prune DELETE in a transaction
# 4. Kick the overnight PipelineWorkflow.LLM_CHAIN rerun
#
# Aborts cleanly at any stage failure; surviving stages NOT rolled back.
# Logs to scripts/overnight-chain-2026-05-29.log.

set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/scripts/overnight-chain-2026-05-29.log"
CATCHUP_LOG="$ROOT/scripts/catchup-2026-05-29.log"

log() { echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] $*" | tee -a "$LOG"; }

log "==== overnight chain START ===="
log "catchup log: $CATCHUP_LOG"
log "this log:    $LOG"

# ─── Step 1: wait for catchup completion ────────────────────────────────
log "step 1: polling for catchup DONE marker (sleeping 60 sec between checks)..."
while ! grep -q "==== catchup-a DONE" "$CATCHUP_LOG" 2>/dev/null; do
    if grep -q "^\[.*\] abort:" "$CATCHUP_LOG" 2>/dev/null; then
        log "ABORT: catchup recorded an abort line"
        grep "^\[.*\] abort:" "$CATCHUP_LOG" | tee -a "$LOG"
        exit 1
    fi
    sleep 60
done
log "step 1 complete: catchup DONE marker found"

# ─── Step 2: post-catchup script (worker restart + audit) ───────────────
log "step 2: running post-catchup script..."
bash "$ROOT/scripts/post-catchup-2026-05-29.sh" 2>&1 | tee -a "$LOG"
RC=${PIPESTATUS[0]}
if [ "$RC" -ne 0 ]; then
    log "ABORT: post-catchup script exited $RC"
    exit 2
fi
log "step 2 complete"

# ─── Step 3: orphan prune ───────────────────────────────────────────────
log "step 3: running orphan prune in a transaction..."
docker exec -i long-exposure-dev-postgres psql -U leuser -d longexposure 2>&1 <<'PRUNE_SQL' | tee -a "$LOG"
BEGIN;

WITH ranked AS (
    SELECT n.event_hash,
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
DELETE FROM narratives n USING ranked r
WHERE n.event_hash = r.event_hash AND (NOT r.reachable OR r.rn > 1);

WITH ranked AS (
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
)
DELETE FROM interpretations i USING ranked r
WHERE i.interpretation_hash = r.interpretation_hash AND (NOT r.reachable OR r.rn > 1);

COMMIT;

-- Post-prune counts
SELECT 'narratives' AS tbl, COUNT(*) AS rows FROM narratives;
SELECT 'interpretations' AS tbl, COUNT(*) AS rows FROM interpretations;
PRUNE_SQL
RC=${PIPESTATUS[0]}
if [ "$RC" -ne 0 ]; then
    log "ABORT: prune failed with exit $RC"
    exit 3
fi
log "step 3 complete"

# ─── Step 4: kick overnight PipelineWorkflow.LLM_CHAIN ──────────────────
log "step 4: kicking overnight rerun via PipelineWorkflow.LLM_CHAIN..."
WFID="overnight-rerun-$(date -u +%Y%m%d-%H%M%S)"
docker exec long-exposure-dev-temporal temporal workflow start \
  --task-queue long-exposure-daily-pipeline \
  --type PipelineWorkflow \
  --workflow-id "$WFID" \
  --input '{
    "dates": [[2026,5,12],[2026,5,13],[2026,5,14],[2026,5,15],
              [2026,5,18],[2026,5,19],[2026,5,20],[2026,5,21],[2026,5,22]],
    "pollUntilReady": false,
    "forceReingest": false,
    "runRetentionSweep": false,
    "cascadeRollups": true,
    "mode": "LLM_CHAIN"
  }' 2>&1 | tee -a "$LOG"
RC=${PIPESTATUS[0]}
if [ "$RC" -ne 0 ]; then
    log "ABORT: overnight workflow kick failed with exit $RC"
    exit 4
fi

log "==== overnight chain DONE ===="
log "overnight workflow id: $WFID"
log "  follow via:"
log "    docker exec long-exposure-dev-temporal temporal workflow describe --workflow-id $WFID"
log "  expect:"
log "    9 days × (Narrate ~30 min cache-miss + Interpret ~15 min cache-miss + Synth ~3 min) ≈ 7-8 hr"
log "    + AggregateWeek 05-11 + AggregateWeek 05-18 (~6 min)"
log "    + Quarterly + Yearly (gated no-op, <1 sec total)"
