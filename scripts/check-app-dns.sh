#!/usr/bin/env bash
# Repeatable in-app-context DNS check for #506.
#
# Compares hostname resolution for an audio host across three contexts on the
# SAME device and network:
#   1. plain ADB shell (shell UID — reference),
#   2. run-as APP UID (closest to the app process without rebuilding),
#   3. the device Private DNS posture (strict DoT breaks app-UID resolution
#      when the network blocks port 853, while direct :53 tools keep working).
#
# Usage: ./scripts/check-app-dns.sh [package] [host]
#   package defaults to the debug app, host to the 4read audio CDN.
# Exit 0 when all three agree (all resolve or all fail); exit 1 on split.
set -u

PKG="${1:-com.slukhayka.audiobooks.debug}"
HOST="${2:-s1.reasd.org}"

say() { printf '%s\n' "$*"; }

if ! adb devices 2>/dev/null | grep -q $'\tdevice$'; then
  say "FAIL: no adb device attached"
  exit 2
fi

say "== Private DNS posture =="
say "mode: $(adb shell settings get global private_dns_mode 2>/dev/null | tr -d '\r')"
say "specifier: $(adb shell settings get global private_dns_specifier 2>/dev/null | tr -d '\r')"

say "== 1. shell UID =="
if adb shell "ping -c1 -W3 $HOST" 2>&1 | grep -q "bytes from"; then
  SHELL_OK=1; say "shell: RESOLVES"
else
  SHELL_OK=0; say "shell: FAILS"
fi

say "== 2. app UID (run-as $PKG) =="
if adb shell "run-as $PKG ping -c1 -W3 $HOST" 2>&1 | grep -q "bytes from"; then
  APP_OK=1; say "run-as: RESOLVES"
else
  APP_OK=0; say "run-as: FAILS"
fi

say "== 3. plain :53 (nslookup, bypasses netd DoT) =="
if adb shell "nslookup $HOST" 2>&1 | grep -q "Address 1:"; then
  say "nslookup: RESOLVES"
else
  say "nslookup: FAILS"
fi

if [ "$SHELL_OK" = "$APP_OK" ]; then
  say "AGREE: shell and app UID match (both ${SHELL_OK:+resolve})"
  exit 0
fi

say "SPLIT: shell resolves but the app UID does not."
say "Known cause (#506): strict Private DNS (hostname mode) with port 853"
say "blocked on this network. Verified recovery without a code patch:"
say "  adb shell settings put global private_dns_mode off   # or opportunistic"
say "then re-run this script; restore the user's value afterwards."
exit 1
