#!/usr/bin/env bash
#
# Pre-release readiness gate. Run it on the commit you intend to tag:
#
#   ./scripts/pre-release.sh
#
# It answers one question — "is this commit safe to tag?" — with three checks:
#   1. open dependency-update PRs (Renovate/Dependabot) that were never reviewed;
#   2. vendored components (the BotGuard po_token asset once it is ported);
#   3. the live YouTube contract canary against the pinned engine.
#
# Only the canary is a hard gate. Dependency drift and missing vendor locks are
# reported loudly but do not block: they are judgement calls for the maintainer.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

status=0
ok()   { printf 'OK    %s\n' "$*"; }
warn() { printf 'WARN  %s\n' "$*"; }
fail() { printf 'FAIL  %s\n' "$*" >&2; status=1; }

echo "== Pre-release readiness =="

# 1. Dependency updates sitting unreviewed.
if command -v gh >/dev/null 2>&1; then
  deps_count="$(gh pr list --state open --label dependencies --limit 100 \
    --json number --jq 'length' 2>/dev/null || echo 0)"
  if [ "${deps_count:-0}" -gt 0 ]; then
    warn "open dependency PR(s): $deps_count — review before tagging:"
    gh pr list --state open --label dependencies --limit 100 \
      --json number,title --jq '.[] | "      #\(.number) \(.title)"' 2>/dev/null || true
  else
    ok "no open dependency PRs"
  fi
else
  warn "gh not available — skipping the dependency-PR check"
fi

# 2. Vendored components. The BotGuard po_token asset is not ported yet, so
#    today there is nothing pinned; once there is, vendor-sync owns the lock.
if compgen -G "vendor/*.lock" >/dev/null 2>&1; then
  warn "vendor lock(s) present — verify freshness by hand (vendor-sync not built yet)"
else
  ok "no vendored components pinned yet"
fi

# 3. The live YouTube contract canary — the hard gate.
if ./gradlew :app:testDebugUnitTest --tests "*YouTubeContractCanaryTest" \
      -Dyoutube.canary=1 --no-daemon; then
  ok "YouTube contract canary is green"
else
  fail "YouTube contract canary is red — bump the engine (NewPipe) before tagging"
fi

echo
if [ "$status" -ne 0 ]; then
  echo "RELEASE NOT READY"
  exit 1
fi
echo "RELEASE READY"
