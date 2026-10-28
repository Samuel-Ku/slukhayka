#!/usr/bin/env bash
# Roborazzi goldens: one command for record / verify / pin.
#
# Why. A design-refresh session made 52 record and 48 verify invocations, each
# an ssh call plus a build-slot turn, and then updated nine guard digests by
# hand. The flags themselves are easy to get wrong silently: without
# `-Proborazzi.test.record=true|verify=true` a snapshot test does nothing and
# looks green (docs/runbooks/snapshot-goldens.md).
#
# Usage:
#   scripts/goldens.sh record <Class|Class#method ...>
#   scripts/goldens.sh verify <Class|Class#method ...>
#   scripts/goldens.sh record --all        # explicit full-set record
#   scripts/goldens.sh verify --all
#   scripts/goldens.sh pin <file ...>      # sha256 + 32+32 pair for the guard
#
# The Gradle invocation goes through `scripts/test-all.sh` with
# SLUKHAYKA_GRADLE_ARGS_FILE, so the JDK-21 resolver, offline flags and the
# lane wrapper stay the same as everywhere else. `pin` only PRINTS: updating
# SnapshotGoldenBytesGuardTest is a reviewed decision, not a side effect.
#
# Refusals: unknown/missing mode; no class without --all; --all together with
# classes (ambiguous); pin path missing/outside app/src/test/snapshots; a
# non-file. Record/verify print the changed goldens from `git status` (new
# untracked PNGs included) and the exit code of the Gradle run.
#
# Env: GOLDENS_REPO_ROOT — repo root (tests point it at a fixture; default is
# the checkout above this script). No network; no Gradle in the tests.
set -euo pipefail

root=${GOLDENS_REPO_ROOT:-"$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"}
snapshots_rel="app/src/test/snapshots"
snapshots="$root/$snapshots_rel"

usage() {
  sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

refuse() {
  printf 'FAIL  %s\n' "$1" >&2
  exit 2
}

if [[ $# -eq 0 ]]; then
  usage >&2
  exit 2
fi

command_name=$1
shift

case "$command_name" in
  record | verify)
    all=no
    classes=()
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --all) all=yes ;;
        -*) refuse "невідомий прапорець: $1" ;;
        *) classes+=("$1") ;;
      esac
      shift
    done
    if [[ $all == yes && ${#classes[@]} -gt 0 ]]; then
      refuse "--all і список класів взаємовиключні: вибери або весь набір, або названі класи"
    fi
    if [[ $all == no && ${#classes[@]} -eq 0 ]]; then
      refuse "немає класів: назви їх або передай --all (повний запис — свідомий вибір)"
    fi
    args_file=$(mktemp "${TMPDIR:-/tmp}/goldens-args.XXXXXX")
    trap 'rm -f "$args_file"' EXIT
    printf '%s\n' ':app:testComposeRoborazzi' >"$args_file"
    if [[ $all == no ]]; then
      joined=$(IFS=,; printf '%s' "${classes[*]}")
      printf '%s\n' "-Ptest.selectedClasses=$joined" >>"$args_file"
      printf 'goldens: %s %s\n' "$command_name" "$joined"
    else
      printf 'goldens: %s --all (повний набір — перевір, що це справді потрібно)\n' "$command_name"
    fi
    printf '%s\n' "-Proborazzi.test.record=$([[ $command_name == record ]] && echo true || echo false)" >>"$args_file"
    SLUKHAYKA_GRADLE_ARGS_FILE="$args_file" "$root/scripts/test-all.sh"
    rc=$?
    changed=$(git -C "$root" status --porcelain -- "$snapshots_rel" || true)
    if [[ -n "$changed" ]]; then
      printf 'goldens: змінені еталони:\n%s\n' "$changed"
      printf 'goldens: файлів змінено: %s\n' "$(printf '%s\n' "$changed" | wc -l | tr -d ' ')"
    else
      printf 'goldens: еталони не змінилися\n'
    fi
    exit "$rc"
    ;;
  pin)
    [[ $# -gt 0 ]] || refuse "pin: назви хоча б один файл еталона"
    for argument in "$@"; do
      case "$argument" in
        /*) candidate=$argument ;;
        *) candidate="$snapshots/$argument" ;;
      esac
      [[ -f "$candidate" ]] || refuse "pin: файл не знайдено: $candidate"
      resolved=$(cd "$(dirname "$candidate")" && pwd)/$(basename "$candidate")
      resolved_dir=$(dirname "$resolved")
      [[ "$resolved_dir" == "$(cd "$snapshots" && pwd)" ]] ||
        refuse "pin: $candidate не лежить у $snapshots_rel"
      digest=$(sha256sum "$resolved" | cut -d' ' -f1)
      name=$(basename "$resolved")
      printf '%s\n' "$name sha256=$digest"
      printf '  "%s" + "%s"\n' "${digest:0:32}" "${digest:32:32}"
    done
    ;;
  -h | --help)
    usage
    ;;
  *)
    usage >&2
    refuse "невідома підкоманда: $command_name (очікую record|verify|pin)"
    ;;
esac
