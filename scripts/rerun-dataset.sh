#!/usr/bin/env bash
# Full uniform re-run + extension of the loaded dataset.
#
# Re-applies, across every loaded trading day, the accumulated breakdown/code
# changes so the whole dataset is uniform + all-passing on the current prompts:
#   - the analytics suite (slippage, OFI, reversion, burstiness, …)
#   - class-label vocabulary (burstiness_class, ofi_class, depth_imbalance_class,
#     refill_cadence_class, order_to_trade_phrase)
#   - intent denylist on SynthesisVerifier + InterpretationVerifier
#   - TimeInBookDriftScorer (the 9th scorer, inter-day)
#   - extract-v9 / interpret-v9 / synthesize-v5 / aggregate-v5 prompts
#   - calendar rollup hierarchy (week + dormant quarter + dormant year)
#
# 2026-05-28: also EXTENDS the dataset to the current week:
#   - drops 05-08 (the trailing Friday before the 2-week block — user request)
#   - keeps 05-11..15, 05-18..22 (existing)
#   - adds 05-26, 05-27 (new; HIST published, need full pipeline)
#   - 05-25 was Memorial Day (market closed)
#   - 05-28 is today (HIST is T+1, data not yet published)
#
# For ALREADY-loaded days we drive the phase workflows directly (re-narrate
# with the new prompts; no re-download/parse):
#   ScoreWorkflow -> NarrateWorkflow -> InterpretWorkflow -> SynthesizeDayWorkflow
#
# For NEW days we drive DailyPipelineWorkflow (full download + parse + score +
# narrate + interpret + synth + weekly+quarterly+yearly aggregate). This also
# aggregates the week-of-05-25 as a side effect of the last new day.
#
# Weekly aggregates run at the end in chronological order (the prior weeks
# need to be finalized before the next reads them as PRIOR_WEEKS context).
# Content-hash skip makes duplicate runs free.
#
# LLM stages serialize on joi's 2 GPU slots (one-LLM-workflow-at-a-time rule
# is enforced by the `wait_terminal` calls — nothing kicks until the previous
# stage hits a terminal status).
#
# Progress -> scripts/rerun-dataset.log ; poll with `tail -f`.

set -uo pipefail

TEMPORAL="docker exec long-exposure-dev-temporal temporal"
LOG=/home/vedanta/workspace/dev/long-exposure/scripts/rerun-dataset.log

# Chronological. Format: "YYYY-MM-DD ymd-tuple kind"
# - existing: data already loaded; ScoreWorkflow chain
# - new:      need full pipeline (download + parse + everything)
DAYS=(
  "2026-05-11 2026,5,11 existing"
  "2026-05-12 2026,5,12 existing"
  "2026-05-13 2026,5,13 existing"
  "2026-05-14 2026,5,14 existing"
  "2026-05-15 2026,5,15 existing"
  "2026-05-18 2026,5,18 existing"
  "2026-05-19 2026,5,19 existing"
  "2026-05-20 2026,5,20 existing"
  "2026-05-21 2026,5,21 existing"
  "2026-05-22 2026,5,22 existing"
  "2026-05-26 2026,5,26 new"
  "2026-05-27 2026,5,27 new"
)

# One representative date per ISO week, chronological.
WEEKS=(
  "2026-05-11 2026,5,11"   # week of Mon 05-11
  "2026-05-18 2026,5,18"   # week of Mon 05-18
  "2026-05-26 2026,5,26"   # week of Mon 05-25 (Memorial Day; only 05-26 + 05-27 traded)
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

# run_stage <type> <label> <ymd-json>   — for phase workflows (input: LocalDate)
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

# run_daily_pipeline <label> <ymd-tuple>  — for new days (input: DailyPipelineWorkflowInput)
# adHoc shape: pollUntilReady=false (fail fast if HIST not available),
# forceReingest=false (fresh day, no existing rows to pre-clean),
# runRetentionSweep=false (DON'T drop existing chunks; preserves the 2-week wire substrate).
run_daily_pipeline() {
  local label="$1" ymd="$2"
  local ts wfid status
  ts=$(date +%H%M%S)
  wfid="rerun-daily-${label//-/}-${ts}"
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

log "================ full dataset re-run + extend start ================"
log "DAYS: ${#DAYS[@]} entries (existing: $(echo "${DAYS[@]}" | tr ' ' '\n' | grep -c existing), new: $(echo "${DAYS[@]}" | tr ' ' '\n' | grep -c new))"
log "WEEKS: ${#WEEKS[@]} entries"

for entry in "${DAYS[@]}"; do
  read -r date ymd kind <<< "$entry"
  if [ "$kind" = "new" ]; then
    log "--- DAY $date [NEW] : full DailyPipelineWorkflow (download + parse + ...) ---"
    if ! run_daily_pipeline "$date" "$ymd"; then
      log "  (DailyPipelineWorkflow did not complete; continuing to next day)"
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
done

log "--- weekly aggregates (chronological; content-hash skip on no-change) ---"
for entry in "${WEEKS[@]}"; do
  read -r date ymd <<< "$entry"
  run_stage AggregateWeekWorkflow "$date" "$ymd"
done

log "================ full dataset re-run + extend DONE ================"
