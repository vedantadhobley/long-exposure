#!/usr/bin/env bash
# Serial 5-day backfill driver.
#
# Each DailyPipelineWorkflow contains LLM-bearing child workflows
# (Narrate / Interpret / Synthesize). Only ONE LLM-bearing workflow may
# run at a time (joi has 2 GPU slots; LlamaClient Semaphore(2,fair)).
# So days MUST run strictly serial: a full day (download → parse →
# validate → score → narrate → interpret → synthesize → compress →
# cleanup) completes before the next day starts.
#
# Day 1 (2026-05-22) was launched manually before this driver started;
# the driver waits for it, then drives days 2-5.
#
# Progress is logged to backfill.log; poll it with `tail -f`.

set -uo pipefail

TEMPORAL="docker exec long-exposure-dev-temporal temporal"
LOG=/home/vedanta/workspace/dev/long-exposure/scripts/backfill.log

# (label, workflow-id, "Y,M,D")
DAY1_WF="backfill-d1-20260522-172759"      # already running
REMAINING=(
  "d2 2026-05-21 2026,5,21"
  "d3 2026-05-20 2026,5,20"
  "d4 2026-05-19 2026,5,19"
  "d5 2026-05-18 2026,5,18"
)

log() { echo "$(date '+%Y-%m-%d %H:%M:%S')  $*" | tee -a "$LOG"; }

# Block until the given workflow-id reaches a terminal ExecutionStatus.
# Returns the terminal status string (e.g. COMPLETED, FAILED).
#
# `temporal workflow describe` has no plain-text "Status" line, so we read
# the JSON form, which carries
#   "status": "WORKFLOW_EXECUTION_STATUS_COMPLETED"
# and strip the enum prefix. (An earlier text-grep version never matched
# and hung forever in the poll loop.)
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
      RUNNING)
        sleep 60 ;;
      *)
        # transient describe failure or parse miss — retry
        sleep 30 ;;
    esac
  done
}

log "=== backfill driver start ==="
log "waiting for Day 1 ($DAY1_WF, 2026-05-22) to finish..."
s=$(wait_terminal "$DAY1_WF")
log "Day 1 terminal: $s"

for entry in "${REMAINING[@]}"; do
  read -r label date ymd <<< "$entry"
  ts=$(date +%H%M%S)
  wfid="backfill-${label}-${date//-/}-${ts}"
  log "--- launching $label ($date) as $wfid ---"
  $TEMPORAL workflow start \
    --task-queue long-exposure-daily-pipeline \
    --workflow-id "$wfid" \
    --type DailyPipelineWorkflow \
    --input "{\"targetDate\":[$ymd],\"pollUntilReady\":false,\"forceReingest\":false,\"runRetentionSweep\":false}" \
    >>"$LOG" 2>&1
  if [ $? -ne 0 ]; then
    log "FAILED to start $label — aborting driver"
    exit 1
  fi
  s=$(wait_terminal "$wfid")
  log "$label ($date) terminal: $s"
done

log "=== backfill driver done — all 5 days terminal ==="
