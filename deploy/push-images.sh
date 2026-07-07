#!/usr/bin/env bash
# Push the three connectCenter images at <version> to Docker Hub (oagi1docker).
# Requires: docker login (Docker Hub account with push rights to oagi1docker/*).
#
# Usage: deploy/push-images.sh [version]
set -euo pipefail
# shellcheck source=deploy/config.sh
source "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/config.sh"

VERSION="${1:-$(default_version)}"

command -v docker >/dev/null 2>&1 || die "docker not found."
if ! docker system info 2>/dev/null | grep -qi 'Username:'; then
  warn "Docker Hub login not detected. If push fails with 'denied', run: docker login"
fi

for tag in "$IMAGE_WEB:$VERSION" "$IMAGE_HTTP:$VERSION" "$IMAGE_REPO:$VERSION"; do
  docker image inspect "$tag" >/dev/null 2>&1 || die "image $tag not found locally -- run deploy/build-images.sh $VERSION first."
  log "docker push $tag"
  docker push "$tag"
done
log "Pushed $VERSION (web, http-gateway, repo)"
