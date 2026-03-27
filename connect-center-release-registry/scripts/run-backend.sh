#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"

export DOTNET_CLI_HOME="${DOTNET_CLI_HOME:-/tmp/codex-dotnet-home}"
export DOTNET_SKIP_FIRST_TIME_EXPERIENCE="${DOTNET_SKIP_FIRST_TIME_EXPERIENCE:-1}"
export DOTNET_CLI_TELEMETRY_OPTOUT="${DOTNET_CLI_TELEMETRY_OPTOUT:-1}"
export NUGET_PACKAGES="${NUGET_PACKAGES:-/tmp/codex-nuget}"

mkdir -p "$DOTNET_CLI_HOME" "$NUGET_PACKAGES"

cd "$BACKEND_DIR"
exec dotnet run --project src/ConnectCenter.ReleaseRegistry.Api
