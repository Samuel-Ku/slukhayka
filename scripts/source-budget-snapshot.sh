#!/usr/bin/env bash
# Acceptance helper for #527 / #528 / #529 / #533.
#
# The app keeps a token bucket per source host (ADR-0039) and persists it in
# `source_gate_budget`, so request counts can be read off the device with no
# instrumentation build.
#
# Careful: the persisted value is a snapshot from the moment of the LAST
# request, and refill is lazy (credited inside the admission path). The token
# delta alone is therefore a LOWER bound — a request that consumed a
# just-credited token leaves the count unchanged. `lastRefillAtMs` records the
# refill steps credited in the window, which turns the bound into the exact
# count.
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
REFILL_INTERVAL_MS="${REFILL_INTERVAL_MS:-10000}"

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
  python3 - "$DIR/before.xml" "$DIR/after.xml" "$REFILL_INTERVAL_MS" <<'PY'
import re, sys

def parse(path):
    """host -> (tokens, lastRefillAtMs) as persisted by the gate."""
    try:
        text = open(path, encoding="utf-8").read()
    except OSError:
        return {}
    out = {}
    for m in re.finditer(r'<string name="([^"]+)">([^<]*)</string>', text):
        raw = m.group(2)
        if "|" not in raw:
            continue
        tokens, refill = raw.split("|", 1)
        try:
            out[m.group(1)] = (int(tokens), int(refill))
        except ValueError:
            continue
    return out

before, after = parse(sys.argv[1]), parse(sys.argv[2])
interval = int(sys.argv[3])
if not before and not after:
    print("no snapshots yet — run `before` and `after` first")
    raise SystemExit(0)

print(f"{'host':38} {'tokens':>13} {'refills':>8} {'requests':>9}")
total = 0
for host in sorted(set(before) | set(after)):
    if host not in before or host not in after:
        continue
    b_tokens, b_refill = before[host]
    a_tokens, a_refill = after[host]
    steps = max(0, (a_refill - b_refill) // interval)
    spent = max(0, b_tokens + steps - a_tokens)
    if spent:
        total += spent
    print(f"{host:38} {b_tokens:>6} -> {a_tokens:<4} {steps:>8} {spent:>9}")
print(f"\nразом запитів: {total}")
print("(tokens — знімок на момент останнього запиту; refills — кроки, нараховані")
print(" під час вікна; requests = tokens_до + refills − tokens_після)")
PY
}

case "${1:-}" in
  before|after) snapshot "$1" ;;
  delta) delta ;;
  *) echo "usage: $0 before|after|delta" >&2; exit 2 ;;
esac
