#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND_DIR="$ROOT_DIR/frontend"

export NPM_CONFIG_CACHE="${NPM_CONFIG_CACHE:-/tmp/codex-npm-cache}"

mkdir -p "$NPM_CONFIG_CACHE"

cd "$FRONTEND_DIR"

if [[ ! -d node_modules ]]; then
  npm install
fi

npm run dev
