#!/usr/bin/env bash
set -u

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
gradlew=${SLUKHAYKA_GRADLEW:-"$repo_root/gradlew"}

include_controlled=no
if [[ $# -eq 1 && "$1" == --include-controlled-app ]]; then
  include_controlled=yes
elif [[ $# -ne 0 ]]; then
  printf '%s\n' "Usage: scripts/test-all.sh [--include-controlled-app]" >&2
  exit 2
fi
if [[ "$include_controlled" == yes && $(uname -s) != Darwin ]]; then
  printf '%s\n' "Controlled App target supports reviewed macOS tools only. Linux is unsupported until a separate pin review." >&2
  exit 2
fi
if [[ "$include_controlled" == no ]]; then
  printf '%s\n' "Running the six normal JVM legs. The manual controlled App target is excluded; run python3 scripts/test-controlled-app.py run separately."
fi

is_jdk_21() {
  local candidate=$1
  [[ -x "$candidate/bin/java" ]] || return 1
  # `JAVA_TOOL_OPTIONS` makes every JVM print "Picked up JAVA_TOOL_OPTIONS: …"
  # BEFORE the version line, so reading line 1 makes this probe answer "not 21"
  # on a machine whose wrapper sets it — and the caller then exits 2 having run
  # no tests at all. Read the first line that is actually the version.
  "$candidate/bin/java" -version 2>&1 | grep -v '^Picked up ' | head -n 1 | grep -Eq 'version "21([.]|\")'
}

resolve_jdk_21() {
  local candidate=""
  if [[ -n ${SLUKHAYKA_JAVA_HOME:-} ]]; then
    if is_jdk_21 "$SLUKHAYKA_JAVA_HOME"; then
      printf '%s\n' "$SLUKHAYKA_JAVA_HOME"
      return 0
    fi
    printf '%s\n' "SLUKHAYKA_JAVA_HOME does not point to a working JDK 21: $SLUKHAYKA_JAVA_HOME" >&2
    return 1
  fi

  if [[ -x /usr/libexec/java_home ]]; then
    candidate=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
    if [[ -n "$candidate" ]] && is_jdk_21 "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi

  for candidate in \
    /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    /usr/lib/jvm/java-21-openjdk-amd64 \
    /usr/lib/jvm/java-21-openjdk; do
    if is_jdk_21 "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  if command -v java >/dev/null 2>&1; then
    candidate=$(cd "$(dirname "$(command -v java)")/.." && pwd)
    if is_jdk_21 "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi

  printf '%s\n' "JDK 21 not found. Install a stable JDK 21 or set SLUKHAYKA_JAVA_HOME." >&2
  return 1
}

jdk_home=$(resolve_jdk_21) || exit 2
temp_base=${TMPDIR:-${TMP:-/tmp}}
run_temp=$(mktemp -d "$temp_base/slukhayka-tests.XXXXXX") || exit 2
cleanup() {
  rm -rf -- "$run_temp"
}
trap cleanup EXIT HUP INT TERM

export JAVA_HOME=$jdk_home
export PATH="$JAVA_HOME/bin:$PATH"
export TMPDIR=$run_temp

cd "$repo_root" || exit 2
if [[ -n ${SLUKHAYKA_GRADLE_ARGS_FILE:-} ]]; then
  [[ "$include_controlled" == no ]] || { printf '%s\n' "A custom normal partition cannot be combined with --include-controlled-app" >&2; exit 2; }
  if [[ ! -f "$SLUKHAYKA_GRADLE_ARGS_FILE" ]]; then
    printf '%s\n' "Gradle argument file is missing: $SLUKHAYKA_GRADLE_ARGS_FILE" >&2
    exit 2
  fi
  gradle_arguments=()
  while IFS= read -r argument; do
    [[ -n "$argument" ]] && gradle_arguments+=("$argument")
  done < "$SLUKHAYKA_GRADLE_ARGS_FILE"
  "$gradlew" "${gradle_arguments[@]}" --no-daemon --stacktrace
  exit $?
fi

# Separate Gradle invocations are intentional: all aliases filter AGP's one
# testDebugUnitTest task, so each invocation gets exactly one partition.
"$gradlew" :app:validateTestPartitions :app:testPureJvm --no-daemon --stacktrace || exit $?
"$gradlew" :app:testRoomNativeSdk35 --no-daemon --stacktrace || exit $?
"$gradlew" :app:testRoomNativeSdk36 --no-daemon --stacktrace || exit $?
"$gradlew" :app:testRoomNativeDefault --no-daemon --stacktrace || exit $?
"$gradlew" :app:testRoomRobolectricOnly --no-daemon --stacktrace || exit $?
"$gradlew" :app:testComposeRoborazzi --no-daemon --stacktrace || exit $?
if [[ "$include_controlled" == yes ]]; then
  python3 "$repo_root/scripts/test-controlled-app.py" run || exit $?
  python3 "$repo_root/scripts/test-controlled-app.py" run --case overview-late-room || exit $?
  python3 "$repo_root/scripts/test-controlled-app.py" run --case arrivals-live-feed || exit $?
fi
