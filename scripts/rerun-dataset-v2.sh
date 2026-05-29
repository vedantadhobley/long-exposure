#!/usr/bin/env bash
# Resumed re-run starting 2026-05-19 (Days 1-6 already done via v1 script).
#
# Two changes from rerun-dataset.sh:
#   1. Starts from 05-19 (Days 05-11..05-18 already processed; 05-15's
#      synth + AggregateWeek-05-11 handled manually before this script kicks).
#   2. INTERLEAVES weekly aggregates after each week's last AVAILABLE day —
#      not batched at the end. This matches DailyPipelineWorkflow's design
#      and avoids the failure mode where a per-day synth failure leaves the
#      end-of-run weekly aggregate reading stale content.
#
# Triggers:
#   After 05-22 finishes → AggregateWeek(2026,5,18)
#   After 05-27 finishes → AggregateWeek(2026,5,26)
#   (Week-of-05-11 / AggregateWeek(2026,5,11) is handled manually before this
#    script — week-of-05-11 = 05-11..05-15, all fresh after the 05-15 recovery.)

set -uo pipefail

TEMPORAL="docker exec long-exposure-dev-temporal temporal"
LOG=/home/vedanta/workspace/dev/long-exposure/scripts/rerun-dataset.log

# Chronological. "kind" = existing | new.
DAYS=(
  "2026-05-19 2026,5,19 existing"
  "2026-05-20 2026,5,20 existing"
  "2026-05-21 2026,5,21 existing"
  "2026-05-22 2026,5,22 existing"     # last day of week-of-05-18
  "2026-05-26 2026,5,26 new"
  "2026-05-27 2026,5,27 new"          # last day of week-of-05-26 (Memorial-Day partial)
)

# Map: day-just-finished -> ymd-tuple for AggregateWeek to fire right after.
declare -A WEEK_TRIGGER
WEEK_TRIGGER["2026-05-22"]="2026,5,18"
WEEK_TRIGGER["2026-05-27"]="2026,5,26"

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

# Phase workflow (LocalDate input).
run_stage() {
  local wftype="$1" label="$2" ymd="$3"
  local ts wfid status
  ts=$(date +%H%M%S)
  wfid="rerun2-${wftype}-${label//-/}-${ts}"
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

# DailyPipelineWorkflow (full input object, for new days).
run_daily_pipeline() {
  local label="$1" ymd="$2"
  local ts wfid status
  ts=$(date +%H%M%S)
  wfid="rerun2-daily-${label//-/}-${ts}"
  $TEMPORAL workflow start \
    --task-queue long-exposure-daily-pipeline \
    --workflow-id "$wfid" \
    --type DailyPipelineWorkflow \
    --input '{"targetDate":['"$ymd"'],"pollUntilReady":false,"forceReingest":false,"runRetentionSweep":false}' \
    >>"$LOG" 2>&1
  if [ $? -ne 0 ]; then log "  !! failed to START DailyPipelineWorkflow $label"; return 1; fi
  status=$(wait_terminal "$wfid")
  log "  DailyPipelineWorkflow $label -> $status  ($wfid)"
  [ "$status" = "COMPLETED" ]
}

log "================ rerun-v2 start (resume from 05-19, interleaved weeklies) ================"
log "DAYS: ${#DAYS[@]} entries (${#DAYS[@]} after 05-18 already done in v1)"

for entry in "${DAYS[@]}"; do
  read -r date ymd kind <<< "$entry"
  if [ "$kind" = "new" ]; then
    log "--- DAY $date [NEW] : full DailyPipelineWorkflow ---"
    if ! run_daily_pipeline "$date" "$ymd"; then
      log "  (DailyPipelineWorkflow did not complete; continuing)"
    fi
  else
    log "--- DAY $date [EXISTING] : score -> narrate -> interpret -> synthesize ---"
    if run_stage ScoreWorkflow "$date" "$ymd"; then
      run_stage NarrateWorkflow       "$date" "$ymd"
      run_stage InterpretWorkflow     "$date" "$ymd"
      run_stage SynthesizeDayWorkflow "$date" "$ymd"
    else
      log "  (score did not complete; skipping narrate/interpret/synth for $date)"
    fi
  fi

  # Interleaved weekly aggregate: fire only after the LAST available day of
  # the week — read finalized syntheses, not a mix of fresh + stale.
  if [[ -n "${WEEK_TRIGGER[$date]:-}" ]]; then
    week_ymd="${WEEK_TRIGGER[$date]}"
    log "--- WEEKLY aggregate for week containing $date (week_start=$week_ymd) ---"
    run_stage AggregateWeekWorkflow "week-of-$week_ymd" "$week_ymd"
  fi
done

log "================ rerun-v2 done ================"
