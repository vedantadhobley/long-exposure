#!/usr/bin/env bash
# Serial backfill of the prior trading week (2026-05-11 → 2026-05-15) to
# reach the 2-full-week retention target. Same constraints as the first
# week: LLM stages serialize on joi's 2 GPU slots, so days run strictly
# one-at-a-time (full pipeline per day before the next starts).
#
# All five days are fresh launches (none pre-running). The pipeline
# handles any non-trading day gracefully (ResolveUrl throws NotATradingDay
# → skipped_no_data), so we just enqueue all weekdays.
#
# Progress → backfill-prior-week.log ; poll with `tail -f`.

set -uo pipefail

TEMPORAL="docker exec long-exposure-dev-temporal temporal"
LOG=/home/vedanta/workspace/dev/long-exposure/scripts/backfill-prior-week.log

# (label, date, "Y,M,D")  — chronological; load order is irrelevant to final state
DAYS=(
  # 05-11 already completed (run earlier); resume at 05-12.
  "d2 2026-05-12 2026,5,12"
  "d3 2026-05-13 2026,5,13"
  "d4 2026-05-14 2026,5,14"
  "d5 2026-05-15 2026,5,15"
)

log() { echo "$(date '+%Y-%m-%d %H:%M:%S')  $*" | tee -a "$LOG"; }

# Block until workflow-id reaches a terminal ExecutionStatus; echo it.
# Reads JSON (describe has no plain-text Status line) and strips the
# WORKFLOW_EXECUTION_STATUS_ prefix.
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
      RUNNING) sleep 60 ;;
      *)       sleep 30 ;;
    esac
  done
}

log "=== prior-week backfill driver start (2026-05-11 → 2026-05-15) ==="
for entry in "${DAYS[@]}"; do
  read -r label date ymd <<< "$entry"
  ts=$(date +%H%M%S)
  wfid="bf-prior-${label}-${date//-/}-${ts}"
  log "--- launching $label ($date) as $wfid ---"
  $TEMPORAL workflow start \
    --task-queue long-exposure-daily-pipeline \
    --workflow-id "$wfid" \
    --type DailyPipelineWorkflow \
    --input "{\"targetDate\":[$ymd],\"pollUntilReady\":false,\"forceReingest\":true,\"runRetentionSweep\":false}" \
    >>"$LOG" 2>&1
  if [ $? -ne 0 ]; then log "FAILED to start $label — aborting"; exit 1; fi
  s=$(wait_terminal "$wfid")
  log "$label ($date) terminal: $s"
done
log "=== prior-week backfill driver done — all 5 days terminal ==="
