#!/usr/bin/env bash
set -u

attempts=${ANDROID_PACKAGE_READY_ATTEMPTS:-60}
sleep_seconds=${ANDROID_PACKAGE_READY_SLEEP_SECONDS:-2}
device_timeout_seconds=${ANDROID_DEVICE_READY_TIMEOUT_SECONDS:-120}

adb wait-for-device &
adb_wait_pid=$!
device_deadline=$((SECONDS + device_timeout_seconds))
while kill -0 "$adb_wait_pid" 2>/dev/null; do
  if ((SECONDS >= device_deadline)); then
    kill "$adb_wait_pid" 2>/dev/null || true
    wait "$adb_wait_pid" 2>/dev/null || true
    printf '%s\n' "Android device did not become available within ${device_timeout_seconds}s." >&2
    adb devices -l >&2 || true
    exit 1
  fi
  sleep 1
done
if ! wait "$adb_wait_pid"; then
  printf '%s\n' "Android device did not become available within ${device_timeout_seconds}s." >&2
  adb devices -l >&2 || true
  exit 1
fi
for ((attempt = 1; attempt <= attempts; attempt += 1)); do
  boot_completed=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  package_service=$(adb shell service check package 2>/dev/null | tr -d '\r' || true)
  if [[ $boot_completed == "1" ]] \
    && grep -Fqx "Service package: found" <<<"$package_service" \
    && adb shell pm path android >/dev/null 2>&1; then
    printf '%s\n' "Android package service is ready."
    exit 0
  fi
  if (( attempt < attempts )); then
    sleep "$sleep_seconds"
  fi
done

printf '%s\n' "Android package service did not become ready after $attempts checks." >&2
adb devices -l >&2 || true
adb shell getprop >&2 || true
exit 1
