#!/usr/bin/env bash
# Full uniform re-run of the loaded 2-week dataset (Phase 4 end-state).
#
# Re-applies, across every loaded trading day, the accumulated breakdown/code
# changes so the whole dataset is uniform + all-passing:
#   - $X,XXX.XX notional formatting (BreakdownFmt.formatDollars)
#   - co_occurring fixes (integer deletes, no "children events" jargon)
#   - volume_deviation on every eligible day (05-18..21 had none)
#   - verifier-driven retry (re-rolls transient number-glitch failures)
#
# We DO NOT re-download / re-parse — the wire data is already loaded and
# validated. We drive the phase workflows directly:
#   per day:  ScoreWorkflow -> NarrateWorkflow -> InterpretWorkflow -> SynthesizeDayWorkflow
#   per week: AggregateWeekWorkflow   (after all days, chronological)
#
# The per-event content-addressed skip makes this cheap: only changed events
# hit the LLM; unchanged ones are reused. LLM stages serialize on joi's 2 GPU
# slots, so everything runs strictly one workflow at a time (the waits enforce
# the one-LLM-workflow-at-a-time rule).
#
# Progress -> scripts/rerun-dataset.log ; poll with `tail -f`.

set -uo pipefail

TEMPORAL="docker exec long-exposure-dev-temporal temporal"
LOG=/home/vedanta/workspace/dev/long-exposure/scripts/rerun-dataset.log

# Chronological. Per-day order is irrelevant to final state; aggregates run at
# the end so each week reads the prior (finalized) week.
DAYS=(
  "2026-05-08 2026,5,8"
  "2026-05-11 2026,5,11"
  "2026-05-12 2026,5,12"
  "2026-05-13 2026,5,13"
  "2026-05-14 2026,5,14"
  "2026-05-15 2026,5,15"
  "2026-05-18 2026,5,18"
  "2026-05-19 2026,5,19"
  "2026-05-20 2026,5,20"
  "2026-05-21 2026,5,21"
  "2026-05-22 2026,5,22"
)

# One representative date per ISO week, chronological (prior week must be
# finalized before the next aggregates, since AggregateWeek reads prior weeks).
WEEKS=(
  "2026-05-08 2026,5,8"    # week of Mon 05-04 (1 day)
  "2026-05-11 2026,5,11"   # week of Mon 05-11
  "2026-05-18 2026,5,18"   # week of Mon 05-18
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

# run_stage <type> <label> <ymd-json>
run_stage() {
  local wftype="$1" label="$2" ymd="$3"
  local ts wfid status
  ts=$(date +%H%M%S)
  wfid="rerun-${wftype}-${label//-/}-${ts}"
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

log "================ full dataset re-run start ================"
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

log "--- weekly aggregates (chronological) ---"
for entry in "${WEEKS[@]}"; do
  read -r date ymd <<< "$entry"
  run_stage AggregateWeekWorkflow "$date" "$ymd"
done

log "================ full dataset re-run DONE ================"
