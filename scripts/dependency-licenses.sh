#!/usr/bin/env bash
# #784 — dependency license inventory, read from the Gradle module cache.
#
# The POM of every resolved artifact carries its declared license(s), so the
# report needs neither network nor a plugin. Useful BEFORE the NewPipeExtractor
# removal lands: it shows exactly what copyleft is still in the graph.
set -euo pipefail

CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"

python3 - "$CACHE" <<'PY'
import os, re, sys, collections

cache = sys.argv[1]
ENTRY = re.compile(r"<license>(.*?)</license>", re.S)
NAME = re.compile(r"<name>(.*?)</name>", re.S)
# Both spellings matter: NewPipeExtractor declares «GNU General Public
# License v3.0 or later» — written out, with no «GPL» acronym anywhere, so an
# acronym-only pattern would silently pass the very dependency this audit is
# about.
COPYLEFT = re.compile(
    r"\b(A?GPL|LGPL|GPL)\b"
    r"|General Public License"
    r"|Affero",
    re.I,
)

found = {}          # module -> set(licenses)
for root, _dirs, files in os.walk(cache):
    for f in files:
        if not f.endswith(".pom"):
            continue
        path = os.path.join(root, f)
        try:
            text = open(path, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        if "<licenses>" not in text:
            continue
        block = text.split("<licenses>", 1)[1].split("</licenses>", 1)[0]
        names = set()
        for entry in ENTRY.findall(block):
            m = NAME.search(entry)
            if m:
                names.add(" ".join(m.group(1).split()))
        if not names:
            continue
        # group/artifact/version from the cache layout
        parts = path[len(cache):].strip(os.sep).split(os.sep)
        module = "/".join(parts[:3]) if len(parts) >= 3 else path
        found.setdefault(module, set()).update(names)

if not found:
    print("no POMs with license metadata in", cache)
    raise SystemExit(0)

by_license = collections.defaultdict(list)
for module, names in found.items():
    for n in names:
        by_license[n].append(module)

print(f"артефактів із ліцензіями: {len(found)}\n")
copyleft = []
for license_name in sorted(by_license, key=lambda k: (-len(by_license[k]), k)):
    modules = sorted(by_license[license_name])
    flag = "  ← COPYLEFT" if COPYLEFT.search(license_name) else ""
    print(f"{license_name}  ({len(modules)}){flag}")
    if flag:
        copyleft.extend(modules)

print("\n--- GPL-family artifacts (must be gone before release) ---")
if copyleft:
    for module in sorted(set(copyleft)):
        print("  " + module)
else:
    print("  none found in the cache")
PY
