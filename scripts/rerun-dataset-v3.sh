#!/usr/bin/env bash
# Full overnight rerun (Round 2) — 2026-05-29.
#
# Kicked after the 2026-05-28 evening audit + fixes. Code state at kickoff:
#   - Word-form numerals checked by verifier (commit 8c3a3ee)
#   - Structural AttributionVerifier (SYNTHESIZE: bfa3da3, AggregateWeek: 3109133)
#   - "Split between A and B" false-positive fixed (commit aeba0e4)
#   - Selection diversity: per-symbol cap=8, floor 1→3, halt log-scoring (0511866)
#
# All of those invalidate existing selected_events / narratives / interpretations
# / daily_synthesis / weekly_aggregate content_hashes. Full rerun produces fresh
# prose under the new rules.
#
# Day coverage (12 days + 3 weekly rollups):
#   week-of-05-11 (Mon): 05-11, 05-12, 05-13, 05-14, 05-15  -> AggregateWeek-05-11
#   week-of-05-18 (Mon): 05-18, 05-19, 05-20, 05-21, 05-22  -> AggregateWeek-05-18
#   week-of-05-25 (Mon): 05-26, 05-27 (Memorial Day Mon)   -> AggregateWeek-05-25
#                        (NEW DAYS — full DailyPipelineWorkflow inc. parse)
#
# 05-08 deliberately excluded (older than 2-week retention; per project policy).
#
# Per-day timing estimate (under R2/R3/R4 selection — fresh narrate, no cache):
#   Existing day:  Score 16m + Narrate 17m + Interp 17m + Synth 3m  ≈ 53 min
#   New day:       above + Parse 90m + Materialize 10m              ≈ 115 min
#   Weekly:        ~3 min (cache-friendly content hash)
#
# Total wall-clock estimate (sequential, one LLM workflow at a time):
#   10 existing × 53 min  = 530 min
#    2 new      × 115 min = 230 min
#    3 weeklies × 3 min   =   9 min
#                          = ~13 hours
#
# Started at midnight → done ~13:00 EDT next day.

set -uo pipefail

TEMPORAL="docker exec long-exposure-dev-temporal temporal"
LOG=/home/vedanta/workspace/dev/long-exposure/scripts/rerun-dataset-v3.log

# Chronological. "kind" = existing | new.
DAYS=(
  "2026-05-11 2026,5,11 existing"
  "2026-05-12 2026,5,12 existing"
  "2026-05-13 2026,5,13 existing"
  "2026-05-14 2026,5,14 existing"
  "2026-05-15 2026,5,15 existing"     # last day of week-of-05-11
  "2026-05-18 2026,5,18 existing"
  "2026-05-19 2026,5,19 existing"
  "2026-05-20 2026,5,20 existing"
  "2026-05-21 2026,5,21 existing"
  "2026-05-22 2026,5,22 existing"     # last day of week-of-05-18
  "2026-05-26 2026,5,26 new"
  "2026-05-27 2026,5,27 new"          # last day of week-of-05-25
)

# Map: day-just-finished -> ymd-tuple for AggregateWeek to fire right after.
declare -A WEEK_TRIGGER
WEEK_TRIGGER["2026-05-15"]="2026,5,11"
WEEK_TRIGGER["2026-05-22"]="2026,5,18"
WEEK_TRIGGER["2026-05-27"]="2026,5,25"

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
  wfid="rerun3-${wftype}-${label//-/}-${ts}"
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
  wfid="rerun3-daily-${label//-/}-${ts}"
  $TEMPORAL workflow start \
    --task-queue long-exposure-daily-pipeline \
    --workflow-id "$wfid" \
    --type DailyPipelineWorkflow \
    --input '{"targetDate":['"$ymd"'],"pollUntilReady":false,"forceReingest":true,"runRetentionSweep":false}' \
    >>"$LOG" 2>&1
  if [ $? -ne 0 ]; then log "  !! failed to START DailyPipelineWorkflow $label"; return 1; fi
  status=$(wait_terminal "$wfid")
  log "  DailyPipelineWorkflow $label -> $status  ($wfid)"
  [ "$status" = "COMPLETED" ]
}

log "================ rerun-v3 start (full 12-day overnight rerun) ================"
log "DAYS: ${#DAYS[@]} entries (12 days + 3 weekly aggregates)"
log "Code state: R1-R5 from 2026-05-28 evening session"
log "  R1=split-between fix (aeba0e4); R2=per-symbol cap; R3=floor 1→3;"
log "  R4=halt log-scoring (0511866); R5=AggregateWeek attribution (3109133)"

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

  # Interleaved weekly aggregate after the last day of each week.
  if [[ -n "${WEEK_TRIGGER[$date]:-}" ]]; then
    week_ymd="${WEEK_TRIGGER[$date]}"
    log "--- WEEKLY aggregate for week containing $date (week_start=$week_ymd) ---"
    run_stage AggregateWeekWorkflow "week-of-$week_ymd" "$week_ymd"
  fi
done

log "================ rerun-v3 done ================"
