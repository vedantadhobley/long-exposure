#!/usr/bin/env bash
# Resume the uniform re-run from where it was interrupted (days 05-08..05-18
# already done with the current formatting/retry/co_occurring fixes; 05-19 was
# mid-interpret when stopped; 05-20..22 still stale from the prior run).
#
# Re-does 05-19..22 fully (score → narrate → interpret → synthesize) — score is
# idempotent and the per-event content-hash skip makes narrate/interpret cheap
# for anything already done — then re-aggregates the 05-18 ISO week (now spanning
# the freshly-redone 05-19..22). Earlier weeks are unchanged and need no re-agg.
#
# Same constraints as the main driver: LLM stages serialize on joi, one workflow
# at a time. Progress -> scripts/rerun-resume.log.

set -uo pipefail

TEMPORAL="docker exec long-exposure-dev-temporal temporal"
LOG=/home/vedanta/workspace/dev/long-exposure/scripts/rerun-resume.log

DAYS=(
  "2026-05-19 2026,5,19"
  "2026-05-20 2026,5,20"
  "2026-05-21 2026,5,21"
  "2026-05-22 2026,5,22"
)

WEEKS=(
  "2026-05-18 2026,5,18"   # week of Mon 05-18 — now complete with 05-19..22
)

log() { echo "$(date '+%Y-%m-%d %H:%M:%S')  $*" | tee -a "$LOG"; }

wait_terminal() {
  local wfid="$1"
  while true; do
    local status
    status=$($TEMPORAL workflow describe --workflow-id "$wfid" -o json 2>/dev/null \
      | grep -m1 '"status"' \
      | sed -E 's/.*"WORKFLOW_EXECUTION_STATUS_([A-Z_]+)".*/\1/')
    case "$status" in
      COMPLETED|FAILED|TERMINATED|CANCELED|TIMED_OUT|CONTINUED_AS_NEW)
        echo "$status"; return 0 ;;
      RUNNING) sleep 30 ;;
      *)       sleep 20 ;;
    esac
  done
}

run_stage() {
  local wftype="$1" label="$2" ymd="$3"
  local ts wfid status
  ts=$(date +%H%M%S)
  wfid="resume-${wftype}-${label//-/}-${ts}"
  $TEMPORAL workflow start \
    --task-queue long-exposure-daily-pipeline \
    --workflow-id "$wfid" \
    --type "$wftype" \
    --input "[$ymd]" >>"$LOG" 2>&1
  if [ $? -ne 0 ]; then log "  !! failed to START $wftype $label"; return 1; fi
  status=$(wait_terminal "$wfid")
  log "  $wftype $label -> $status  ($wfid)"
  [ "$status" = "COMPLETED" ]
}

log "================ resume re-run start (05-19..22 + 05-18 week) ================"
for entry in "${DAYS[@]}"; do
  read -r date ymd <<< "$entry"
  log "--- DAY $date : score -> narrate -> interpret -> synthesize ---"
  if run_stage ScoreWorkflow "$date" "$ymd"; then
    run_stage NarrateWorkflow       "$date" "$ymd"
    run_stage InterpretWorkflow     "$date" "$ymd"
    run_stage SynthesizeDayWorkflow "$date" "$ymd"
  else
    log "  (score did not complete; skipping narrate/interpret/synth for $date)"
  fi
done

log "--- weekly aggregate (05-18 week, now complete) ---"
for entry in "${WEEKS[@]}"; do
  read -r date ymd <<< "$entry"
  run_stage AggregateWeekWorkflow "$date" "$ymd"
done

log "================ resume re-run DONE ================"
