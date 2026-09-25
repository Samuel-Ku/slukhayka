#!/usr/bin/env bash
#
# The CI door for the instrumented suites (#852, #1017).
#
# CI runs the same five classes the local door runs
# (`scripts/run-instrumented-suites.sh`), one gradle invocation per class.
# Per class, not one run for all five, because every instrumentation run gets
# its own database: `IsolatedDatabaseTestRunner` deletes the scratch file in
# `onCreate`. Sharing one run would let `MainActivityAccessibilityTest` — which
# deliberately wipes and reseeds the database — decide another class's
# fixtures.
#
# The list below is the executed set `InstrumentedCoverageGuardTest` (#984)
# asserts against: a class in neither this list nor the guard's known-debt
# list fails the JVM build, so the run cannot silently shrink again.

set -uo pipefail

report_dir="app/build/reports/androidTests"
mkdir -p "$report_dir"
# #408: deterministic 3-button navigation for the emulator journey — TalkBack
# ordering depends on the nav mode; force it before the test run.
adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton
adb shell cmd overlay list | grep -F "[x] com.android.internal.systemui.navbar.threebutton"
adb logcat -c

# #424: the APKs are prebuilt before the emulator starts; wait until Android
# package service answers instead of racing it (the #424 readiness contract).
scripts/wait-for-android-package-service.sh

suites=(
  com.slukhayka.audiobooks.accessibility.MainActivityAccessibilityTest
  com.slukhayka.audiobooks.audio.AudioPlaybackEspressoTest
  com.slukhayka.audiobooks.accessibility.UiSurfaceAuditTest
  com.slukhayka.audiobooks.accessibility.SettingsNavigationTest
  com.slukhayka.audiobooks.accessibility.BottomBarLargeTextLayoutTest
)

failed=()
for suite in "${suites[@]}"; do
  printf '=== %s\n' "$suite"
  if ./gradlew \
    :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$suite" \
    --no-daemon \
    --max-workers=1 \
    --stacktrace > "$report_dir/${suite##*.}.log" 2>&1; then
    printf '    green\n'
  else
    printf '    RED — tail of %s/%s.log\n' "$report_dir" "${suite##*.}"
    tail -n 200 "$report_dir/${suite##*.}.log"
    failed+=("$suite")
  fi
done

adb logcat -d > "$report_dir/accessibility-api35-logcat.txt" || true

if [[ ${#failed[@]} -gt 0 ]]; then
  printf '%d of %d suite(s) failed:\n' "${#failed[@]}" "${#suites[@]}" >&2
  printf '  %s\n' "${failed[@]}" >&2
  exit 1
fi

printf 'all %d suite(s) green\n' "${#suites[@]}"
