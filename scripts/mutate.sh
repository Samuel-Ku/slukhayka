#!/usr/bin/env bash
# #1221: mutation bookkeeping. Apply one named mutation, run exactly one test
# class, roll the mutation back on every exit path, and append a machine-readable
# record under .mutations/.
#
# A mutation run leaves the same Gradle artifacts as a real run: the same
# app/build/test-results/testDebugUnitTest/*.xml, overwritten in place. Without a
# record, a later reader can only guess whether the last failing XML came from a
# mutation. `scripts/mutate.sh status` answers that from the record.
#
# #1255: `--python` is the same bookkeeping for scripts/**. A JVM run is witnessed
# by Gradle's JUnit XML — the file mutate.sh hashes and reads the counts from —
# and `unittest` writes no such file, so a mutation of scripts/** had nowhere to
# put its evidence. scripts/mutation_python_runner.py runs one target and writes
# a JUnit XML of the same shape, so the Python half feeds the same record fields,
# the same verdict and the same `status` vocabulary: one answer to "is this
# artifact a mutation's or a real run's", for both worlds.
#
# scripts/mutation_trace.py owns the record itself: this script decides when to
# write one, that module decides what one says.
set -u

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
trace_dir=$repo_root/.mutations
trace_log=$trace_dir/runs.jsonl
selector=$repo_root/scripts/test_partitions.py
trace_module=$repo_root/scripts/mutation_trace.py
python_runner=$repo_root/scripts/mutation_python_runner.py
# Свою теку прогону створює спільний прибиральник (`scripts/tmp_budget.py`), а не
# `mktemp`: він же прибирає теку, лишену SIGKILL'ом попереднього прогону, і кладе
# маркер власника, щоб сусідній прогін не зніс живу (#1300). Прибирання тут
# лишається як було — у EXIT-пастці; SIGKILL її не виконує, і саме тому
# прибиральник мусить бути на СТАРТІ, а не в кінці.
budget_module=$repo_root/scripts/tmp_budget.py
runner=$repo_root/scripts/test-all.sh
# Every partition alias depends on AGP's single testDebugUnitTest task, so they
# all write one JUnit directory (app/build.gradle.kts registers the aliases on it).
results_relative=app/build/test-results/testDebugUnitTest
# The Python half's artifact directory. It is under .mutations/ for the same
# reason the record is: it is git-ignored, so a run's own artifact cannot make
# the tree dirty for the next run. No real `python3 -m unittest` run writes a
# file at all — the runner is what writes this XML.
python_results_relative=.mutations/artifacts

usage() {
  cat <<'USAGE'
Usage:
  scripts/mutate.sh --patch <file> --class <FQCN>
  scripts/mutate.sh --patch <file> --python <target>
  scripts/mutate.sh status [--class <FQCN> | --python <target>]

--patch <file>  One named mutation as a `git apply` patch. Keep it outside the
                tree or in .mutations/patches/: .mutations/ is git-ignored, while
                any other untracked file counts as a dirty worktree.
--class <FQCN>  The single JVM test class to run (com.example.SomeTest).
--python <target>
                The single Python test target to run, spelled the way the
                script-tests job discovers it:
                scripts/tests/test_<name>.py (the whole file) or
                scripts/tests/test_<name>.py::<ClassName> (one class). The
                record's class key is the flat unittest name (test_<name> or
                test_<name>.<ClassName>), which is what `python3 -m unittest`
                loads. web/ is out of reach: vitest needs npm ci and a Node
                toolchain, and no such target would be a unittest run.

Run mode appends one JSON record to .mutations/runs.jsonl, prints that record on
stdout and the sha256 proof of the rollback on stderr. Before it starts, the
worktree must be clean; afterwards it is clean again — the mutation is rolled
back (tracked files from the index, files the patch added removed) even when the
run fails or is interrupted. Exit codes:
  0 mutation caught · 1 mutation survived · 2 refused (dirty worktree, unknown
  class, patch that does not apply) · 3 no verdict (the target never ran, or the
  build failed) · 128+N interrupted by signal N.

The artifact the record names is Gradle's JUnit XML for --class and the runner's
own XML (.mutations/artifacts/TEST-<target>.xml) for --python; both are hashed
the same way and both carry tests/failures/errors, so `status` answers for either.

status mode prints one JSON object: whether the newest test artifact for that
target on disk was written by a recorded mutation run ("mutational"), by a run
this tool did not record ("genuine"), is gone ("no-artifact"), or cannot be
decided because no record log exists in this worktree ("unknown"). Records are
matched by that target key alone: a JVM class whose fully qualified name happened
to spell a Python target's key would be answered as that key's artifact.

Verdicts: a run where no test executed is no verdict, not a survivor — a target
that declares no tests, and a suite whose every test was skipped, both end at 3.

Honest limits: this tool records mutations that run through it. It does not
write mutations (which mutation to write stays your judgement) and it does not
find survivors for you: "survived" says the target ran green under the mutation,
and nothing about the rest of the suite. A patch that edits scripts/mutate.sh
itself edits a file the running shell is still reading: pin those checks with the
copy-mutant pattern in scripts/tests/test_mutate*.py instead.

A SIGKILL cannot run the EXIT trap, so it leaves two things behind, and only one
of them is cleaned up for you. The scratch directory is reaped at the START of the
next run (scripts/tmp_budget.py sweep — the same sweeper the mutation mechanisms
use; claim reaps again once the run gets that far), and that includes a run which
then REFUSES — the dirty worktree a SIGKILL leaves behind is exactly what makes it
refuse, so the directory cannot pile up in a tmpfs. The applied mutation is NOT
reaped: it stays in the tree, and the next run refuses on a dirty worktree until
you roll it back by hand (git checkout -- .). That is a boundary in this script,
not an oversight.
USAGE
}

refuse() {
  printf '%s\n' "mutate.sh: $*" >&2
  exit 2
}

note() {
  printf '%s\n' "mutate.sh: $*" >&2
}

sha256_program=()
if command -v sha256sum >/dev/null 2>&1; then
  sha256_program=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
  sha256_program=(shasum -a 256)
else
  refuse "sha256sum or shasum is required"
fi

sha256_of() {
  "${sha256_program[@]}" "$1" | awk '{print $1}'
}

# An interrupt has to stop the run, not just the shell that started it: gradlew
# forks a JVM, and a stray JVM on this machine is an OOM waiting for the next
# lane. Walk the tree before signalling it — a dead parent's children are
# reparented and unfindable. Without pgrep this stops the direct child only.
descendants() {
  local parent=$1
  local child
  for child in $(pgrep -P "$parent" 2>/dev/null || true); do
    printf '%s\n' "$child"
    descendants "$child"
  done
}

mode=run
runner_kind=jvm
patch_file=""
test_class=""
python_arg=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    status)
      mode=status
      shift
      ;;
    --patch)
      [[ $# -ge 2 ]] || refuse "--patch requires a patch file"
      patch_file=$2
      shift 2
      ;;
    --class)
      [[ $# -ge 2 ]] || refuse "--class requires a fully qualified test class"
      test_class=$2
      shift 2
      ;;
    --python)
      [[ $# -ge 2 ]] || refuse "--python requires a test target"
      python_arg=$2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf '%s\n' "mutate.sh: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -n $python_arg && -n $test_class ]]; then
  refuse "--class and --python name different worlds; pass one"
fi

# Прибирання стоїть ПІСЛЯ розбору аргументів і після перевірки `sha256sum` (вона
# ще вище), але ДО перевірок РЕЖИМУ ПРОГОНУ — патча, класу, партиції й брудного
# дерева, — і це не про охайність.
# SIGKILL лишає теку прогону, а якщо він упав до відкату (а він падає туди, поки
# хтось у гурті переживає TERM), то ще й застосовану мутацію в дереві: наступний
# прогін відмовляє саме на брудному дереві. Прибирання, яке стоїть після ЦИХ
# перевірок, не виконалося б ніколи (#1300): відмова цього прогону не сміє бути
# причиною, чому чужа тека лишається в tmpfs назавжди. `status` проходить тут
# ТЕЖ (його `exec` — нижче), і це навмисно: прибирання не залежить від режиму.
# `--help` виходить вище, у розборі аргументів, і не прибирає нічого.
python3 "$budget_module" sweep --prefix slukhayka-mutation. ||
  note "прибирання застарілих тек прогону не вдалося (причина вище)"

# `--python` names one target the way the script-tests CI job discovers it:
# scripts/tests/test_<name>.py for the whole file, …::<ClassName> for one class.
# Both spellings reduce to the record's `class` key — the flat unittest module
# name and the class — because that is the name `python3 -m unittest` loads and
# the name `status --python` has to reproduce. The checks that need a file (does
# it exist, is the class declared in it) belong to run mode; status answers for
# targets whose file is gone too, from the record.
parse_python_target() {
  local target=$1
  python_file=${target%%::*}
  python_class=""
  if [[ $target == *"::"* ]]; then
    python_class=${target#*::}
    [[ -n $python_class && $python_class != *"::"* ]] ||
      refuse "not a test target: $target (expected <file> or <file>::<ClassName>)"
  fi
  [[ $python_file =~ ^scripts/tests/test_[A-Za-z0-9_]+\.py$ ]] ||
    refuse "--python reaches only the unittest files the script-tests job discovers (scripts/tests/test_*.py), not: $python_file"
  python_module=$(basename "$python_file" .py)
  python_target=$python_module
  if [[ -n $python_class ]]; then
    [[ $python_class =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] ||
      refuse "not a test class name: $python_class"
    python_target=$python_module.$python_class
  fi
}

if [[ $mode == status ]]; then
  [[ -f $trace_module ]] || refuse "missing $trace_module"
  status_results=$results_relative
  if [[ -n $python_arg ]]; then
    parse_python_target "$python_arg"
    test_class=$python_target
    status_results=$python_results_relative
  fi
  exec python3 "$trace_module" status \
    --root "$repo_root" --log "$trace_log" --results "$status_results" \
    --class "$test_class"
fi

[[ -n $patch_file ]] || refuse "usage: --patch <file> (--class <FQCN> | --python <target>)"
[[ -f $patch_file ]] || refuse "patch file not found: $patch_file"
if [[ -n $python_arg ]]; then
  runner_kind=python
  parse_python_target "$python_arg"
  test_class=$python_target
  [[ -f "$repo_root/$python_file" ]] || refuse "no test file: $python_file"
  [[ -f $python_runner ]] || refuse "missing $python_runner"
  if [[ -n $python_class ]]; then
    grep -Eq "^class ${python_class}([^A-Za-z0-9_]|\$)" "$repo_root/$python_file" ||
      refuse "no class $python_class in $python_file"
  fi
else
  [[ -n $test_class ]] || refuse "usage: --patch <file> --class <FQCN>"
  [[ $test_class =~ ^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$ ]] ||
    refuse "not a fully qualified class name: $test_class"
  if [[ -z ${GRADLE_USER_HOME:-} ]]; then
    note "GRADLE_USER_HOME is unset: run this through the build slot, e.g. ../gt scripts/mutate.sh --patch … --class …"
  fi
fi

git -C "$repo_root" rev-parse --verify HEAD >/dev/null 2>&1 ||
  refuse "no commit at HEAD in $repo_root"

# The rollback restores tracked files to the index, so anything already changed
# would be lost — and a mutation mixed with working edits proves nothing anyway.
dirty=$(git -C "$repo_root" status --porcelain)
if [[ -n $dirty ]]; then
  printf '%s\n' "mutate.sh: the worktree is dirty; commit or stash first so the mutation cannot mix with your edits:" >&2
  printf '%s\n' "$dirty" >&2
  exit 2
fi

scratch=$(python3 "$budget_module" claim --prefix slukhayka-mutation. --owner-pid $$) ||
  refuse "cannot create a scratch directory (the reason is above)"

# One flag gates the rollback (set before the patch is applied, so a half-applied
# patch is still covered), one gates the record (set only after the patch landed).
guard=0
record=0
child_pid=""
interrupted=""
interrupted_status=0
outcome=""
detail=""
trace_written=0
run_id="" patch_sha="" task="" partition="" branch="" head=""
started_at="" finished_at="" duration="" run_status="" mutation_name=""
tests="" failures="" errors="" skipped=""
artifact_sha="" artifact_fresh=0 artifact_path="" artifact_before=""
touched=()
pristine=()

finalize() {
  local status=$?
  local all_restored=1
  local final
  local leftover
  local index=0
  local path
  local before
  local after
  local files_tsv
  local tree
  local pid

  if [[ -n $child_pid ]]; then
    tree=$(descendants "$child_pid")
    for pid in $tree; do
      kill -TERM "$pid" 2>/dev/null || true
    done
    kill -TERM "$child_pid" 2>/dev/null || true
    index=0
    while kill -0 "$child_pid" 2>/dev/null && [[ $index -lt 12 ]]; do
      sleep 0.2
      index=$((index + 1))
    done
    for pid in $tree; do
      kill -KILL "$pid" 2>/dev/null || true
    done
    kill -KILL "$child_pid" 2>/dev/null || true
  fi

  if [[ -n $interrupted ]]; then
    outcome=interrupted
    detail="SIG$interrupted arrived while the run was in flight"
    # Gradle may have written the class's XML before the signal. That file is
    # still this run's output, and without its hash status would have to read it
    # as a run nobody recorded — the exact guess this tool exists to remove.
    if [[ -n $artifact_path && -f $artifact_path ]]; then
      artifact_sha=$(sha256_of "$artifact_path")
      [[ $artifact_sha == "$artifact_before" ]] || artifact_fresh=1
      [[ $artifact_fresh -eq 1 ]] || artifact_sha=""
    fi
  fi

  if [[ $guard -eq 1 ]]; then
    git -C "$repo_root" checkout -- . >/dev/null 2>&1 || true
    git -C "$repo_root" clean -fdq -- . >/dev/null 2>&1 || true

    files_tsv=$scratch/files.tsv
    : > "$files_tsv"
    index=0
    while [[ $index -lt ${#touched[@]} ]]; do
      path=${touched[$index]}
      before=${pristine[$index]}
      after=""
      [[ -f "$repo_root/$path" ]] && after=$(sha256_of "$repo_root/$path")
      if [[ $before == "$after" ]]; then
        # A refusal before the patch landed has nothing to prove: stay quiet.
        [[ $record -eq 1 ]] && note "restored ${path} — sha256 ${after:-absent} before and after"
      else
        all_restored=0
        note "RESTORE FAILED for ${path} — before ${before:-absent}, after ${after:-absent}"
      fi
      printf '%s\t%s\t%s\n' "$path" "$before" "$after" >> "$files_tsv"
      index=$((index + 1))
    done
    leftover=$(git -C "$repo_root" status --porcelain -uall)
    if [[ -n $leftover ]]; then
      all_restored=0
      printf '%s\n' "mutate.sh: the worktree is still dirty after the rollback:" >&2
      printf '%s\n' "$leftover" >&2
    fi
  fi

  if [[ $record -eq 1 ]]; then
    if python3 "$trace_module" record --log "$trace_log" --files "$files_tsv" \
      "runId=$run_id" "mutation=$mutation_name" "class=$test_class" "task=$task" \
      "partition=$partition" "patch=$patch_file" "patchSha256=$patch_sha" \
      "worktree=$repo_root" "branch=$branch" "head=$head" "startedAt=$started_at" \
      "finishedAt=$finished_at" "durationSeconds=$duration" "exitCode=$run_status" \
      "outcome=$outcome" "detail=$detail" "tests=$tests" "failures=$failures" \
      "errors=$errors" "artifact=$artifact_relative/TEST-$test_class.xml" \
      "artifactFresh=$([[ $artifact_fresh -eq 1 ]] && printf yes || printf no)" \
      "artifactSha256=$artifact_sha" \
      "restored=$([[ $all_restored -eq 1 ]] && printf yes || printf no)"; then
      trace_written=1
      note "record appended to ${trace_log#"$repo_root"/} (run $run_id)"
    else
      note "FAILED to append the record to $trace_log"
    fi
  fi

  # A refusal (record=0) already said why; a verdict line there would invent one.
  if [[ $record -eq 0 ]]; then
    rm -rf -- "$scratch"
    exit "$status"
  fi

  case "$outcome" in
    killed) note "verdict: caught — ${test_class} failed under the mutation" ;;
    survived) note "verdict: survived — ${test_class} ran green under the mutation; the test does not pin this behaviour" ;;
    interrupted) note "verdict: interrupted — the mutation was rolled back; this run's artifacts are not a verdict" ;;
    *) note "verdict: no verdict — $detail" ;;
  esac

  if [[ $status -eq 2 ]]; then
    final=2
  else
    case "$outcome" in
      killed) final=0 ;;
      survived) final=1 ;;
      interrupted) final=$interrupted_status ;;
      *) final=3 ;;
    esac
  fi
  [[ $all_restored -eq 1 ]] || final=3
  [[ $trace_written -eq 1 ]] || final=3
  rm -rf -- "$scratch"
  exit "$final"
}

trap finalize EXIT
trap 'interrupted=TERM; interrupted_status=143; exit 143' TERM
trap 'interrupted=INT; interrupted_status=130; exit 130' INT
trap 'interrupted=HUP; interrupted_status=129; exit 129' HUP

if [[ $runner_kind == python ]]; then
  # The Python half is a unittest target inside the script-tests job's own
  # directory: no partition selector, no Gradle task. `task` names the target the
  # way that job loads it — `python3 -m unittest <target>` finds the module only
  # from scripts/tests, not from the repository root — and `partition` names the
  # CI job that owns the file.
  artifact_relative=$python_results_relative
  task="python3 -m unittest $test_class"
  partition=script-tests
else
  test_path="app/src/test/java/${test_class//./\/}.kt"
  [[ -f "$repo_root/$test_path" ]] ||
    refuse "no test source for $test_class (expected $test_path)"

  if ! selection=$(python3 "$selector" --root "$repo_root" select --changed-file "$test_path"); then
    refuse "the test partition selector failed for $test_class"
  fi
  # #1636: a changed test file may declare several test classes (#1634 gave the
  # selector that shape). The named class only has to be ONE of them; requiring
  # `classes == [target]` refused every class of such a file. When the class is
  # not among the declared ones the refusal names them, so the next run does not
  # have to re-derive the file by hand.
  if ! partition=$(python3 - "$selection" "$test_class" 2>"$scratch/selector-hint" <<'PY'
import json
import sys

try:
    payload = json.loads(sys.argv[1])
except json.JSONDecodeError:
    sys.exit(1)
partitions = payload.get("partitions") or {}
if payload.get("fullSuite") or len(partitions) != 1:
    sys.exit(1)
name, classes = next(iter(partitions.items()))
target = sys.argv[2]
if target not in classes:
    print(
        f"{target} is not a test class declared in the changed file; "
        f"declared: {', '.join(classes)}",
        file=sys.stderr,
    )
    sys.exit(1)
print(name)
PY
  ); then
    hint=$(cat "$scratch/selector-hint" 2>/dev/null || true)
    [[ -n $hint ]] || hint="the changed file declares no single test class"
    refuse "no single test partition claims $test_class — $hint"
  fi

  # Same partition → Gradle task mapping as scripts/test-changed.sh: keep in sync.
  case "$partition" in
    pure-jvm) task=:app:testPureJvm ;;
    room-native-sdk35) task=:app:testRoomNativeSdk35 ;;
    room-native-sdk36) task=:app:testRoomNativeSdk36 ;;
    room-native-default) task=:app:testRoomNativeDefault ;;
    room-robolectric-only) task=:app:testRoomRobolectricOnly ;;
    compose-roborazzi) task=:app:testComposeRoborazzi ;;
    *) refuse "unknown test partition: $partition" ;;
  esac
  artifact_relative=$results_relative
fi

apply_error=$(git -C "$repo_root" apply --check -- "$patch_file" 2>&1) ||
  refuse "the patch does not apply cleanly: $apply_error"

touched=()
while IFS= read -r path; do
  [[ -n $path ]] && touched+=("$path")
done < <(git -C "$repo_root" apply --numstat -- "$patch_file" | awk -F'\t' 'NF >= 3 { print $3 }')
[[ ${#touched[@]} -gt 0 ]] || refuse "the patch touches no files"

index=0
while [[ $index -lt ${#touched[@]} ]]; do
  path=${touched[$index]}
  if [[ -f "$repo_root/$path" ]]; then
    pristine+=("$(sha256_of "$repo_root/$path")")
  else
    pristine+=("")
  fi
  index=$((index + 1))
done

patch_sha=$(sha256_of "$patch_file")
branch=$(git -C "$repo_root" rev-parse --abbrev-ref HEAD 2>/dev/null || printf '')
head=$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || printf '')
run_id=$(date -u +%Y%m%dT%H%M%SZ)-$$
mutation_name=$(basename "$patch_file")
mutation_name=${mutation_name%.patch}
mutation_name=${mutation_name%.diff}

guard=1
git -C "$repo_root" apply -- "$patch_file" ||
  refuse "git apply failed after --check passed; nothing was left modified"
record=1

artifact_path=$repo_root/$artifact_relative/TEST-$test_class.xml
[[ -f $artifact_path ]] && artifact_before=$(sha256_of "$artifact_path")

started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
started_epoch=$(date +%s)
note "mutation $mutation_name → $test_class on $task (the run follows)"

if [[ $runner_kind == python ]]; then
  # PYTHONPYCACHEPREFIX matters more than it looks: a .pyc is validated by the
  # source's size and whole-second mtime, so a same-size mutation applied in the
  # same second as the previous compile would be read from a stale cache — the
  # suite would stay green and the record would call a live mutation a survivor.
  # The prefix parks every cached byte under the scratch directory, which the
  # rollback deletes, so the tree is neither read from a stale cache nor left with
  # a __pycache__ directory of its own.
  PYTHONPYCACHEPREFIX=$scratch/pycache python3 "$python_runner" \
    --tests-dir "$repo_root/scripts/tests" --target "$test_class" \
    --artifact "$artifact_path" &
else
  args_file=$scratch/gradle-arguments
  printf '%s\n' :app:validateTestPartitions "$task" "-Ptest.selectedClasses=$test_class" --rerun > "$args_file"
  SLUKHAYKA_GRADLE_ARGS_FILE=$args_file "$runner" &
fi
child_pid=$!
wait "$child_pid"
run_status=$?
child_pid=""
finished_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
duration=$(( $(date +%s) - started_epoch ))

# An XML that existed before this run and kept the same bytes is a stale artifact,
# not a verdict: JUnit XML carries its own timestamp, so a fresh run cannot
# reproduce the previous file byte for byte.
if [[ -f $artifact_path ]]; then
  artifact_sha=$(sha256_of "$artifact_path")
  [[ $artifact_sha == "$artifact_before" ]] || artifact_fresh=1
fi
if [[ $artifact_fresh -eq 1 ]]; then
  counts=$(python3 - "$artifact_path" <<'PY'
import sys
import xml.etree.ElementTree as ET

try:
    root = ET.parse(sys.argv[1]).getroot()
except (ET.ParseError, OSError):
    sys.exit(1)
print(
    root.get("tests", "0"),
    root.get("failures", "0"),
    root.get("errors", "0"),
    root.get("skipped", "0"),
)
PY
  ) || counts=""
  if [[ -n $counts ]]; then
    read -r tests failures errors skipped <<< "$counts"
  else
    artifact_fresh=0
  fi
fi
if [[ $artifact_fresh -eq 0 ]]; then
  artifact_sha=""
fi

if [[ -z $tests ]]; then
  outcome=no-verdict
  if [[ -f $artifact_path ]]; then
    detail="the class left no fresh JUnit artifact; the XML on disk predates this run, and the runner exited ${run_status}"
  elif [[ $runner_kind == python ]]; then
    detail="the target left no JUnit artifact; the runner exited ${run_status} (the target or its imports did not load, it declared no tests, or the runner was refused)"
  else
    detail="the class left no JUnit artifact; the runner exited ${run_status} (compile error, no matching class, or a refused runner)"
  fi
elif [[ $failures -gt 0 || $errors -gt 0 ]]; then
  outcome=killed
  detail="the class failed under the mutation (tests=$tests failures=$failures errors=$errors)"
elif [[ $run_status -ne 0 ]]; then
  outcome=no-verdict
  detail="the artifact shows no failure but the runner exited ${run_status}: no verdict"
elif [[ $skipped == "$tests" ]]; then
  # Every test skipped (or none declared: tests=0, skipped=0) is a green run that
  # ran nothing. "Survived" has to mean the tests executed and stayed green —
  # a live mutation this recorded as a survivor is the one answer this tool must
  # never invent. Both halves write `skipped` into their JUnit XML, so both are
  # covered by the one rule.
  outcome=no-verdict
  detail="every test was skipped (tests=$tests skipped=$skipped): a run that executed nothing is no verdict"
else
  outcome=survived
  detail="the class ran green under the mutation (tests=$tests)"
fi

exit 0
