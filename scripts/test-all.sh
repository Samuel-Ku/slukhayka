#!/usr/bin/env bash
set -u

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
gradlew=${SLUKHAYKA_GRADLEW:-"$repo_root/gradlew"}

is_jdk_21() {
  local candidate=$1
  [[ -x "$candidate/bin/java" ]] || return 1
  "$candidate/bin/java" -version 2>&1 | head -n 1 | grep -Eq 'version "21([.]|\")'
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
"$gradlew" :app:testRoomRobolectric --no-daemon --stacktrace || exit $?
"$gradlew" :app:testComposeRoborazzi --no-daemon --stacktrace
