#!/usr/bin/env bash
# Plan (a) catch-up after the 2026-05-28 overnight stall + Phase 9-A worker restart.
#
# Per-day chain: NarrateWorkflow → InterpretWorkflow → SynthesizeDayWorkflow.
# Then AggregateWeekWorkflow for each touched week.
# Strictly sequential per the one-LLM-workflow-at-a-time rule.
#
# Logs each workflow start + completion to scripts/catchup-2026-05-29.log.
# Each workflow runs in Temporal so terminal-close / driver-death cannot
# kill the chain — the workflow is durable; this script just issues
# the next `temporal workflow start` and polls describe for completion.

set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/scripts/catchup-2026-05-29.log"
TQ=long-exposure-daily-pipeline

# Done days (12-22): need v11 INTERPRET re-run + fresh SYNTHESIZE.
# 05-13 had its InterpretWorkflow run by the smoke test 03:03 UTC so we
# skip its interpret here — saves 15 min.
DAYS=(2026-05-12 2026-05-13 2026-05-14 2026-05-15 2026-05-18 2026-05-19 2026-05-20 2026-05-21 2026-05-22)
SKIP_INTERPRET_FOR=(2026-05-13)

# Weekly rollups touched by these dates.
WEEKS=(2026-05-11 2026-05-18)

log() { echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] $*" | tee -a "$LOG"; }

date_to_input() {  # 2026-05-12 → [2026,5,12]
  local d=$1
  local y=${d%%-*}; local m=${d:5:2}; local dd=${d:8:2}
  echo "[$y,$((10#$m)),$((10#$dd))]"
}

run_workflow() {  # wftype, wfid, input
  local wftype=$1 wfid=$2 input=$3
  log "START $wftype  id=$wfid  input=$input"
  docker exec long-exposure-dev-temporal temporal workflow start \
    --task-queue "$TQ" --type "$wftype" --workflow-id "$wfid" --input "$input" >>"$LOG" 2>&1
  # Poll until terminal status.
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

skip_interpret() {  # date
  local d=$1
  for s in "${SKIP_INTERPRET_FOR[@]}"; do [[ "$s" == "$d" ]] && return 0; done
  return 1
}

T0=$(date +%s)
log "==== catchup-a START ===="
log "days=${DAYS[*]}"
log "weeks=${WEEKS[*]}"
log "skip_interpret_for=${SKIP_INTERPRET_FOR[*]}"

# Per-day chain. Run NarrateWorkflow then InterpretWorkflow then SynthesizeDayWorkflow.
# Cache-hit for narrate where extract/render PROMPT_VERSION unchanged.
# Cache-miss for interpret because PROMPT_VERSION v9 → v11 (Phase 9-A).
# SynthesizeDay always re-runs (no cache).
for D in "${DAYS[@]}"; do
  INPUT=$(date_to_input "$D")
  TAG=$(echo "$D" | tr -d -)
  log "--- DAY $D ---"

  run_workflow NarrateWorkflow "catchup-narrate-$TAG-$(date -u +%H%M%S)" "$INPUT" \
    || { log "abort: NarrateWorkflow $D failed"; exit 2; }

  if skip_interpret "$D"; then
    log "skip InterpretWorkflow $D (smoke already ran)"
  else
    run_workflow InterpretWorkflow "catchup-interpret-$TAG-$(date -u +%H%M%S)" "$INPUT" \
      || { log "abort: InterpretWorkflow $D failed"; exit 3; }
  fi

  run_workflow SynthesizeDayWorkflow "catchup-synth-$TAG-$(date -u +%H%M%S)" "$INPUT" \
    || { log "abort: SynthesizeDayWorkflow $D failed"; exit 4; }
done

# Weekly cascade.
for W in "${WEEKS[@]}"; do
  INPUT=$(date_to_input "$W")
  TAG=$(echo "$W" | tr -d -)
  run_workflow AggregateWeekWorkflow "catchup-aggweek-$TAG-$(date -u +%H%M%S)" "$INPUT" \
    || { log "abort: AggregateWeekWorkflow $W failed"; exit 5; }
done

T1=$(date +%s)
log "==== catchup-a DONE  elapsed=$((T1-T0))s ===="
