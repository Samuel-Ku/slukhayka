#!/usr/bin/env bash
#
# #852 — one command for the instrumented suites that guard the UI surfaces.
#
# CI executes exactly ONE instrumented class
# (.github/scripts/run-accessibility-test.sh passes a single `class=` filter);
# the rest are compiled by every CI run and executed by none. That debt is
# pinned deliberately in InstrumentedCoverageGuardTest (#984) — widening the CI
# run is a CI-cost decision, not a code one.
#
# This script is the local door: it runs the suites that are already green, one
# class at a time, on an attached device or emulator, and fails if any of them
# is red. The end-to-end recipe (KVM, AVD, why the classes need a content-free
# host) lives in docs/runbooks/instrumented-suites.md.
#
# Usage:
#   scripts/run-instrumented-suites.sh                  # the known-green set
#   scripts/run-instrumented-suites.sh <FQCN> [<FQCN>…] # a custom selection

set -u

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
gradlew=${SLUKHAYKA_GRADLEW:-"$repo_root/gradlew"}
log_dir=${SLUKHAYKA_INSTRUMENTED_LOG_DIR:-/tmp}

default_suites=(
  com.slukhayka.audiobooks.accessibility.MainActivityAccessibilityTest
  com.slukhayka.audiobooks.audio.AudioPlaybackEspressoTest
  com.slukhayka.audiobooks.accessibility.UiSurfaceAuditTest
  com.slukhayka.audiobooks.accessibility.SettingsNavigationTest
  com.slukhayka.audiobooks.accessibility.BottomBarLargeTextLayoutTest
)

suites=("$@")
if [[ ${#suites[@]} -eq 0 ]]; then
  suites=("${default_suites[@]}")
fi

command -v adb >/dev/null 2>&1 || {
  printf 'adb not found on PATH — install platform-tools first\n' >&2
  exit 2
}

if [[ -z "$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')" ]]; then
  printf 'no device or emulator in "device" state.\n' >&2
  printf 'Start one first — see docs/runbooks/instrumented-suites.md\n' >&2
  adb devices -l >&2 || true
  exit 2
fi

# CI parity (.github/scripts/run-accessibility-test.sh): TalkBack ordering
# depends on the navigation mode, and animations race the scene transitions.
adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton >/dev/null 2>&1 || true
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb shell settings put global "$scale" 0 >/dev/null 2>&1 || true
done

failed=()
for suite in "${suites[@]}"; do
  log="$log_dir/instrumented-${suite##*.}.log"
  printf '%-64s ' "${suite##*.}"
  if "$gradlew" :app:connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class="$suite" >"$log" 2>&1; then
    printf 'PASS\n'
  else
    printf 'FAIL\n'
    failed+=("$suite")
  fi
done

if [[ ${#failed[@]} -gt 0 ]]; then
  printf '\n%d of %d suite(s) failed:\n' "${#failed[@]}" "${#suites[@]}" >&2
  printf '  %s\n' "${failed[@]}" >&2
  printf 'logs: %s/instrumented-<Class>.log\n' "$log_dir" >&2
  exit 1
fi

printf '\nall %d suite(s) green\n' "${#suites[@]}"
