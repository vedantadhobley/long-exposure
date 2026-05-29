#!/usr/bin/env bash
# Compact heartbeat: every 5 min, one line per check appended to overnight-heartbeat.log.
# Covers: current Temporal workflow, current day, narration/interpretation/synth/weekly
# counts since start, recent worker-log error count, recent verifier failures.
HEARTBEAT_LOG=/home/vedanta/workspace/dev/long-exposure/scripts/overnight-heartbeat.log
PSQL="docker exec long-exposure-dev-postgres psql -U leuser -d longexposure -tA -c"
START_TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
echo "[$(date '+%Y-%m-%d %H:%M:%S')] HEARTBEAT-MONITOR-START  watching from $START_TS" >> "$HEARTBEAT_LOG"

while true; do
  ts=$(date '+%Y-%m-%d %H:%M:%S')
  # current running workflow (one line)
  running=$(docker exec long-exposure-dev-temporal temporal workflow list --query "ExecutionStatus='Running'" 2>/dev/null | awk 'NR==2 {print $2"|"$3}')
  # recently completed (last 15 min)
  completed=$(docker exec long-exposure-dev-temporal temporal workflow list --query "ExecutionStatus='Completed' AND CloseTime > '$(date -u -d '15 minutes ago' +%Y-%m-%dT%H:%M:%SZ)'" 2>/dev/null | grep -c "^  ")
  failed=$(docker exec long-exposure-dev-temporal temporal workflow list --query "ExecutionStatus='Failed' AND CloseTime > '$START_TS'" 2>/dev/null | grep -c "^  ")
  terminated=$(docker exec long-exposure-dev-temporal temporal workflow list --query "ExecutionStatus='Terminated' AND CloseTime > '$START_TS'" 2>/dev/null | grep -c "^  ")
  # narration cumulative counts since run started
  narr=$($PSQL "SELECT COUNT(*) FROM narratives WHERE created_at > '$START_TS';" 2>/dev/null)
  narr_pass=$($PSQL "SELECT COUNT(*) FROM narratives WHERE created_at > '$START_TS' AND verifier_passed;" 2>/dev/null)
  interp=$($PSQL "SELECT COUNT(*) FROM interpretations WHERE created_at > '$START_TS';" 2>/dev/null)
  synth=$($PSQL "SELECT COUNT(*) FROM daily_synthesis WHERE created_at > '$START_TS';" 2>/dev/null)
  weekly=$($PSQL "SELECT COUNT(*) FROM weekly_aggregate WHERE created_at > '$START_TS';" 2>/dev/null)
  quarterly=$($PSQL "SELECT COUNT(*) FROM quarterly_aggregate WHERE created_at > '$START_TS';" 2>/dev/null)
  # worker exception count in last 5 min
  exc=$(docker logs long-exposure-dev-worker --since 5m 2>&1 | grep -cE "Exception|ERROR|FAILED" || echo 0)
  # which day is currently being processed
  current_day=$($PSQL "SELECT MAX(trading_date) FROM narratives WHERE created_at > NOW() - INTERVAL '10 min';" 2>/dev/null)
  echo "[$ts] running=${running:-none} day=${current_day:-?} narr=$narr ($narr_pass passed) interp=$interp synth=$synth weekly=$weekly quart=$quarterly | recent-15m: completed=$completed failed=$failed terminated=$terminated worker-errors-5m=$exc" >> "$HEARTBEAT_LOG"
  sleep 300
done
