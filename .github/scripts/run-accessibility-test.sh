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

# Keep each attempt outside AGP's replaceable connected reports. The suffix
# preserves earlier evidence if this helper is invoked again with the same label.
attempt=${1:-1}
attempt_dir=""
if mkdir -p app/build/accessibility-diagnostics; then
  attempt_dir=$(mktemp -d "app/build/accessibility-diagnostics/attempt-${attempt}.XXXXXX") || true
fi
if [[ -n "$attempt_dir" ]]; then
  {
    printf 'attempt=%s\nrunId=%s\nrunAttempt=%s\n' "$attempt" "${GITHUB_RUN_ID:-local}" "${GITHUB_RUN_ATTEMPT:-local}"
    printf 'checkout='; git rev-parse HEAD
    printf 'tree='; git rev-parse 'HEAD^{tree}'
  } > "$attempt_dir/invocation.txt" || printf '::warning::could not record attempt metadata\n'
else
  printf '::warning::could not create an attempt diagnostics directory\n'
fi

capture_suite() {
  local suite=$1 started=$2 ended=$3 status=$4
  local destination="$attempt_dir/${suite##*.}" source name capture_exit
  [[ -n "$attempt_dir" ]] || return 0
  if ! mkdir "$destination"; then
    printf '::warning::could not create diagnostics for %s\n' "$suite"
    return 0
  fi
  printf 'requestedClass=%s\ngradleStartedAt=%s\ngradleEndedAt=%s\ngradleExit=%s\n' \
    "$suite" "$started" "$ended" "$status" > "$destination/status.txt" || \
    printf '::warning::could not record the Gradle status for %s\n' "$suite"
  if cp -p "$report_dir/${suite##*.}.log" "$destination/gradle.log"; then
    printf 'gradleLog=recorded\n' >> "$destination/capture-status.txt"
  else
    printf 'gradleLog=copy-failed\n' >> "$destination/capture-status.txt"
    printf '::warning::could not copy the full log for %s\n' "$suite"
  fi
  if timeout 10s adb logcat -d > "$destination/logcat.txt" 2> "$destination/logcat-stderr.txt"; then
    printf 'logcatExit=0\n' >> "$destination/capture-status.txt"
  else
    capture_exit=$?
    printf 'logcatExit=%s\n' "$capture_exit" >> "$destination/capture-status.txt"
    printf '::warning::logcat capture failed for %s (exit %s)\n' "$suite" "$capture_exit"
  fi
  # These are raw outputs present after the requested invocation. An early
  # Gradle failure can leave XML from an earlier class; copying is not proof
  # that this class ran. Preserve mtimes and the full output tree for review.
  for name in connected-results connected-reports; do
    if [[ "$name" == connected-results ]]; then
      source=app/build/outputs/androidTest-results/connected
    else
      source="$report_dir/connected"
    fi
    if [[ ! -d "$source" ]]; then
      printf '%s=absent source=%s\n' "$name" "$source" >> "$destination/capture-status.txt"
    elif timeout 10s cp -pR "$source" "$destination/$name"; then
      printf '%s=recorded source=%s\n' "$name" "$source" >> "$destination/capture-status.txt"
    else
      capture_exit=$?
      printf '%s=copy-failed exit=%s source=%s\n' "$name" "$capture_exit" "$source" >> "$destination/capture-status.txt"
      printf '::warning::could not copy %s for %s\n' "$name" "$suite"
    fi
  done
}
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
  suite_started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  if ./gradlew \
    :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$suite" \
    --no-daemon \
    --max-workers=1 \
    --stacktrace > "$report_dir/${suite##*.}.log" 2>&1; then
    suite_exit=0
    printf '    green\n'
  else
    suite_exit=$?
    printf '    RED — tail of %s/%s.log\n' "$report_dir" "${suite##*.}"
    tail -n 200 "$report_dir/${suite##*.}.log"
    failed+=("$suite")
  fi
  capture_suite "$suite" "$suite_started" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$suite_exit"
done

if adb logcat -d > "$report_dir/accessibility-api35-logcat.txt"; then
  final_logcat_exit=0
else
  final_logcat_exit=$?
fi
if [[ -n "$attempt_dir" ]]; then
  printf 'finalLogcatExit=%s\nfailedSuites=%s\n' "$final_logcat_exit" "${#failed[@]}" > "$attempt_dir/attempt-status.txt" || \
    printf '::warning::could not record the final attempt status\n'
  cp -p "$report_dir/accessibility-api35-logcat.txt" "$attempt_dir/attempt-logcat.txt" || \
    printf '::warning::could not copy the final attempt logcat\n'
fi

if [[ ${#failed[@]} -gt 0 ]]; then
  printf '%d of %d suite(s) failed:\n' "${#failed[@]}" "${#suites[@]}" >&2
  printf '  %s\n' "${failed[@]}" >&2
  exit 1
fi

printf 'all %d suite(s) green\n' "${#suites[@]}"
