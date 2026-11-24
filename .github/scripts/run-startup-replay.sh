#!/usr/bin/env bash
set -euo pipefail
trap 'exit 2' ERR
mode=${1:-}
output=.mutations/evidence/startup-replay
package=com.slukhayka.audiobooks.debug
component="$package/com.slukhayka.audiobooks.MainActivity"
mkdir -p "$output"
adb wait-for-device
adb shell getprop > "$output/android-properties.txt"
if [[ "$mode" == resource ]]; then
  adb install -r -t .mutations/apks/probe.apk
  adb shell pm grant "$package" android.permission.POST_NOTIFICATIONS
  result=0
  for iteration in 1 2 3; do
    directory="$output/resource-$iteration"
    mkdir -p "$directory"
    rm -f "$directory/resource-verdict.json"
    adb logcat -b all -c
    timeout 90 adb shell am start -S -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$component" > "$directory/launch.txt" 2>&1
    sleep 1
    adb shell pidof "$package" > "$directory/live-pid.txt"
    adb logcat -b all -d -v threadtime Startup1742:I AndroidRuntime:E ActivityTaskManager:I ActivityManager:W ActivityThread:W libc:F DEBUG:F '*:S' > "$directory/logcat.txt"
    code=0
    python3 "$RUNNER_TEMP/startup-resource-verdict.py" "$directory" || code=$?
    if [[ "$code" -ne 0 && "$code" -ne 1 ]]; then result=2; elif [[ "$code" -eq 1 && "$result" -eq 0 ]]; then result=1; fi
  done
  exit "$result"
elif [[ "$mode" == method ]]; then
  for phase in startup idle; do
    directory="$output/method-$phase"
    remote="/data/local/tmp/replay-$phase.trace"
    mkdir -p "$directory"
    adb logcat -b all -c
    if [[ "$phase" == startup ]]; then
      timeout 90 adb shell am start -S -W --start-profiler "$remote" --sampling 1000 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$component" > "$directory/launch.txt" 2>&1
    else
      timeout 90 adb shell am start -S -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$component" > "$directory/launch.txt" 2>&1
      timeout 30 adb shell am profile start --sampling 1000 "$package" "$remote" > "$directory/profile-start.txt" 2>&1
    fi
    sleep 1
    adb shell pidof "$package" > "$directory/pid-before-stop.txt" || true
    code=0
    timeout 30 adb shell am profile stop "$package" > "$directory/profile-stop.txt" 2>&1 || code=$?
    printf '%s\n' "$code" > "$directory/stop-status.txt"
    sleep 3
    adb shell pidof "$package" > "$directory/pid-after-stop.txt" || true
    adb logcat -b crash -d -v threadtime > "$directory/crash-buffer.txt"
    adb logcat -b all -d -v threadtime Startup1742:I AndroidRuntime:E ActivityTaskManager:I ActivityManager:W ActivityThread:W libc:F DEBUG:F '*:S' > "$directory/logcat.txt"
    code=0
    adb pull "$remote" "$directory/method.trace" > "$directory/pull.txt" 2>&1 || code=$?
    printf '%s\n' "$code" > "$directory/pull-status.txt"
  done
  python3 - "$output" <<'PYCODE'
from pathlib import Path
import json,sys
root=Path(sys.argv[1]);records=[]
for phase in ['startup','idle']:
    p=root/('method-'+phase);trace=p/'method.trace';data=trace.read_bytes()if trace.exists()else b''
    records.append({'phase':phase,'traceBytes':len(data),'hasSlowHeader':b'*end\nSLOW'in data,'pidBeforeStop':(p/'pid-before-stop.txt').read_text().strip(),'pidAfterStop':(p/'pid-after-stop.txt').read_text().strip()})
(root/'method-diagnostic.json').write_text(json.dumps({'scope':'profiling-tool diagnostic; not a product resource verdict','records':records},indent=2)+'\n')
print(json.dumps(records))
PYCODE
elif [[ "$mode" == system ]]; then
  directory="$output/system"
  mkdir -p "$directory"
  adb logcat -b all -c
  timeout 30 adb shell perfetto --out /data/misc/perfetto-traces/startup-replay.pftrace --time 8s --buffer 16mb --app "$package" sched freq idle am wm gfx view binder_driver > "$directory/perfetto.txt" 2>&1 &
  recorder=$!
  sleep 1
  timeout 90 adb shell am start -S -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$component" > "$directory/launch.txt" 2>&1
  wait "$recorder"
  adb pull /data/misc/perfetto-traces/startup-replay.pftrace "$directory/startup.pftrace"
  adb logcat -b all -d -v threadtime Startup1742:I AndroidRuntime:E ActivityTaskManager:I ActivityThread:W '*:S' > "$directory/logcat.txt"
  test -s "$directory/startup.pftrace"
else
  printf '%s\n' 'expected resource, method, or system' >&2
  exit 2
fi
