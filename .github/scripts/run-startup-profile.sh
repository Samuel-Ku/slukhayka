#!/usr/bin/env bash
set -euo pipefail

mode=${1:?measure or guard}
output=.mutations/evidence/startup-profile
package=com.slukhayka.audiobooks.debug
component="$package/com.slukhayka.audiobooks.MainActivity"
mkdir -p "$output"
adb wait-for-device
adb shell getprop > "$output/android-properties.txt"
adb shell am help > "$output/am-help.txt"

if [[ "$mode" == measure ]]; then
  adb install -r -t .mutations/apks/plain.apk
  adb shell am force-stop "$package"
  timeout 90 adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$component" > "$output/initial-launch.txt" 2>&1
  # Let the one-time seed settle; it is not one of the measured process-cold runs.
  sleep 10
  for iteration in 1 2 3 4 5 6 7 8 9; do
    adb shell am force-stop "$package"
    timeout 90 adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$component" > "$output/cold-$iteration.txt" 2>&1
    sleep 1
  done
  python3 - "$output" <<'PYCODE'
from pathlib import Path
import json, re, statistics, sys
root = Path(sys.argv[1]); records = []
for file in sorted(root.glob('cold-*.txt')):
    text = file.read_text()
    assert re.search(r'^Status: ok\s*$', text, re.M), text
    assert re.search(r'^LaunchState: COLD\s*$', text, re.M), text
    match = re.search(r'^TotalTime: (\d+)\s*$', text, re.M)
    assert match and int(match[1]) > 0, text
    records.append({'file': file.name, 'totalTimeMs': int(match[1])})
assert len(records) == 9
report = {'scope': 'unmodified debug APK, API35x86_64, process-cold with warmed installation/data',
          'records': records, 'medianTotalTimeMs': statistics.median(r['totalTimeMs'] for r in records)}
(root / 'plain-timings.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report))
PYCODE
elif [[ "$mode" == guard ]]; then
  adb install -r -t .mutations/apks/probe.apk
  adb shell am force-stop "$package"
  adb logcat -c
  status=0
  timeout 90 adb shell am start -W --start-profiler /data/local/tmp/startup-1742.trace --sampling 1000 \
    -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$component" > "$output/profile-launch.txt" 2>&1 || status=$?
  sleep 1
  adb shell am profile stop "$package" > "$output/profile-stop.txt" 2>&1 || status=$?
  adb logcat -d -v threadtime Startup1742:I AndroidRuntime:E ActivityTaskManager:I '*:S' > "$output/probe-logcat.txt"
  adb pull /data/local/tmp/startup-1742.trace "$output/startup.trace" || status=$?
  if [[ "$status" -ne 0 ]]; then exit 2; fi
  python3 - "$output" "${GUARD_KIND:-BOTH}" <<'PYCODE'
from pathlib import Path
import json, re, sys
root = Path(sys.argv[1]); focus = sys.argv[2]
assert focus in ['BOTH', 'ROOM', 'COIL']
launch = (root / 'profile-launch.txt').read_text()
log = (root / 'probe-logcat.txt').read_text()
trace = (root / 'startup.trace').read_bytes()
if not re.search(r'^Status: ok\s*$', launch, re.M) or 'event=FIRST_DRAW_END' not in log or b'*end\nSLOW' not in trace:
    print('Infrastructure/trace incomplete; no performance verdict', file=sys.stderr)
    sys.exit(2)
records = []
for line in log.splitlines():
    m = re.search(r'END kind=(ROOM|COIL) main=(true|false) beforeFirstDraw=(true|false) wallNs=(\d+) cpuNs=(\d+)', line)
    if m:
        records.append({'kind': m[1], 'main': m[2] == 'true', 'beforeFirstDraw': m[3] == 'true',
                        'wallMs': int(m[4]) / 1e6, 'cpuMs': int(m[5]) / 1e6})
assert records, 'no actual resource constructor observed'
violations = [r for r in records if r['main'] and r['beforeFirstDraw']]
failing = [r for r in violations if focus == 'BOTH' or r['kind'] == focus]
report = {'guardKind': focus, 'allConstructors': records, 'allViolations': violations,
          'failingViolations': failing, 'outcome': 'red-resource-init-on-main-before-draw' if failing else 'green',
          'scope': 'resource/thread guard on diagnostic APK; latency is measured separately on the unmodified APK'}
(root / 'guard-verdict.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report))
sys.exit(1 if failing else 0)
PYCODE
else
  printf '%s\n' 'expected measure or guard' >&2
  exit 2
fi
