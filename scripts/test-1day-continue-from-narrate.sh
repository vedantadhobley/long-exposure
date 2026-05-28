#!/usr/bin/env bash
# Continuation of test-1day-tierAB-052228.sh starting from NarrateWorkflow,
# since the original script's Score completed (8 min) and we don't want to lose
# that work. Same workflow chain, same input, fixed JSON parser.
set -uo pipefail

DATE="2026-05-22"
DATE_INPUT='[2026,5,22]'
STAMP=$(date +%Y%m%d-%H%M%S)
LOG="$(dirname "$0")/test-1day-continue-${STAMP}.log"

echo "=== continuation: Narrate → … on ${DATE}; logs → ${LOG}"
echo "$(date) test-1day continuation start" | tee -a "$LOG"

run_stage () {
    local type=$1
    local wfid="test2-${type,,}-${DATE//-/}-$(date +%s)"
    echo "$(date) ── starting ${type} (workflow_id=${wfid})" | tee -a "$LOG"
    docker exec long-exposure-dev-temporal temporal workflow start \
        --task-queue long-exposure-daily-pipeline \
        --type "$type" \
        --workflow-id "$wfid" \
        --input "$DATE_INPUT" 2>&1 | tee -a "$LOG"
    while true; do
        local status
        status=$(docker exec long-exposure-dev-temporal temporal workflow describe \
            -w "$wfid" --output json 2>/dev/null | grep -m1 '"status"' \
            | sed 's/.*"status": "WORKFLOW_EXECUTION_STATUS_//; s/",.*//')
        case "$status" in
            "COMPLETED") echo "$(date) ${type} → Completed" | tee -a "$LOG"; return 0 ;;
            "FAILED"|"TERMINATED"|"TIMED_OUT"|"CANCELED")
                echo "$(date) ${type} → ${status}" | tee -a "$LOG"
                docker exec long-exposure-dev-temporal temporal workflow show -w "$wfid" --output table 2>&1 | tail -20 | tee -a "$LOG"
                return 1 ;;
        esac
        sleep 15
    done
}

run_stage NarrateWorkflow          || exit 1
run_stage InterpretWorkflow        || exit 1
run_stage SynthesizeDayWorkflow    || exit 1
run_stage AggregateWeekWorkflow    || exit 1
run_stage AggregateQuarterWorkflow || exit 1
run_stage AggregateYearWorkflow    || exit 1

echo "=== 1-DAY TEST CONTINUATION COMPLETE ===" | tee -a "$LOG"
