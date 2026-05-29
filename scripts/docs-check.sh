#!/usr/bin/env bash
# Doc-drift guard: grep live code for canonical counts (scorers, workflow
# registrations, hypertables) and compare against the numbers claimed in docs.
# Catches the "8 scorers / 22 activities / 9 tables" drift that bit us
# 2026-05-28 when adding TimeInBookDriftScorer + the quarterly/yearly
# aggregates left a handful of doc claims stale.
#
# Exits 0 on match, 1 on any drift. Wire as a pre-commit hook or run
# manually before milestone doc passes.

set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

EXIT=0
header() { printf "\n\033[1m== %s ==\033[0m\n" "$1"; }
ok()     { printf "  \033[32m✓\033[0m %s\n" "$1"; }
bad()    { printf "  \033[31m✗\033[0m %s\n" "$1"; EXIT=1; }

# ─── 1. Number of scorers in EventScorerRegistry vs doc claims ─────────
header "scorer count"
SCORER_REGISTRY_FILE=parser/src/main/java/com/longexposure/scoring/EventScorerRegistry.java
SCORERS=$(grep -cE "^\s+new [A-Z][A-Za-z]+Scorer\(\)" "$SCORER_REGISTRY_FILE" 2>/dev/null \
          || echo 0)
echo "  code says: $SCORERS scorers in EventScorerRegistry.ALL"
# Search docs for "N scorers" claims and check each
while IFS=: read -r file line claim; do
    DOC_N=$(echo "$claim" | grep -oE "[0-9]+ scorers" | head -1 | awk '{print $1}')
    if [ -n "$DOC_N" ] && [ "$DOC_N" != "$SCORERS" ]; then
        bad "$file:$line — claims \"$DOC_N scorers\" but code has $SCORERS"
    fi
done < <(grep -rnE "[0-9]+ scorers" docs/ AGENTS.md README.md 2>/dev/null | grep -v scripts | grep -v docs-check)
[ "$EXIT" -eq 0 ] && ok "all doc scorer-counts match code ($SCORERS)"

# ─── 2. Workflow registrations in WorkerMain vs doc claims ──────────────
header "workflow registration count"
WORKER_MAIN=parser/src/main/java/com/longexposure/temporal/WorkerMain.java
WORKFLOWS=$(awk '/registerWorkflowImplementationTypes\(/,/\);/' "$WORKER_MAIN" 2>/dev/null \
            | grep -cE "WorkflowImpl\.class" || echo 0)
echo "  code says: $WORKFLOWS workflow types registered in WorkerMain"
LOCAL_EXIT=$EXIT
while IFS=: read -r file line claim; do
    DOC_N=$(echo "$claim" | grep -oE "[0-9]+ workflows" | head -1 | awk '{print $1}')
    if [ -n "$DOC_N" ] && [ "$DOC_N" != "$WORKFLOWS" ]; then
        bad "$file:$line — claims \"$DOC_N workflows\" but code has $WORKFLOWS"
    fi
done < <(grep -rnE "[0-9]+ workflows" docs/ AGENTS.md README.md 2>/dev/null | grep -v scripts | grep -v docs-check)
[ "$EXIT" -eq "$LOCAL_EXIT" ] && ok "all doc workflow-counts match code ($WORKFLOWS)"

# ─── 3. Activity classes in temporal/activities/ ───────────────────────
header "activity count"
ACTIVITIES=$(ls parser/src/main/java/com/longexposure/temporal/activities/*ActivityImpl.java 2>/dev/null | wc -l)
echo "  code says: $ACTIVITIES *ActivityImpl.java files"
LOCAL_EXIT=$EXIT
while IFS=: read -r file line claim; do
    DOC_N=$(echo "$claim" | grep -oE "[0-9]+ activities" | head -1 | awk '{print $1}')
    if [ -n "$DOC_N" ] && [ "$DOC_N" != "$ACTIVITIES" ]; then
        bad "$file:$line — claims \"$DOC_N activities\" but code has $ACTIVITIES"
    fi
done < <(grep -rnE "[0-9]+ activities" docs/ AGENTS.md README.md 2>/dev/null | grep -v scripts | grep -v docs-check)
[ "$EXIT" -eq "$LOCAL_EXIT" ] && ok "all doc activity-counts match code ($ACTIVITIES)"

# ─── 4. Hypertables in schema.sql ──────────────────────────────────────
header "hypertable count"
SCHEMA=parser/src/main/resources/schema.sql
HYPERTABLES=$(grep -cE "^SELECT create_hypertable\(" "$SCHEMA" 2>/dev/null || echo 0)
echo "  code says: $HYPERTABLES create_hypertable() calls in schema.sql"
LOCAL_EXIT=$EXIT
while IFS=: read -r file line claim; do
    DOC_N=$(echo "$claim" | grep -oE "[0-9]+ hypertables" | head -1 | awk '{print $1}')
    if [ -n "$DOC_N" ] && [ "$DOC_N" != "$HYPERTABLES" ]; then
        bad "$file:$line — claims \"$DOC_N hypertables\" but code has $HYPERTABLES"
    fi
done < <(grep -rnE "[0-9]+ hypertables" docs/ AGENTS.md README.md 2>/dev/null | grep -v scripts | grep -v docs-check)
[ "$EXIT" -eq "$LOCAL_EXIT" ] && ok "all doc hypertable-counts match code ($HYPERTABLES)"

echo
if [ "$EXIT" -eq 0 ]; then
    printf "\033[32mall doc counts match code\033[0m\n"
else
    printf "\033[31mdoc drift detected — see flagged lines above\033[0m\n"
fi
exit "$EXIT"
