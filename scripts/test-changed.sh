#!/usr/bin/env bash
set -u

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
selector="$repo_root/scripts/test_partitions.py"
# Свою теку прогону створює спільний прибиральник (`scripts/tmp_budget.py`), а не
# `mktemp`: він же прибирає теку, лишену SIGKILL'ом попереднього прогону, і кладе
# маркер власника, щоб сусідній прогін не зніс живу (#1300). Прибирання в
# `cleanup` лишається як було — SIGKILL його не виконує, і саме тому прибиральник
# мусить бути на СТАРТІ прогону, а не в кінці.
budget_module="$repo_root/scripts/tmp_budget.py"
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

scratch=$(python3 "$budget_module" claim --prefix slukhayka-changed-tests. --owner-pid $$) ||
  { printf '%s\n' "test-changed.sh: cannot create a scratch directory (the reason is above)" >&2; exit 2; }
cleanup() {
  # A cleanup that cannot remove the directory says so instead of passing in
  # silence: the boundary is that the cleanup is no stronger than `rm`, so a
  # read-only parent or a permission problem leaves the directory behind. The
  # exit code is untouched either way — on the exec path the process becomes the
  # full suite, and an EXIT trap does not overwrite the status of the script.
  rm -rf -- "$scratch" ||
    printf '%s\n' "test-changed.sh: cannot remove $scratch" >&2
}
trap cleanup EXIT HUP INT TERM

# Both fallbacks to the full suite `exec` it, and `exec` REPLACES this process:
# a replaced process runs no EXIT trap, so the trap above would never fire on
# that path and every fallback left one empty `slukhayka-changed-tests.*`
# directory in $TMPDIR behind (17 had piled up by #1280). `exec` stays because
# it hands the full suite's exit code through untouched — which means the
# cleanup has to run BEFORE it, at EVERY `exec` site. `rm -rf` is idempotent, so
# running it twice on an interrupted run is not a problem.
run_full_suite() {
  cleanup
  exec "$repo_root/scripts/test-all.sh"
}

if [[ ${#changed_files[@]} -eq 0 ]]; then
  cd "$repo_root" || exit 2
  if ! git rev-parse --verify "$base_ref^{commit}" >/dev/null 2>&1; then
    printf '%s\n' "Base ref '$base_ref' is unavailable; running the full suite." >&2
    run_full_suite
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
  run_full_suite
fi

python3 -c '
import json, sys
payload = json.loads(sys.argv[1])
for partition, classes in payload["partitions"].items():
    print(partition + "\t" + " ".join(classes))
' "$selection" > "$scratch/selection"

python3 -c '
import json, sys
for check in json.loads(sys.argv[1]).get("checks", []):
    print(check)
' "$selection" > "$scratch/checks"

# A path the selector maps to a named check instead of a partition. Only the
# web tree has one today: it needs no JDK, so the full Android matrix would be
# the wrong answer for it. Steps mirror the `web` job in
# .github/workflows/ci.yml.
while IFS= read -r check; do
  case "$check" in
    web)
      (cd "$repo_root/web" && npm ci && npx tsc --noEmit && npx vitest run) || exit $?
      ;;
    *)
      printf '%s\n' "Unknown verification check: $check" >&2
      exit 2
      ;;
  esac
done < "$scratch/checks"

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
