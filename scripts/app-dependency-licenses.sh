#!/usr/bin/env bash
# #784 — the SHIPPED license inventory: the resolved app classpath, not the cache.
#
# `dependency-licenses.sh` sweeps the whole Gradle cache, which is wider than
# the app (build tooling, plugins, CI). This one asks Gradle what actually
# resolves for the app, then reads those artifacts' declared licenses from POMs:
# the cache first, then Google Maven / Maven Central / JitPack (#1355). Gradle 9
# keeps jars and aars in `modules-2/files-2.1`, not the POMs, so a cache-only
# reader found no license at all and still printed `GPL-family: none`. The
# reader now names every unchecked artifact UNKNOWN and prints `none` only when
# all coordinates were checked and none is copyleft. `APP_LICENSE_OFFLINE=1`
# keeps it to cached POMs; unresolved coordinates stay UNKNOWN there too.
# For AC1 the numbers below are the ones that matter.
set -euo pipefail

CONFIG="${CONFIG:-debugRuntimeClasspath}"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
TREE="${TREE:-/tmp/app-dependencies.txt}"

if [ ! -s "$TREE" ]; then
  echo "resolving :app:$CONFIG …" >&2
  ./gradlew :app:dependencies --configuration "$CONFIG" > "$TREE"
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

arguments=(--tree "$TREE" --cache "$CACHE")
if [ "${APP_LICENSE_OFFLINE:-0}" = "1" ]; then
  arguments+=(--offline)
fi

exec python3 "$SCRIPT_DIR/app_dependency_licenses.py" "${arguments[@]}"
