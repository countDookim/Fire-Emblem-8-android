#!/usr/bin/env bash
# Run clock for scheduled runs (owner rule: ~30 min, work until ~minute 23, never end early).
#   tools/run_clock.sh start   record the run start (keeps the earliest; setup.sh calls it)
#   tools/run_clock.sh         print elapsed minutes
#   tools/run_clock.sh check   exit 1 (and say what to do) if wrap-up would start before minute 23
set -euo pipefail
F=${RUN_CLOCK_FILE:-/tmp/fe8_run_start}
case "${1:-}" in
  start) { [ -s "$F" ] && [ $(( $(date +%s) - $(cat "$F") )) -lt 7200 ]; } || date +%s > "$F";  # stale (>2 h) = new run
         echo "run start: $(date -u -d @"$(cat "$F")" +%H:%M:%S) UTC";;
  *)
    [ -s "$F" ] || { echo "no run start recorded: run 'tools/run_clock.sh start' first"; exit 2; }
    m=$(( ($(date +%s) - $(cat "$F")) / 60 ))
    echo "elapsed: ${m} min (work until 23, wrap up by 30)"
    if [ "${1:-}" = check ] && [ "$m" -lt 23 ]; then
      echo "TOO EARLY to wrap up: $((23 - m)) min of work left. Start the next HANDOFF task (on wip/<task-id> if it may not finish)."
      echo "Token saving (CLAUDE.md rule 9) never shortens a run; 'no verifiable task fits' is not a reason to stop."
      exit 1
    fi;;
esac
