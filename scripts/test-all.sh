#!/usr/bin/env bash
set -u

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
gradlew=${SLUKHAYKA_GRADLEW:-"$repo_root/gradlew"}
# Свою теку прогону створює спільний прибиральник (`scripts/tmp_budget.py`), а не
# `mktemp`: він же прибирає теку, лишену SIGKILL'ом попереднього прогону, і кладе
# маркер власника, щоб сусідній прогін не зніс живу (#1305). Прибирання тут
# лишається як було — у пастці; SIGKILL її не виконує, і саме тому прибиральник
# мусить бути на СТАРТІ. Своя тека тут — це `TMPDIR` для всього Gradle, тож
# лишена вона не порожня.
budget_module=$repo_root/scripts/tmp_budget.py

# Прибирання стоїть ДО перевірок, які можуть відмовити (JDK, аргументи Gradle):
# тека, лишена SIGKILL'ом, мусить зникнути й тоді, коли цей прогін не відбудеться,
# — інакше відмова одного прогону лишала б чужу теку в tmpfs назавжди (#1305).
# Невдача самого прибирання прогін не валить: причина йде в stderr, а робота — далі.
python3 "$budget_module" sweep --prefix slukhayka-tests. ||
  printf '%s\n' "test-all.sh: прибирання застарілих тек прогону не вдалося (причина вище)" >&2

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
run_temp=$(python3 "$budget_module" claim --prefix slukhayka-tests. --owner-pid $$) ||
  { printf '%s\n' "test-all.sh: cannot create a run temp directory (the reason is above)" >&2; exit 2; }
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
"$gradlew" :app:testRoomNativeSdk35 --no-daemon --stacktrace || exit $?
"$gradlew" :app:testRoomNativeSdk36 --no-daemon --stacktrace || exit $?
"$gradlew" :app:testRoomNativeDefault --no-daemon --stacktrace || exit $?
"$gradlew" :app:testRoomRobolectricOnly --no-daemon --stacktrace || exit $?
"$gradlew" :app:testComposeRoborazzi --no-daemon --stacktrace
