#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_SCRIPT="$ROOT_DIR/scripts/run-backend.sh"
FRONTEND_SCRIPT="$ROOT_DIR/scripts/run-frontend.sh"

cleanup() {
  if [[ -n "${BACKEND_PID:-}" ]]; then
    if kill -0 "$BACKEND_PID" 2>/dev/null; then
      kill "$BACKEND_PID" 2>/dev/null || true
      wait "$BACKEND_PID" 2>/dev/null || true
    fi
  fi
}

trap cleanup EXIT INT TERM

"$BACKEND_SCRIPT" &
BACKEND_PID=$!

BACKEND_HEALTH_URL="${BACKEND_HEALTH_URL:-http://localhost:5133/api/health}"
BACKEND_STARTUP_TIMEOUT_SECONDS="${BACKEND_STARTUP_TIMEOUT_SECONDS:-60}"

echo "Waiting for backend at ${BACKEND_HEALTH_URL}..."

for ((i = 0; i < BACKEND_STARTUP_TIMEOUT_SECONDS; i++)); do
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "Backend exited before becoming ready."
    wait "$BACKEND_PID"
    exit 1
  fi

  if curl --silent --fail "$BACKEND_HEALTH_URL" >/dev/null 2>&1; then
    echo "Backend is ready. Starting frontend..."
    "$FRONTEND_SCRIPT"
    exit 0
  fi

  sleep 1
done

echo "Backend did not become ready within ${BACKEND_STARTUP_TIMEOUT_SECONDS}s."
exit 1
