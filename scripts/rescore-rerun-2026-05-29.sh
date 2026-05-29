#!/usr/bin/env bash
# Autonomous rescore-then-rerun chain. Run with nohup; survives terminal-close.
#
# Triggered after the 2026-05-29 morning overnight rerun showed all halt
# narrations failing verifier because the breakdowns predate commit
# 024a025 (halt_phase_span_label data-layer addition) but the worker now
# has the Phase 7 prompt that leads with that field.
#
# Steps:
#   1. Terminate the in-flight overnight-rerun-20260529-083026
#   2. Re-score 9 days (12-22) sequentially via ScoreWorkflow
#      → repopulates breakdowns with halt_phase_span_label
#      → produces fresh selected_events with new selected_ids
#   3. Kick PipelineWorkflow.LLM_CHAIN on all 9 days with cascadeRollups
#
# Aborts cleanly at any stage failure; logs everything.

set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/scripts/rescore-rerun-2026-05-29.log"

# 9 days to rescore + rerun
DATES=(2026-05-12 2026-05-13 2026-05-14 2026-05-15 2026-05-18 2026-05-19 2026-05-20 2026-05-21 2026-05-22)

# Workflow ID of the overnight rerun to terminate
OLD_WFID="overnight-rerun-20260529-083026"

log() { echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] $*" | tee -a "$LOG"; }

date_to_input() {
    local d=$1
    local y=${d%%-*}
    local m=${d:5:2}
    local dd=${d:8:2}
    echo "[$y,$((10#$m)),$((10#$dd))]"
}

run_workflow() {  # type, wfid, input
    local wftype=$1 wfid=$2 input=$3
    log "START $wftype  id=$wfid  input=$input"
    docker exec long-exposure-dev-temporal temporal workflow start \
      --task-queue long-exposure-daily-pipeline \
      --type "$wftype" --workflow-id "$wfid" --input "$input" >>"$LOG" 2>&1
    # Poll until terminal status
    while true; do
        local status
        status=$(docker exec long-exposure-dev-temporal temporal workflow describe \
                  --workflow-id "$wfid" --output json 2>/dev/null \
                  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['workflowExecutionInfo']['status'])" 2>/dev/null \
                  || echo "POLL_ERR")
        case "$status" in
            WORKFLOW_EXECUTION_STATUS_COMPLETED) log "DONE  $wftype  id=$wfid"; return 0 ;;
            WORKFLOW_EXECUTION_STATUS_FAILED|WORKFLOW_EXECUTION_STATUS_TERMINATED|WORKFLOW_EXECUTION_STATUS_TIMED_OUT|WORKFLOW_EXECUTION_STATUS_CANCELED)
                log "BAD   $wftype  id=$wfid  status=$status"; return 1 ;;
        esac
        sleep 30
    done
}

T0=$(date +%s)
log "==== rescore-rerun START ===="
log "dates: ${DATES[*]}"

# ─── Step 1: Terminate the in-flight overnight rerun ──────────────────
log "step 1: terminating $OLD_WFID..."
docker exec long-exposure-dev-temporal temporal workflow terminate \
    --workflow-id "$OLD_WFID" \
    --reason "rescore needed — halt narratives missing halt_phase_span_label" \
    >>"$LOG" 2>&1 || log "  (terminate exit non-zero — may already be done)"

# Wait for it to actually terminate
log "step 1b: waiting for in-flight children to drain..."
for i in $(seq 1 60); do
    RUNNING=$(docker exec long-exposure-dev-temporal temporal workflow list \
              --query "ExecutionStatus='Running'" 2>/dev/null \
              | grep -cE "^  Running" || true)
    if [ "${RUNNING:-0}" -eq 0 ]; then
        log "  no workflows in flight (poll iter $i)"
        break
    fi
    sleep 10
done

# ─── Step 2: Re-score each day sequentially ───────────────────────────
log "step 2: re-scoring 9 days..."
for D in "${DATES[@]}"; do
    INPUT=$(date_to_input "$D")
    TAG=$(echo "$D" | tr -d -)
    log "--- DAY $D : rescore ---"
    run_workflow ScoreWorkflow "rescore-${TAG}-$(date -u +%H%M%S)" "$INPUT" \
        || { log "ABORT: ScoreWorkflow $D failed"; exit 2; }
done

# ─── Step 3: Quick sanity check — halt_phase_span_label now populated ─
log "step 3: sanity check..."
docker exec long-exposure-dev-postgres psql -U leuser -d longexposure -tA -c "
SELECT trading_date::text, COUNT(*) AS halt_events,
       SUM((breakdown ? 'halt_phase_span_label')::int) AS with_span_label
FROM selected_events
WHERE scorer_id='halt' AND trading_date BETWEEN '2026-05-12' AND '2026-05-22'
GROUP BY trading_date ORDER BY trading_date;
" >> "$LOG" 2>&1

# ─── Step 4: Kick the LLM_CHAIN overnight rerun ───────────────────────
log "step 4: kicking PipelineWorkflow.LLM_CHAIN..."
WFID="overnight-rerun-v2-$(date -u +%Y%m%d-%H%M%S)"
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
  }' >> "$LOG" 2>&1
RC=$?
if [ "$RC" -ne 0 ]; then
    log "ABORT: overnight rerun kick failed (exit $RC)"
    exit 3
fi

T1=$(date +%s)
log "==== rescore-rerun DONE  elapsed=$((T1-T0))s ===="
log ""
log "Overnight rerun workflow: $WFID"
log "  Follow:    docker exec long-exposure-dev-temporal temporal workflow describe --workflow-id $WFID"
log "  Expected:  ~7.5 hr LLM chain + ~6 min cascade"
