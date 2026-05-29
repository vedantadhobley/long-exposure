#!/usr/bin/env bash
# Post-catchup-(a) handoff:
#   1. Verify catchup-(a) actually completed cleanly
#   2. Confirm nothing in flight in Temporal
#   3. Restart worker (loads Phase 7b — BackgroundHeartbeat in Narrate/Interpret)
#   4. Verify new JVM booted + workflows registered + Phase 7b symbols present
#   5. Audit final dataset state across the catchup window
#
# Run after scripts/catchup-2026-05-29.sh reaches "==== catchup-a DONE ====".
# Idempotent: if catchup hasn't finished or workflows are in flight, refuses to
# restart the worker. Logs to scripts/post-catchup-2026-05-29.log.

set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/scripts/post-catchup-2026-05-29.log"
CATCHUP_LOG="$ROOT/scripts/catchup-2026-05-29.log"

log() { echo "[$(date -u '+%Y-%m-%dT%H:%M:%SZ')] $*" | tee -a "$LOG"; }
sep() { echo "---"; }

log "==== post-catchup START ===="

# ─── Step 0: catchup completion guard ──────────────────────────────────────
if [ ! -f "$CATCHUP_LOG" ]; then
  log "ABORT: catchup log not found at $CATCHUP_LOG"
  exit 1
fi
if ! grep -q "==== catchup-a DONE" "$CATCHUP_LOG"; then
  log "ABORT: catchup-(a) has not logged 'DONE' — refusing to restart worker mid-chain"
  log "  current tail of $CATCHUP_LOG:"
  tail -5 "$CATCHUP_LOG" | sed 's/^/    /' | tee -a "$LOG"
  exit 1
fi
if grep -q "^\[.*\] abort:" "$CATCHUP_LOG"; then
  log "WARN: catchup-(a) recorded one or more aborts:"
  grep "^\[.*\] abort:" "$CATCHUP_LOG" | tail -5 | sed 's/^/    /' | tee -a "$LOG"
  log "WARN: proceeding anyway — review the abort lines manually before trusting the dataset"
fi
log "catchup-(a) completed cleanly per log marker"

# ─── Step 1: nothing in flight in Temporal ─────────────────────────────────
RUNNING_COUNT=$(docker exec long-exposure-dev-temporal temporal workflow list \
                 --query "ExecutionStatus='Running'" 2>/dev/null \
                 | grep -c "Running" || echo 0)
log "in-flight workflows: $RUNNING_COUNT"
if [ "${RUNNING_COUNT:-0}" -gt 0 ]; then
  log "ABORT: $RUNNING_COUNT workflows still in flight; will not restart worker"
  docker exec long-exposure-dev-temporal temporal workflow list \
    --query "ExecutionStatus='Running'" 2>&1 | head -10 | tee -a "$LOG"
  exit 2
fi

# ─── Step 2: Phase 7b symbols present in compiled classes ──────────────────
log "checking Phase 7b symbols in compiled .class files..."
NARR_OK=$(docker exec long-exposure-dev-worker sh -c \
  'strings /app/build/classes/java/main/com/longexposure/temporal/activities/NarrateEventActivityImpl.class 2>/dev/null | grep -c narrate-heartbeat' \
  2>/dev/null || echo 0)
INTR_OK=$(docker exec long-exposure-dev-worker sh -c \
  'strings /app/build/classes/java/main/com/longexposure/temporal/activities/InterpretEventActivityImpl.class 2>/dev/null | grep -c interpret-heartbeat' \
  2>/dev/null || echo 0)
log "  NarrateEventActivityImpl.class:    narrate-heartbeat=${NARR_OK}"
log "  InterpretEventActivityImpl.class:  interpret-heartbeat=${INTR_OK}"
if [ "${NARR_OK:-0}" -eq 0 ] || [ "${INTR_OK:-0}" -eq 0 ]; then
  log "ABORT: Phase 7b symbols missing — class files weren't recompiled"
  log "  (try: docker exec -w /app long-exposure-dev-worker gradle --no-daemon --quiet compileJava)"
  exit 3
fi

# ─── Step 3: restart worker ────────────────────────────────────────────────
log "restarting worker container..."
docker restart long-exposure-dev-worker >>"$LOG" 2>&1
log "  restart issued at $(date -u '+%Y-%m-%dT%H:%M:%SZ'); waiting for boot..."

# Wait up to ~3 min for WorkerMain to log "workers started"
BOOTED=0
for i in $(seq 1 36); do
  if docker logs long-exposure-dev-worker --since 5m 2>&1 | grep -q "workers started"; then
    BOOTED=1
    log "  worker booted (poll iter $i)"
    break
  fi
  sleep 5
done
if [ "$BOOTED" -eq 0 ]; then
  log "ABORT: worker did not log 'workers started' within 3 min"
  exit 4
fi

# ─── Step 4: verify new JVM loaded Phase 7b ────────────────────────────────
# WorkerMain PID + classpath
WMPID=$(docker exec long-exposure-dev-worker pgrep -f "com.longexposure.Main" 2>/dev/null | tail -1)
if [ -z "$WMPID" ]; then
  log "ABORT: WorkerMain JVM not found after restart"
  exit 5
fi
WMSTART=$(docker exec long-exposure-dev-worker stat -c "%y" /proc/$WMPID 2>/dev/null)
log "WorkerMain PID=$WMPID started=$WMSTART"

# Verify the new JVM's classpath includes the recompiled /app/build classes
CP=$(docker exec long-exposure-dev-worker sh -c "tr '\0' '\n' < /proc/$WMPID/cmdline 2>/dev/null | grep -A1 '^-cp$' | tail -1" 2>/dev/null \
     | tr ':' '\n' | grep "/app/build/classes" | head -1)
log "JVM classpath includes: ${CP:-<not found>}"

# Workflows registered (from worker INFO logs)
log "workflow registration:"
docker logs long-exposure-dev-worker --since 5m 2>&1 \
  | grep -E "WorkerMain.*connecting to Temporal|WorkerMain.*workers started|WorkerMain.*schedule already exists" \
  | tail -5 | sed 's/^/    /' | tee -a "$LOG"

# ─── Step 5: final dataset audit ───────────────────────────────────────────
sep | tee -a "$LOG"
log "==== final dataset audit (2026-05-12 → 2026-05-22) ===="

docker exec long-exposure-dev-postgres psql -U leuser -d longexposure -c "
\\echo '\n--- narratives per day (latest verified) ---'
SELECT trading_date, COUNT(*) AS narratives,
       SUM((verifier_passed)::int) AS verified
FROM narratives
WHERE trading_date BETWEEN '2026-05-12' AND '2026-05-22'
GROUP BY trading_date ORDER BY trading_date;

\\echo '\n--- interpretations per day (latest verified) ---'
SELECT trading_date, COUNT(*) AS interps,
       SUM((verifier_passed)::int) AS verified
FROM interpretations
WHERE trading_date BETWEEN '2026-05-12' AND '2026-05-22'
GROUP BY trading_date ORDER BY trading_date;

\\echo '\n--- inter-day events with INTERPRET coverage ---'
SELECT se.trading_date,
       se.scorer_id,
       COUNT(se.*) AS selected,
       COUNT(i.*) AS interpreted
FROM selected_events se
LEFT JOIN interpretations i ON i.selected_id = se.selected_id
WHERE se.scorer_id IN ('volume_deviation','time_in_book_drift')
  AND se.trading_date BETWEEN '2026-05-12' AND '2026-05-22'
GROUP BY se.trading_date, se.scorer_id
ORDER BY se.trading_date, se.scorer_id;

\\echo '\n--- daily_synthesis per day (latest) ---'
SELECT trading_date,
       LEFT(synthesis_text, 80) || '...' AS preview,
       verifier_passed
FROM daily_synthesis
WHERE trading_date BETWEEN '2026-05-12' AND '2026-05-22'
ORDER BY trading_date, created_at DESC;

\\echo '\n--- weekly_aggregate ---'
SELECT week_start,
       LEFT(aggregate_text, 80) || '...' AS preview,
       verifier_passed,
       created_at
FROM weekly_aggregate
ORDER BY week_start, created_at DESC;
" 2>&1 | tee -a "$LOG"

sep | tee -a "$LOG"
log "==== post-catchup DONE ===="

# Suggested next-step monitor (not auto-run):
log "next:"
log "  • Inspect $LOG and the audit output above"
log "  • If any inter-day day shows interpreted<selected, run InterpretWorkflow for it"
log "  • If any synthesis shows verifier_passed=false, re-fire SynthesizeDayWorkflow"
log "  • If everything clean → safe to docs/sql/prune-stale-narrations.sql in dry-run mode"
