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
import json, math, statistics
root = Path(sys.argv[1])
result = (root / 'instrumentation.txt').read_text()
log = (root / 'logcat.txt').read_text()
assert re.search(r'^OK \(1 test\)\s*$', result, re.M), result
assert re.search(r'^INSTRUMENTATION_CODE: -1\s*$', result, re.M), result
assert 'FAILURES!!!' not in result and 'INSTRUMENTATION_FAILED' not in result, result
for name in ['edition-memo']:
    assert len(re.findall(r'PerfLoop\s*: END ' + re.escape(name) + r'\s*$', log, re.M)) == 1, name
assert log.count('ALL_BENCHMARKS_DONE') == 1, log
comparisons = []
for name in ['hot20', 'hot1000', 'local-settled20', 'churn5000', 'distinct20000']:
    pattern = re.escape(name) + r' referenceMs=([0-9.eE+-]+) candidateMs=([0-9.eE+-]+) speedup=([0-9.eE+-]+) before=(\[.*?\]) after=(\[.*?\])'
    matches = re.findall(pattern, log)
    assert len(matches) == 1, (name, matches)
    before, after, ratio, left, right = matches[0]
    left, right = json.loads(left), json.loads(right)
    assert len(left) == len(right) == 9
    assert all(math.isfinite(x) and x > 0 for x in left + right)
    assert math.isclose(float(before), statistics.median(left), rel_tol=1e-9)
    assert math.isclose(float(after), statistics.median(right), rel_tol=1e-9)
    assert math.isclose(float(ratio), float(before) / float(after), rel_tol=1e-9)
    comparisons.append({'scenario': name, 'referenceMs': float(before), 'candidateMs': float(after), 'speedup': float(ratio), 'before': left, 'after': right})
assert log.count('12000 independent SHA output comparisons passed') == 1
assert len(re.findall(r'checksum=-?\d+', log)) == 1
pids = re.findall(r'\s(\d+)\s+\d+\s+I\s+PerfLoop\s*:\s+ENV ', log)
assert len(pids) == 1, pids
(root / 'comparison-summary.json').write_text(json.dumps({'pid': int(pids[0]), 'comparisons': comparisons}, indent=2) + '\n')
print(root.name + ': real instrumentation passed; five memo workloads validated')
PYCODE
done

python3 - "$output" <<'PYCODE'
from pathlib import Path
import json,sys
root=Path(sys.argv[1])
records=[json.loads((root/f'run-{i}'/'comparison-summary.json').read_text())for i in range(1,4)]
assert len({r['pid']for r in records})==3
(root/'three-process-summary.json').write_text(json.dumps(records,indent=2)+'\n')
PYCODE
