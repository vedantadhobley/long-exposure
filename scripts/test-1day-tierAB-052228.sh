#!/usr/bin/env bash
# 1-day validation gate for the 2026-05-28 pre-relaunch batch.
# Runs the full per-day chain on 2026-05-22 (the day we have new analytics for)
# + exercises the AggregateWeek path + the Tier B dormant-path workflows.
# After this passes, kick the overnight relaunch.
set -uo pipefail

DATE="2026-05-22"
DATE_INPUT='[2026,5,22]'   # Temporal LocalDate JSON
STAMP=$(date +%Y%m%d-%H%M%S)
LOG="$(dirname "$0")/test-1day-${STAMP}.log"

echo "=== 1-day test on ${DATE}; logs → ${LOG}"
echo "$(date) test-1day start" | tee -a "$LOG"

# Run a single workflow synchronously; print the resulting status.
run_stage () {
    local type=$1
    local wfid="test-${type,,}-${DATE//-/}-$(date +%s)"
    echo "$(date) ── starting ${type} (workflow_id=${wfid})" | tee -a "$LOG"
    docker exec long-exposure-dev-temporal temporal workflow start \
        --task-queue long-exposure-daily-pipeline \
        --type "$type" \
        --workflow-id "$wfid" \
        --input "$DATE_INPUT" 2>&1 | tee -a "$LOG"
    # block until terminal
    while true; do
        local status
        # JSON output returns the full enum: WORKFLOW_EXECUTION_STATUS_COMPLETED
        # etc. — strip the prefix to compare against the short form.
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

# Per-day chain — re-scores 05-22 with the new OFI/depth class labels, then
# re-narrates with extract-v7 + interpret-v9 (new supporting-analytics prompts),
# then re-synthesizes (the synthesis verifier now has the intent denylist).
run_stage ScoreWorkflow         || exit 1
run_stage NarrateWorkflow       || exit 1
run_stage InterpretWorkflow     || exit 1
run_stage SynthesizeDayWorkflow || exit 1

# Exercise the AggregateWeek path with the new SynthesisVerifier intent denylist
# (also re-aggregates the week of 05-18 with PRIOR_WEEKS=13).
run_stage AggregateWeekWorkflow || exit 1

# Tier B dormant-path smoke: should each return 0 (not enough weeks/quarters
# yet), exercising the gate logic without an LLM call.
run_stage AggregateQuarterWorkflow || exit 1
run_stage AggregateYearWorkflow    || exit 1

echo "=== 1-DAY TEST COMPLETE ===" | tee -a "$LOG"
