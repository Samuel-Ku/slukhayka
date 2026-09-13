#!/usr/bin/env bash
# #784 — the SHIPPED license inventory: the resolved app classpath, not the cache.
#
# `dependency-licenses.sh` sweeps the whole Gradle cache, which is wider than
# the app (build tooling, plugins, CI). This one asks Gradle what actually
# resolves for the app, then reads those artifacts' declared licenses from the
# cache POMs. For AC1 the numbers below are the ones that matter.
set -euo pipefail

CONFIG="${CONFIG:-debugRuntimeClasspath}"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
TREE="${TREE:-/tmp/app-dependencies.txt}"

if [ ! -s "$TREE" ]; then
  echo "resolving :app:$CONFIG …" >&2
  ./gradlew :app:dependencies --configuration "$CONFIG" > "$TREE"
fi

python3 - "$TREE" "$CACHE" <<'PY'
import os, re, sys, collections

tree, cache = sys.argv[1], sys.argv[2]
COORD = re.compile(r"[|+\\\- ]*([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+)(?: -> ([A-Za-z0-9_.\-]+))?")

resolved = set()
for line in open(tree, encoding="utf-8", errors="replace"):
    if "---" not in line and "+" not in line:
        continue
    m = COORD.search(line)
    if m:
        version = m.group(4) or m.group(3)
        resolved.add(f"{m.group(1)}:{m.group(2)}:{version}")

ENTRY = re.compile(r"<license>(.*?)</license>", re.S)
NAME = re.compile(r"<name>(.*?)</name>", re.S)
COPYLEFT = re.compile(
    r"\b(A?GPL|LGPL|GPL)\b|General Public License|Affero", re.I
)

def licenses_for(group, artifact, version):
    base = os.path.join(cache, group, artifact, version)
    if not os.path.isdir(base):
        return None
    names = set()
    for root, _d, files in os.walk(base):
        for f in files:
            if not f.endswith(".pom"):
                continue
            try:
                text = open(os.path.join(root, f), encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            if "<licenses>" not in text:
                continue
            block = text.split("<licenses>", 1)[1].split("</licenses>", 1)[0]
            for entry in ENTRY.findall(block):
                m = NAME.search(entry)
                if m:
                    names.add(" ".join(m.group(1).split()))
    return names or None

by_license = collections.defaultdict(list)
unknown = []
copyleft = []
for coord in sorted(resolved):
    group, artifact, version = coord.split(":")
    names = licenses_for(group, artifact, version)
    if names is None:
        unknown.append(coord)
        continue
    for n in names:
        by_license[n].append(coord)
        if COPYLEFT.search(n):
            copyleft.append(coord)

print(f"розвʼязано артефактів у :app:{os.path.basename(tree)}: {len(resolved)}")
print(f"з них із відомою ліцензією: {len(resolved) - len(unknown)}")
print(f"без POM у кеші: {len(unknown)}\n")
for name in sorted(by_license, key=lambda k: (-len(by_license[k]), k)):
    flag = "  ← GPL-family" if COPYLEFT.search(name) else ""
    print(f"{name}  ({len(by_license[name])}){flag}")

print("\n--- GPL-family in the SHIPPED graph ---")
for coord in sorted(set(copyleft)):
    print("  " + coord)
if not copyleft:
    print("  none")
PY
