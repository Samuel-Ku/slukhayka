#!/usr/bin/env bash
# Acceptance helper for #527 / #528 / #529 / #533.
#
# The app keeps a token bucket per source host (ADR-0039) and persists it in
# `source_gate_budget`. The delta in `tokens` between two snapshots is the
# number of requests the app actually made — an exact count, with no
# instrumentation build.
#
# Usage:
#   scripts/source-budget-snapshot.sh before
#   … do the one action in the UI …
#   scripts/source-budget-snapshot.sh after
#   scripts/source-budget-snapshot.sh delta
#
# PKG overrides the package (default: the debug build).
set -euo pipefail

PKG="${PKG:-com.slukhayka.audiobooks.debug}"
DIR="${DIR:-/tmp/source-budget}"
PREFS="shared_prefs/source_gate_budget.xml"

mkdir -p "$DIR"

snapshot() {
  local name="$1"
  if ! adb exec-out "run-as $PKG cat $PREFS" > "$DIR/$name.xml" 2>/dev/null; then
    echo "cannot read $PREFS from $PKG (is the app installed and debuggable?)" >&2
    exit 1
  fi
  if [ ! -s "$DIR/$name.xml" ]; then
    echo "empty snapshot — the gate has not spent anything yet" >&2
  fi
  echo "saved $DIR/$name.xml"
}

delta() {
  python3 - "$DIR/before.xml" "$DIR/after.xml" <<'PY'
import re, sys

def parse(path):
    try:
        text = open(path, encoding="utf-8").read()
    except OSError:
        return {}
    # <string name="host">tokens|lastRefillAtMs</string>
    return {
        m.group(1): m.group(2)
        for m in re.finditer(r'<string name="([^"]+)">([^<]*)</string>', text)
    }

before, after = parse(sys.argv[1]), parse(sys.argv[2])
if not before and not after:
    print("no snapshots yet — run `before` and `after` first")
    raise SystemExit(0)

print(f"{'host':38} {'before':>10} {'after':>10} {'requests':>9}")
total = 0
for host in sorted(set(before) | set(after)):
    def tokens(value):
        return int(value.split("|")[0]) if value and "|" in value else None
    b, a = tokens(before.get(host)), tokens(after.get(host))
    if b is None or a is None:
        continue
    spent = b - a
    if spent:
        total += spent
    print(f"{host:38} {b:>10} {a:>10} {spent:>9}")
print(f"\nразом запитів: {total}")
print("якщо refill стався під час виміру, частина витрат могла бути компенсована —")
print("перевірте другу половину значення (lastRefillAtMs) у снапшотах.")
PY
}

case "${1:-}" in
  before|after) snapshot "$1" ;;
  delta) delta ;;
  *) echo "usage: $0 before|after|delta" >&2; exit 2 ;;
esac
