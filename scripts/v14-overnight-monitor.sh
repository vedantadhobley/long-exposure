#!/usr/bin/env bash
# Hourly progress monitor for overnight-v14-20260530-042433.
# Appends to scripts/_archive/v14-overnight-monitor.log every hour
# until the workflow terminates.
set -uo pipefail

WORKFLOW_ID="overnight-v14-20260530-042433"
LOG=/home/vedanta/workspace/dev/long-exposure/scripts/_archive/v14-overnight-monitor.log
mkdir -p "$(dirname "$LOG")"

log() { printf '%s  %s\n' "$(date '+%H:%M:%S EDT')" "$*" >> "$LOG"; }
psql_q() {
  docker exec long-exposure-dev-postgres psql -U leuser -d longexposure -tA -c "$1" 2>&1
}
is_running() {
  docker exec long-exposure-dev-temporal temporal workflow list \
    --query "WorkflowId='${WORKFLOW_ID}' AND ExecutionStatus='Running'" 2>/dev/null \
    | grep -q "$WORKFLOW_ID"
}

log "──────────────────────────────────────────"
log "monitor started — watching $WORKFLOW_ID"

while true; do
  if ! is_running; then
    log "workflow no longer running — final status:"
    docker exec long-exposure-dev-temporal temporal workflow list \
      --query "WorkflowId='${WORKFLOW_ID}'" 2>/dev/null \
      | head -3 | sed 's/^/  /' >> "$LOG"
    log "exiting monitor"
    exit 0
  fi

  log "── hourly check ──"
  log "running workflows:"
  docker exec long-exposure-dev-temporal temporal workflow list \
    --query "ExecutionStatus='Running'" 2>/dev/null \
    | tail -n +2 | head -8 | sed 's/^/  /' >> "$LOG"

  log "narrate progress (per day):"
  psql_q "SELECT trading_date::text || ' ' || COUNT(*) || ' narrated (' || COUNT(*) FILTER (WHERE verifier_passed) || ' pass)'
            FROM narratives WHERE trading_date BETWEEN '2026-05-11' AND '2026-05-22'
                              AND created_at > '2026-05-30 08:24:00'
           GROUP BY trading_date ORDER BY trading_date;" | sed 's/^/  /' >> "$LOG"

  log "verifier failures since v14 kicked:"
  psql_q "SELECT trading_date::text || ' ' || event_type || ' ' || symbol || ': ' ||
                 LEFT(verifier_notes::text, 120)
            FROM narratives WHERE trading_date BETWEEN '2026-05-11' AND '2026-05-22'
                              AND created_at > '2026-05-30 08:24:00'
                              AND NOT verifier_passed
           ORDER BY created_at DESC LIMIT 5;" | sed 's/^/  /' >> "$LOG"

  log "synth-v14 + agg-v8 fired:"
  psql_q "SELECT 'synth ' || trading_date::text || ' ' ||
                 (CASE WHEN verifier_passed THEN 'PASS' ELSE 'FAIL' END) || ' ' ||
                 LEFT(prompt_version, 25)
            FROM daily_synthesis
           WHERE created_at > '2026-05-30 08:24:00'
           ORDER BY trading_date;" | sed 's/^/  /' >> "$LOG"
  psql_q "SELECT 'weekly ' || week_start::text || ' ' ||
                 (CASE WHEN verifier_passed THEN 'PASS' ELSE 'FAIL' END) || ' ' ||
                 LEFT(prompt_version, 25)
            FROM weekly_aggregate
           WHERE created_at > '2026-05-30 08:24:00'
           ORDER BY week_start;" | sed 's/^/  /' >> "$LOG"

  log "──"
  sleep 3600
done
