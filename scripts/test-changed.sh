#!/usr/bin/env bash
set -u

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
selector="$repo_root/scripts/test_partitions.py"
base_ref=origin/main
changed_files=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base)
      [[ $# -ge 2 ]] || { printf '%s\n' "--base requires a Git ref" >&2; exit 2; }
      base_ref=$2
      shift 2
      ;;
    --changed-file)
      [[ $# -ge 2 ]] || { printf '%s\n' "--changed-file requires a path" >&2; exit 2; }
      changed_files+=("$2")
      shift 2
      ;;
    *)
      printf '%s\n' "Usage: scripts/test-changed.sh [--base <git-ref>] [--changed-file <path>]..." >&2
      exit 2
      ;;
  esac
done

scratch=$(mktemp -d "${TMPDIR:-/tmp}/slukhayka-changed-tests.XXXXXX") || exit 2
cleanup() {
  rm -rf -- "$scratch"
}
trap cleanup EXIT HUP INT TERM

if [[ ${#changed_files[@]} -eq 0 ]]; then
  cd "$repo_root" || exit 2
  if ! git rev-parse --verify "$base_ref^{commit}" >/dev/null 2>&1; then
    printf '%s\n' "Base ref '$base_ref' is unavailable; running the full suite." >&2
    exec "$repo_root/scripts/test-all.sh"
  fi
  git diff --name-only "$base_ref" -- > "$scratch/changed-files"
  git ls-files --others --exclude-standard >> "$scratch/changed-files"
  while IFS= read -r path; do
    [[ -n "$path" ]] && changed_files+=("$path")
  done < "$scratch/changed-files"
fi

selector_arguments=("$selector" --root "$repo_root" select)
for path in "${changed_files[@]}"; do
  selector_arguments+=(--changed-file "$path")
done
selection=$(python3 "${selector_arguments[@]}") || exit $?

if [[ $(python3 -c 'import json,sys; print("yes" if json.loads(sys.argv[1])["fullSuite"] else "no")' "$selection") == yes ]]; then
  reason=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["reason"])' "$selection")
  printf '%s\n' "Changed-test selection fell back to the full suite: $reason"
  exec "$repo_root/scripts/test-all.sh"
fi

python3 -c '
import json, sys
payload = json.loads(sys.argv[1])
for partition, classes in payload["partitions"].items():
    print(partition + "\t" + " ".join(classes))
' "$selection" > "$scratch/selection"

while IFS="$(printf '\t')" read -r partition classes; do
  case "$partition" in
    pure-jvm) task=:app:testPureJvm ;;
    room-native-sdk35) task=:app:testRoomNativeSdk35 ;;
    room-native-sdk36) task=:app:testRoomNativeSdk36 ;;
    room-native-default) task=:app:testRoomNativeDefault ;;
    room-robolectric-only) task=:app:testRoomRobolectricOnly ;;
    compose-roborazzi) task=:app:testComposeRoborazzi ;;
    *) printf '%s\n' "Unknown test partition: $partition" >&2; exit 2 ;;
  esac
  printf '%s\n' :app:validateTestPartitions "$task" > "$scratch/gradle-arguments"
  selected_classes=$(printf '%s' "$classes" | tr ' ' ',')
  printf '%s\n' "-Ptest.selectedClasses=$selected_classes" >> "$scratch/gradle-arguments"
  SLUKHAYKA_GRADLE_ARGS_FILE="$scratch/gradle-arguments" "$repo_root/scripts/test-all.sh" || exit $?
done < "$scratch/selection"
