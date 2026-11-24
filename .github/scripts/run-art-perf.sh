#!/usr/bin/env bash
set -euo pipefail

output=.mutations/evidence/art-perf
mkdir -p "$output"
adb wait-for-device
adb devices -l > "$output/devices.txt"
adb shell getprop > "$output/android-properties.txt"
app_apk=app/build/outputs/apk/debug/app-debug.apk
test_apk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
sha256sum "$app_apk" "$test_apk" > "$output/apk-sha256.txt"
adb install -r -t "$app_apk"
adb install -r -t "$test_apk"

for iteration in 1 2 3; do
  directory="$output/run-$iteration"
  mkdir -p "$directory"
  adb shell am force-stop com.slukhayka.audiobooks.debug
  adb logcat -c
  status=0
  timeout 900 adb shell am instrument -w -r \
    -e class com.slukhayka.audiobooks.performance.PerformanceLoopBenchmarkTest \
    com.slukhayka.audiobooks.debug.test/com.slukhayka.audiobooks.IsolatedDatabaseTestRunner \
    > "$directory/instrumentation.txt" 2>&1 || status=$?
  cat "$directory/instrumentation.txt"
  adb logcat -d -v threadtime PerfLoop:I System.out:I AndroidRuntime:E TestRunner:I '*:S' > "$directory/logcat.txt"
  if [[ "$status" -ne 0 ]]; then
    exit "$status"
  fi
  python3 - "$directory" <<'PYCODE'
from pathlib import Path
import re
import sys
root = Path(sys.argv[1])
result = (root / 'instrumentation.txt').read_text()
log = (root / 'logcat.txt').read_text()
assert re.search(r'^OK \(1 test\)\s*$', result, re.M), result
assert re.search(r'^INSTRUMENTATION_CODE: -1\s*$', result, re.M), result
assert 'FAILURES!!!' not in result and 'INSTRUMENTATION_FAILED' not in result, result
for name in ['merge-key', 'source-hosts', 'collection-membership', 'edition-id', 'strip-tags', 'entity-decoder', 'claimed-text']:
    assert len(re.findall(r'PerfLoop\s*: END ' + re.escape(name) + r'\s*$', log, re.M)) == 1, name
assert log.count('ALL_BENCHMARKS_DONE') == 1, log
print(root.name + ': real instrumentation passed; all seven comparison probes completed')
PYCODE
done
