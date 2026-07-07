#!/usr/bin/env bash
# Build the three connectCenter images at the given version (default: score-http/VERSION).
# Wraps each module's own docker/build.sh, which already tag oagi1docker/srt-*:<version>.
#
# Prereqs (checked by the underlying build.sh scripts):
#   - docker running; on Apple Silicon builds are emulated linux/amd64 (slow but correct)
#   - score-web: Node 22 (via nvm) + prebuilt docs at docs/user_guide/_build/html
#   - score-http: Maven wrapper + mariadb JDBC driver in ~/.m2
#
# Usage: deploy/build-images.sh [version]
set -euo pipefail
# shellcheck source=deploy/config.sh
source "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/config.sh"

VERSION="${1:-$(default_version)}"
export DOCKER_DEFAULT_PLATFORM="${DOCKER_DEFAULT_PLATFORM:-linux/amd64}"

command -v docker >/dev/null 2>&1 || die "docker not found."
docker info >/dev/null 2>&1 || die "docker daemon not reachable (is Docker Desktop running?)."

log "Building images for $VERSION (platform $DOCKER_DEFAULT_PLATFORM)"

# repo first (fast), then http (Maven), then web (Angular).
log "[1/3] srt-repo"
sh "$REPO_ROOT/score-repo/docker/build.sh" "$VERSION"
log "[2/3] srt-http-gateway"
sh "$REPO_ROOT/score-http/docker/build.sh" "$VERSION"
log "[3/3] srt-web"
sh "$REPO_ROOT/score-web/docker/build.sh" "$VERSION"

log "Built:"
docker images --format '  {{.Repository}}:{{.Tag}}\t{{.ID}}\t{{.Size}}' \
  | grep -E "oagi1docker/srt-(web|http-gateway|repo):${VERSION}$" || true
