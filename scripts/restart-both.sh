#!/usr/bin/env bash
# Restart both applications:
#   1. Process API (port 8081) - downstream service that implements /api/v1/process
#   2. NAS File Processor (port 8080) - this app, calls the process API
#
# The Process API is not in this repo; start it yourself (e.g. from its project).
# This script stops whatever is on 8080 and 8081, then starts the NAS processor.

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

stop_port() {
  local port=$1
  local name=$2
  # Find PIDs listening on the port (macOS lsof)
  local pids
  pids=$(lsof -ti ":$port" 2>/dev/null || true)
  if [[ -n "$pids" ]]; then
    echo "Stopping $name (port $port)..."
    echo "$pids" | xargs kill -9 2>/dev/null || true
    sleep 2
  else
    echo "Nothing running on port $port ($name)."
  fi
}

echo "=== Stopping applications ==="
stop_port 8081 "Process API"
stop_port 8080 "NAS File Processor"
echo ""

echo "=== Starting NAS File Processor (port 8080, api.base-url=http://localhost:8081) ==="
echo "Start your Process API on port 8081 in another terminal if needed."
echo ""

# Run in foreground so user sees logs; use Ctrl+C to stop
./gradlew bootRun --args='--api.base-url=http://localhost:8081' "$@"
