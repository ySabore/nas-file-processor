#!/usr/bin/env bash
# End-to-end test: NAS file processor + process API at http://localhost:8081
# Prereqs: Process API must be running on port 8081.

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

# Ensure E2E test file exists in inbound (use existing from error folder or create manually)
E2E_JSON="nas/inbound/e2e_test.json"
if [[ ! -f "$E2E_JSON" ]]; then
  # Restore from a previous error run if present
  RESTORE=$(find nas/error -name "e2e_test_*.json" -type f 2>/dev/null | head -1)
  if [[ -n "$RESTORE" ]]; then
    mkdir -p nas/inbound
    cp "$RESTORE" "$E2E_JSON"
    echo "Restored $E2E_JSON from $RESTORE"
  else
    echo "Error: Put e2e_test.json (JSON array of loan objects) in nas/inbound and re-run."
    exit 1
  fi
fi

# Optional: use port 8082 if 8080 is already in use
PORT="${NAS_PROCESSOR_PORT:-8082}"
echo "Starting NAS file processor on port $PORT (api.base-url=http://localhost:8081)..."
echo "Processor will run one cycle on startup and process $E2E_JSON."
echo ""

./gradlew bootRun --args="--server.port=$PORT --api.base-url=http://localhost:8081" 2>&1 | tee /tmp/nas-e2e.log &
PID=$!
trap "kill $PID 2>/dev/null || true" EXIT

# Wait for "Processing cycle complete" or timeout
for i in $(seq 1 45); do
  sleep 1
  if grep -q "Processing cycle complete" /tmp/nas-e2e.log 2>/dev/null; then
    echo ""
    echo "E2E cycle finished. Checking results..."
    break
  fi
  if ! kill -0 $PID 2>/dev/null; then
    echo "Process exited early. Last log lines:"
    tail -30 /tmp/nas-e2e.log
    exit 1
  fi
done

# Quick verification
if grep -q "Status=SUCCESS" /tmp/nas-e2e.log && grep -q "Batch 1/1 accepted. HTTP 200" /tmp/nas-e2e.log; then
  echo "PASS: E2E succeeded. File processed, API returned 200, file moved to completed."
  ls -la nas/completed/ 2>/dev/null | tail -5
  exit 0
else
  echo "Check /tmp/nas-e2e.log for details. If API at 8081 is down, batches will fail."
  exit 1
fi
