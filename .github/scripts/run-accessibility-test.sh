#!/usr/bin/env bash

set -uo pipefail

report_dir="app/build/reports/androidTests"
mkdir -p "$report_dir"
adb logcat -c

test_status=0
./gradlew \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.slukhayka.audiobooks.accessibility.MainActivityAccessibilityTest \
  --no-daemon \
  --max-workers=1 \
  --stacktrace || test_status=$?

adb logcat -d > "$report_dir/accessibility-api35-logcat.txt" || true
exit "$test_status"
