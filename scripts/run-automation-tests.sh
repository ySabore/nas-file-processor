#!/usr/bin/env bash
# Run all automation tests: unit, integration, contract (Groovy), and E2E.
# No external services required; E2E uses MockWebServer.
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

echo "=== Cleaning ==="
./gradlew clean

echo ""
echo "=== Running all tests (unit, integration, contract, E2E) ==="
./gradlew test --no-daemon

echo ""
echo "=== Automation tests complete ==="
echo "Reports: build/reports/tests/test/index.html"
