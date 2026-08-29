#!/usr/bin/env bash

set -uo pipefail

report_dir="app/build/reports/androidTests"
mkdir -p "$report_dir"
# #408: deterministic 3-button navigation for the emulator journey — TalkBack
# ordering depends on the nav mode; force it before the test run.
adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton
adb shell cmd overlay list | grep -F "[x] com.android.internal.systemui.navbar.threebutton"
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
