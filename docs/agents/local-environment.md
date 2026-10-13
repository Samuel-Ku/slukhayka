# Local environment

The facts a lane needs on this machine. Commands are the source of truth; this
file says which ones exist and why they are shaped that way.

## Worktrees

The main checkout is **not** `main`: it sits on whatever branch another chat
left there, and `git fetch` updates `origin/main` without touching its working
tree. Reading a file straight out of it silently answers about that other
branch — a grep for a catalog once returned 33 entries where `main` had 60.
Read `main`'s files with `git show origin/main:<path>`, or from a lane's
worktree, and never commit in the main checkout.

One lane, one worktree, one branch, off `origin/main`:

```sh
git worktree add .worktrees/<lane> -b <branch> origin/main
cp local.properties .worktrees/<lane>/local.properties
```

Worktrees live under `.worktrees/` (git-excluded) and leave with
`git worktree remove --force .worktrees/<lane>` once their PR is merged.

Commit every coherent step as you finish it. A long uncommitted diff is lost
work, not caution: a lane that ran for hours before its first commit has one
point of failure, and the step boundaries are also what make a review read the
change the way it was built.

A rebase rewrites the commit hashes, so a PR body that cited one now cites a
commit nobody can find. After a rebase, refresh the hash in the PR body — or
cite the branch's `HEAD` instead of a hash.

## The build slot

Run Gradle **only** through `.worktrees/gt`:

```sh
.worktrees/gt ./gradlew --offline :app:testPureJvm
```

The wrapper sets `GRADLE_USER_HOME` inside the checkout (the real `$HOME` is
read-only here), exports the offline Robolectric flags
(`-Drobolectric.offline=true -Drobolectric.dependency.dir=<checkout>/.robolectric-jars`),
and serialises every lane on `.worktrees/.build-slot` with `flock`. One Gradle
run at a time: a lane that calls `./gradlew` directly races the others for the
daemon and dies with an unexplained OOM.

A lane waiting on the slot looks exactly like a lane doing nothing — no file
changes, no test results. `pgrep -af 'gradlew --offline'` says whose run holds
the slot, and the waiting lane's own command line names the worktree it belongs
to.

### The measured ceiling

The one-run rule is not caution, it is arithmetic. On 2026-10-12 this machine
(i5-7300HQ, 4 cores / 4 threads, 16 GB) ran one identical build — the two tasks
`:app:validateTestPartitions :app:testPureJvm` narrowed to
`com.slukhayka.audiobooks.data.achievements.AchievementEngineTest` through
`-Ptest.selectedClasses` — in detached worktrees off `origin/main` (`d46bf54b`):

```sh
git worktree add --detach <dir> origin/main
GRADLE_USER_HOME=<per-run dir> GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  TMPDIR=<dir on disk, not tmpfs> SLUKHAYKA_GRADLE_ARGS_FILE=<args-file> \
  scripts/test-all.sh
# <args-file>: :app:validateTestPartitions / :app:testPureJvm / -Ptest.selectedClasses=<FQCN>
```

Those runs deliberately did **not** go through the slot above: no `flock`, no
`--offline`, and each run had its own writable `GRADLE_USER_HOME` over a shared
read-only dependency cache. That is what the experiment measured — what happens
*without* the slot. Every run was `--no-daemon` (as `test-all.sh` always is),
and `TMPDIR` was pointed at a directory on disk because `$HOME` is read-only
here and tmpfs competes with the build for the same memory.

Memory was sampled once a second: `MemAvailable` from `/proc/meminfo`, the sum
of `Used` in `/proc/swaps`, and the summed resident size of the JVM processes —
the first runs picked those out by command line, the later ones by an `exe` that
resolves to `*/java`; the difference is a few megabytes of wrapper processes.
Each row is the extreme its own run reached inside its own wall-clock window
(`end − start`, rounded down); the warm row reads 62 s where Gradle itself
printed `1m 01s`.

| Runs | Wall | Peak JVM RSS (sum) | MemAvailable min | swap used max |
|---|---|---|---|---|
| 1 cold (full compile) | 662 s | 4.74 GB | 2.66 GB | 0.18 GB |
| 1 warm, nothing changed | 62 s | not sampled | — | — |
| 1 incremental (one file edited) | 213 s | 3.61 GB | 3.86 GB | 0.68 GB |
| 2 warm, concurrent | 123 s | 1.99 GB | 5.99 GB | 1.93 GB |
| 3 incremental, concurrent | **killed at 613 s** | 8.10 GB | 1.13 GB | 3.91 GB |

Two warm, compile-free builds fit, and the second buys nothing: 123 s side by
side against 124 s one after another (2 × 62 s). Three do not fit at all —
memory fell monotonically (5.3 → 4.0 → 3.0 → 2.1 → 1.1 GB), swap grew to
3.91 GB, `load average` reached 23.5 on four threads, and an external watchdog
killed the runs at 613 s rather than leave the machine wedged. The slot itself
only serialises; the guard that stops a single run is
`scripts/build-slot-guard.py`, described below. One incremental run over the
same work takes 213 s.

So the slot serialises builds because a third one has nowhere to live, and
because four threads shared by three Gradle JVMs plus in-process Kotlin
compilation spend their time switching. What stays parallel is what does not
build. The cloud alternatives considered and closed on their own evidence —
not on this table — are recorded in ADR-0062.

### The memory guard

The slot serialises builds; it does not stop the one already running. A single
Gradle run can still take the machine down, and the row above is what that
looks like. `scripts/build-slot-guard.py` is the outside that now lives inside
the repository:

```sh
.worktrees/gt scripts/build-slot-guard.py -- ./gradlew --no-daemon :app:testPureJvm
```

It refuses to start when `MemAvailable` is below `--floor-mb` (exit `3`), and it
stops the **process group it created for the run** — never anyone else's — when
free memory falls under `--critical-mb` or swap grows past `--swap-growth-mb`
during the run (exit `4`). The command's own exit code passes through untouched;
`128+N` means the guard or the command died from signal `N`. The floor sitting
above the critical line is deliberate: a run that has already started may
finish, one that has not started should not begin.

The thresholds are defaults taken from the numbers above, not constants:

- `--floor-mb 2000` — a wedge line, not a comfort line. The measured peaks are
  3.61 GB (incremental) and 4.74 GB (cold), so a run starting at 2 GB free will
  still swap; what it should not do is take the machine with it. The state
  #1272 recorded had 1.13 GB free, and that is refused.
- `--critical-mb 800` — below this, the run is already the problem.
- `--swap-growth-mb 2000` — **growth during this run, not the absolute figure**:
  zram does not drain by itself, so an absolute ceiling would eventually stop
  every later run forever, and "free some memory" would not clear it.

Those are the code's defaults. The wrapper on this machine passes
`--floor-mb 1500` instead: 20 samples over 100 s put the machine between 1.94 and
2.10 GB free, so a 2000 MB floor turned ordinary slot work away whenever the
machine sat at its usual level, while the state #1272 recorded (1.13 GB) is
refused at either value. End to end through the wired wrapper on 2026-10-13: a
real `./gradlew --version` returned `0` in 1.08 s, a refusal returned `3` naming
the real number, and a supervised run whose reading fell returned `4` with its
process gone. The guard is not free either — an empty command through the slot
cost 5 ms without it and 111 ms with it, ten runs each.

A code collision is worth naming: because the command's own exit code passes
through untouched, a command that legitimately exits `3` or `4` cannot be told
from a refusal or a stop by the code alone — the reason is on stderr.

Two honest limits. The guard watches a process **group**, not every JVM a build
may own: a Gradle daemon outlives the group, which is one more reason
`test-all.sh` runs `--no-daemon`. And a one-second sampler reports a machine
that is already going under, not one about to; it stops the run, it does not
predict.

Both branches are pinned by `scripts/tests/test_build_slot_guard.py` against
fake memory files — refusal, stop, SIGTERM escalation, swap growth and the
untouched outsider — and every stop test also asserts that the run's processes
are **gone**, so a guard that signals nothing cannot pass.

The wrapper itself is machine-local and this repository does not carry it, so
the wiring is a change to that file, not to this one:

```sh
root=$(git rev-parse --show-toplevel)
if [ -x "$root/scripts/build-slot-guard.py" ]; then
  exec flock -w 7200 <slot-file> "$root/scripts/build-slot-guard.py" -- "$@"
fi
exec flock -w 7200 <slot-file> "$@"
```

On this machine the wrapper is wired to it: a lane whose worktree carries
`scripts/build-slot-guard.py` builds under the guard, and a lane that predates
it keeps working through the plain `exec flock` in the snippet above.

## Mutations

A mutation run leaves the same artifacts as a real one: the same
`app/build/test-results/testDebugUnitTest/*.xml`, overwritten in place. An XML
on disk therefore cannot say whether it came from a mutation or from a run of
HEAD — a review once spent a whole finding on that ambiguity. `scripts/mutate.sh`
records the difference.

```sh
../gt scripts/mutate.sh --patch .mutations/patches/<name>.patch \
    --class com.slukhayka.audiobooks.player.SmartRewindTest
```

It refuses a dirty worktree (a mutation must not mix with working edits), applies
the patch, runs that one class on the partition `scripts/test_partitions.py`
gives it, rolls the patch back, and appends one JSON record to
`.mutations/runs.jsonl`. The rollback runs from a trap, so a failing or
interrupted run rolls back too, and the sha256 of every touched file is printed
before and after. `.mutations/` is git-ignored — otherwise the tool's own record
would make the tree dirty for the next run. stderr is the proof, stdout is the
record. Exit codes: `0` caught · `1` survived · `2` refused · `3` no verdict ·
`128+N` interrupted.

`scripts/**` goes through the same tool with `--python`, which takes one target
from the set the `script-tests` CI job discovers: a whole file, or one class in
it.

```sh
../gt scripts/mutate.sh --patch .mutations/patches/<name>.patch \
    --python scripts/tests/test_merge_pr.py::MergePrTest
```

None of the bookkeeping changes: the same clean-tree refusal, the same trap, the
same sha256 proof, the same record fields, the same exit codes. What changes is
who writes the artifact. A JVM run is witnessed by Gradle's JUnit XML — a file the
real run overwrites too — and `unittest` writes no file at all, so
`scripts/mutation_python_runner.py` runs the target and writes a JUnit XML of the
same shape to `.mutations/artifacts/TEST-<target>.xml`. That is what keeps one
answer to "is this artifact a mutation's or a real run's" for both worlds.

The runner parks every `.pyc` outside the tree (via `PYTHONPYCACHEPREFIX` into the
run's scratch directory). That is not tidiness: a `.pyc` is validated by the
source's size and whole-second mtime, so a same-size mutation applied in the same
second as the previous compile would be read from the stale cache — the suite
would stay green and the record would call a live mutation a survivor.

A run that executed nothing is never a survivor, in either half: a target that
declares no tests, and a suite whose every test was skipped, both end as no verdict
(`3`). Gradle's XML and the runner's carry `skipped` for exactly this, and
`skipped == tests` is the check (it also covers `tests=0`). A mutation recorded as
`survived` by a run that ran no test would be the one answer this tool must never
invent.

Ask which the last run was instead of guessing:

```sh
scripts/mutate.sh status --class com.slukhayka.audiobooks.player.SmartRewindTest
scripts/mutate.sh status --python scripts/tests/test_merge_pr.py::MergePrTest
```

One JSON object, where `verdict` is `mutational` (the XML on disk is
byte-identical to a recorded mutation artifact), `genuine` (it differs from every
recorded one), `no-artifact` (nothing is on disk) or `unknown` (this worktree has
no record log at all). `summary` says the same in a sentence, `artifactRunId`
names the run that wrote the XML, and `headMatches` says whether HEAD still is
the commit the record was made at. Verdicts are decided in that order: with
neither an XML nor a log, the answer is `no-artifact`, not `unknown`. Records are
matched by the target key alone, so a JVM class whose fully qualified name spelled
a Python target's key would be answered as that target's artifact — the two
spellings make that implausible, not impossible.

The record's `task` for a Python target is the target as `python3 -m unittest`
loads it. Run from `scripts/tests`, where the module is importable; from the
repository root the same command is a `ModuleNotFoundError`.

Four honest limits. The records cover mutations that ran through this script — a
hand-edited tree is invisible to it. `--class` reaches JVM test sources under
`app/src/test/java`, `--python` the unittest files under `scripts/tests/`; a
mutation whose only witness is an instrumented test or a web check (`tsc`,
`vitest`, `scripts/web-layout-probe.mjs`) cannot be recorded here — vitest needs
Node and `npm ci`, which a unittest runner has no reason to bring. A patch that
edits `scripts/mutate.sh` itself edits the file the running shell is still
reading, so that promise is pinned by a copy-mutant in
`scripts/tests/test_mutate*.py` (`SLUKHAYKA_MUTATE_SCRIPT`) rather than by a run
through the tool. And no mutation is invented and no survivor is found for you:
`survived` means only that the target ran green under the mutation, so which
mutation to write stays the author's judgement — the script automates the
bookkeeping, not the thinking.

Two exceptions, and they are gates rather than records. The first is the empty
shape of a `case` decision (`"" | "detached HEAD"` — `#1254`, where a test pinned
the literal instead of the condition). `scripts/empty_shape_mutants.py` writes that
one mutation itself, runs the `scripts/tests` modules that name the script, and
refuses when the witness suite stays green — i.e. when no test produces the empty
shape at all. It runs inside the `script-tests` job, has no switch to turn it off,
and covers only `case` arms: a literal alternative such as `-h|--help` is not
pinned by it. `python3 scripts/empty_shape_mutants.py --list` names the shapes it
would mutate.

The second such gate is about the fields a verdict is made of (`#1260`): `errors`
was pinned by no test on either half — a mutation zeroing it left 20/20 green in
Python, and the stand-in `gradlew` wrote `errors=0` always in the JVM half.
`scripts/verdict_field_mutants.py` finds every place a deciding field (`tests`,
`failures`, `errors`, `skipped`, `artifactSha256`) enters the chain — the JUnit
attribute the producer writes, the read out of the XML, the transport into the
record, the record's own field, a read of that field, the same field in a `status`
answer, and the comparison that makes the verdict — builds a mutant that leaves the
field its zero at each one, and runs the witness tests. A green suite means that place holds nothing (`1`, naming the
field, the place and the witness); an unknown (a field with no place, no witness
module, a red suite *without* a mutation, a run that never finished, a missing tool
part, a mutant that does not parse) is `2`.
`python3 scripts/verdict_field_mutants.py --list` names the places and the zero it
would write. Its named limits: the JVM producer is Gradle and is not in the tree,
so that half is pinned through the XML read; a mention of the field in prose (a
`detail` string, a comment) is not a place; `skipped` decides a verdict but never
travels into the record at all; and a field with no verdict-rule place at all is an
unknown (`2`), not a pass — moving the decision onto another variable would leave
every remaining mutant alive.

Both gates build a copy of the tree for every mutant, so the temp filesystem is
their budget as well as their workplace (`#1271`). Only `scripts/` is copied and
every other top-level entry is a symlink: one tree costs 1.2 MiB against 74.7 MiB
if everything were copied (0.03–0.14 s against 0.40–1.04 s, depending on machine
load). The field gate builds 38 trees, so that is 38 × 1.2 MiB = 45.6 MiB against
38 × 74.7 MiB = 2.8 GiB. At most `--jobs` trees are alive at once, because each is
released right after its run: the measured peak is 5.3 MiB, against 46.9 MiB
before the change, when all 38 trees lived to the end of the run.
`scripts/tmp_budget.py` measures the copy
before the first one, adds headroom for the workers and a floor for the witnesses'
own scratch, and refuses with `2` and both numbers ("needs X, has Y") rather than
filling the filesystem: an exhausted `/tmp` does not fail that one run, it fails
*every* process start in the session — `bash` will not run even `true`, and
`ripgrep` does not start, so the work looks like broken tools rather than a spent
quota. The same module reclaims temp trees left by earlier crashes (loudly, naming
each one and the bytes it returned) and refuses a temp directory on a small
RAM-backed tmpfs — `TMPDIR` on a tmpfs below 2 GiB needs `TMPDIR` or
`SLUKHAYKA_MUTANTS_TMPDIR` pointed at a disk path, and an explicit move is
announced on every run. There is no switch to turn any of it off, and the check
lives in exactly one place per gate so that removing it reddens a test.

### Before a merge

1. `../gt scripts/test-changed.sh` — the real run over the changed paths.
2. One patch per mutation under `.mutations/patches/`, one call each, through the
   slot: `../gt scripts/mutate.sh --patch … --class …` for a JVM class,
   `--patch … --python …` for a `scripts/tests/` target. A `survived` verdict is a
   finding: the target does not pin the behaviour you changed.
3. `scripts/mutate.sh status --class <FQCN>` (or `status --python <target>`) for
   every target whose artifact the review will read, so the next reader does not
   have to work out whether it is a mutation's. Rollback restores sources; it does
   not delete the artifact, and `status` is what keeps that leftover from reading
   as a run of HEAD.

The merge step is described under "CI" below.

## Partitions

`AGENTS.md` names them. The wrapper forwards its arguments, so
`-Ptest.selectedClasses=<FQCN,...>` works through it, and `--rerun` is how a
fresh run is forced after a mutation.

`scripts/test-changed.sh` (and `test-all.sh` under it) calls `./gradlew`
itself, so it slips past the slot unless the wrapper runs the script — wrap the
script, not just Gradle:

```sh
.worktrees/gt scripts/test-changed.sh
```

A change under `app/src/main/res/` (a string, a colour) maps to no partition,
so `scripts/test-changed.sh` sends it to the full suite — that is the safe
answer, not a broken mapping, and it is why a strings change costs the whole
matrix locally.

A partition that dies with `OutOfMemoryError` in classes your diff never
touched has told you about the machine, not the code — one run reported 2707
tests and 13 such failures with ~400 MB free and 11 GB of swap, then ran the
same commit clean. Free memory and re-run before you believe it, and before
you go looking for a regression that is not there.

## Web diffs

`web/**` is the one tree that never needs the JDK. For it,
`scripts/test-changed.sh` runs exactly the three steps of the `web` job in
`.github/workflows/ci.yml`, in `web/`: `npm ci`, `npx tsc --noEmit`,
`npx vitest run`. The selector names the steps
(`scripts/test_partitions.py`, `WEB_CHECK_STEPS`) and
`scripts/tests/test_test_wrappers.py` pins that the wrapper executes those
three and stops at the first failure.

Run it like any other diff — no wrapper, because no Gradle call is involved:

```sh
scripts/test-changed.sh --changed-file web/src/ui/adaptiveLayout.ts
```

A web-only diff runs no Gradle at all, so there is nothing to serialise and the
slot never enters the picture: the slot is the `flock` inside `.worktrees/gt`,
and neither `scripts/test-changed.sh` nor `scripts/test-all.sh` takes it — the
wrapper is what takes it (see "The build slot" above). A diff that touches
`web/` and Android paths together does both, and it needs the wrapper exactly
like any other Gradle diff: the web steps run first, and a red one stops the run
before the first Gradle task.

The check needs a Node toolchain, and `npm ci` installs what
`web/package-lock.json` pins (it refuses a lockfile that disagrees with
`package.json`). It needs nothing else: no `local.properties`, no JDK, no
emulator.

## Room native SDK cohorts

`room-native-sdk35`, `room-native-sdk36` and `room-native-default` are not a
second opinion about Room — they are one JVM per Android SDK, because the
native SQLite Room loads through Robolectric belongs to the SDK that runtime
came from. Two SDKs in one worker is a runtime error, recorded when the 1.3.9
branches were integrated
(`docs/audits/2026-09-05-release-integration.md:11`).

The selector places a class by two facts (`scripts/test_partitions.py`): a
Robolectric test that also names native Room (`androidx.room`,
`androidx.sqlite`, `Room.inMemoryDatabaseBuilder`,
`FrameworkSQLiteOpenHelperFactory`, `MigrationTestHelper`, `AppDatabase.`) is a
native one, and its `@Config(sdk = [...])` picks the cohort — 35, 36, or none,
where none means Robolectric's default and lands in `room-native-default`. Any
other SDK is rejected at classification time. Today that is 19 classes in
SDK 35, 97 in SDK 36, 2 in the default cohort, and 124 Robolectric classes that
load no native Room at all.

Each cohort runs as its own Gradle task with a fresh worker per class
(`app/build.gradle.kts`, `forkEvery = 1`, four workers at a time), and CI keeps
them apart on purpose: `jvm-partitions` gives each cohort its own leg, and the
`Kover aggregate gate` then requires two worker probes in two distinct JVMs
with two distinct `sdk=` values (`NativeRoomWorkerIdentity`, the "Require both
native worker probes" step in `.github/workflows/ci.yml`). A native SDK 35 run
that quietly used the SDK 36 runtime would fail that step, not just a test.

Locally these are the heaviest legs in the matrix — four workers at 2 GB each,
on the machine whose OOM note is above — and `AGENTS.md` holds here as
everywhere else: run the partitions a diff touched, through the wrapper, and
name them. What the record adds is how they behave. A full local pass with the
standard JVM config has not finished: on 2026-09-05 the SDK 36 cohort stopped on
a worker that answered neither jstack nor anything else and was killed (exit
137, 6m27s), Robolectric-only and Compose hit the same worker hang, and
Robolectric's temp-dir cleanup failed with `DirectoryNotEmptyException`
(`docs/audits/2026-09-05-545-overview-covers.md:43`); two other runs reached
the two-minute-per-task timeout before SDK 36 finished, and the groups after it
never started
(`docs/audits/2026-09-05-546-book-detail.md:35`,
`docs/audits/2026-09-05-547-settings.md:41`). The cohorts' tests themselves did
pass in pieces once the JVM was tamed — `-XX:TieredStopAtLevel=1`, `-Xint` for
the last Room scenarios — but that run calls itself a check of the whole set in
parts, not a continuous green run with the standard JVM configuration
(`docs/audits/2026-09-05-551-installed-candidate.md:70`). So the local answer
for a native cohort is the same as for any other partition, and when the SDK 36
leg is in the diff, "the full set was not run locally" is the honest sentence
for a PR body — with CI as the exhaustive gate, not a substitute for the run
your diff asked for.

## The lint baseline

`app/lint-baseline.xml` is checked in, so `:app:lintDebug` fails on any new
issue. Regenerate it with `updateLintBaseline` **from the repository root**:
run inside a worktree it writes `.worktrees/<lane>/…` into the entries, which
then match nothing once that worktree is gone. The Android Lint job refuses a
baseline carrying such a path.

A baseline that only ever grows is the same silent debt one layer up, so the job
also counts entries — `MissingTranslation` on its own line, every other rule on
its own — and fails on growth against the PR's base ref:

```sh
python3 scripts/lint_baseline.py check-growth origin/main HEAD        # committed
python3 scripts/lint_baseline.py check-growth origin/main --worktree  # uncommitted
```

It compares counts per rule, not the diff, so removing entries is always fine
and a swap (−5 `UnusedResources`, +5 `MissingTranslation`) still fails on the
translation growth it would otherwise hide. A deliberate increase — a new uk
string with no translation yet — is the owner's call, expressed as the
repository variable `LINT_BASELINE_GROWTH_ALLOWANCE=MissingTranslation=+3@1222`.
The `@<issue>` part is required: the variable lives outside git and has no
expiry while the comparison base moves, so a forgotten grant would quietly pass
up to +N in every later PR. Unset means zero tolerance; while it is set the
guard prints the active allowance on **every** run — log and step summary, even
when nothing grew (`0 із 3 — НЕ витрачено`) — and it is removed with the issue it
names.

Hand-writing the entry (with `errorLine1/2`, the line and the column) is what
let the list grow unnoticed, so the report writes it instead:

```sh
.worktrees/gt ./gradlew :app:lintDebug   # fails on the new issue, writes the report
python3 scripts/lint_baseline.py add     # inserts it where lint keeps such entries
```

`add` reads `app/build/reports/lint-results-debug.xml`, keeps only what
`:app:lintDebug` fails on (`--severity` widens that), drops the `LintBaseline*`
meta entries and anything outside the module, and splices each entry into the
block of its rule at the position lint's own comparator gives it (basename,
line, message, path, column) — which is why `values-pl/strings.xml:6` sits
before `values-en/strings.xml:10`. `--dry-run` prints the text first (and refuses
a machine path exactly like a real run). `verify` proves every entry already in
the file still renders byte-for-byte: 1304 of the 1313 do, and 9 hand-written
`MissingTranslation` entries render semantically instead (they escape `>` as
`&gt;`, which lint does not). It also prints the order metrics the generator's
insertion rule rests on: how many entries sit off their canonical position, and
how many keep a stale line number.

## The web layout probe

The CSS half of the web layout — is the column really 640 px at 599, is the
list really three 320 px tracks at 1280 — is not a question `tsc` or `vitest`
can answer: jsdom runs no layout engine, so the web job in CI cannot settle it
either. `scripts/web-layout-probe.mjs` builds `web/`, serves the bundle with
`vite preview`, measures the rendered page through CDP and prints the numbers:

```sh
node scripts/web-layout-probe.mjs                      # the working tree
node scripts/web-layout-probe.mjs --base origin/main   # base against head
```

The comparison mode exits 1 when a measured field differs, and only then: a run
that could not start is 2 and an error inside the probe is 3, so a wrapper
cannot read "the probe failed" as "the layout moved". The widths are the ones
the repository already names — 411 (the phone Android's tests pin), 599/600
(the two sides of the one line in `ui/adaptiveLayout.ts`), 840 (Android's
`PlayerPaneMinWidthDp`), 2560 — plus 1280, which the probe chose: the common
desktop width, and the one #1218's evidence used.

No dependency is added: the browser is this machine's Chrome/Chromium and the
CDP client is Node's built-in `WebSocket`. The probe stops its preview and
browser, removes its temporary profile, and removes `web/dist` if the run
created it — including a `dist` a failed build left behind. A catalog list
usually has no data under `vite preview` (the dev API proxy is not a preview
proxy), so a `fixture` in the table's `source` column means a synthetic list
was measured inside the app's own `.surface` — the shipped stylesheet still
drew it.

## Wide windows on a phone

Not every width question needs a tablet: a density override makes the window
report a different number of dp, and the layout follows. Two lines are in play,
and the code owns both numbers
(`app/src/main/java/com/slukhayka/audiobooks/ui/adaptive/WindowLayout.kt`):
`ExpandedMinWidthDp` — 600 dp today — for the rail and the wide panes, and
`PlayerPaneMinWidthDp` — 840 dp today — for the player pane, the second line
#1205 drew. `scripts/wide-window.sh <dp>` does the whole recipe:

```sh
scripts/wide-window.sh 840
```

It reads the resolution from the connected device (`adb shell wm size`), takes
the narrow side — the width a portrait window gets, and a landscape window is
only wider — and derives

```text
density = 160 × width_px / target_dp
```

**floored**. That direction matters: a density rounded up is a narrower window
than the one asked for, which is how a run ends up claiming a line it never
crossed. The script applies the density, reads it back from the device, prints
the width in dp that follows from those two numbers, and reminds you about
`wm density reset` (and that the running app keeps the old layout until it
restarts).

The magic number this replaces is `wm density 280`: on the OnePlus 8 Pro
(1440 px) that is 822.9 dp — the 2026-09-25 run recorded it as 823 dp — which
crosses 600 dp and **not** 840 dp
(`docs/phone-test/2026-09-25-phone-deltas-adr-0017.md` used it for the 600 dp
run). The arithmetic was re-derived three times — the lane, the reviewer and the
PR body — which is what the script is for.

With no device attached it exits non-zero and says so: it names
`adb shell wm size` as the way to learn a resolution, and
`scripts/wide-window.sh --density-for <width-px> <dp>`, which prints the
density alone and needs no device. More than one device connected needs
`--serial` — a density override applied to the wrong phone is a phone nobody
meant to touch.

The arithmetic is a pure function, `density_for(width_px, target_dp)`, and
`scripts/tests/test_wide_window.py` pins it together with the device path
driven by a stub `adb`; the `Script unit tests` job runs both on every PR. What
that proves is the arithmetic and what the script prints — not the phone. No
device was attached while the script was written, so its device path has run
only against that stub, and a width nobody read off a real `wm size` is still a
guess.

## Work claims

```sh
python3 scripts/agent-work-claim.py status
python3 scripts/agent-work-claim.py acquire <issue> --owner <chat-id> \
    --reservation <name> --plan <path>
```

The registry is `<git-common-dir>/agent-work-claims/<issue>.json`, shared by
every chat on this machine. Deleting a claim's file releases it — do that once
the ticket is closed. Publish the reservation as an issue comment before
coding; `docs/agents/work-coordination.md` owns the protocol.

## The migration slot

```sh
scripts/check-migration-slot.sh origin/main HEAD
```

The slot is free when the branch's top `app/schemas/.../N.json` sits exactly
one above `main`'s. A data-only migration keeps its top schema file identical
to `main`'s; a schema-changing migration declares its DDL inside
`MIGRATION_(N-1)_N`, and that declaration is what the guard reads.

## CI

Every PR runs the six JVM partition legs (pure JVM, the three native Room
cohorts above, Robolectric-only, Compose + Roborazzi), the `Kover aggregate
gate`, `Recommendation eval`, `Android Lint`, `Web typecheck and vitest`
(`web`, the three steps above), `Script unit tests` (`python3 -m unittest
discover -s scripts/tests -p "test_*.py"`, no JDK), the accessibility journey,
and the lineage/migration/base-sync guards. `gh pr checks <pr>` is the status;
`gh pr checks <pr> | awk -F'\t' '$2=="fail"'` names the losers.

`web` and `script-tests` need no JDK and report their own checks; whether a red
one blocks a merge is the repository's required-checks configuration, not the
workflow file — both jobs say so in `ci.yml`.

Merging is not that loop: `scripts/merge-pr.sh <pr>` is the gate. It re-reads the
checks and refuses without a fresh `review:pass` verdict on the PR — who sets
that label, and when, is `docs/agents/work-coordination.md`. `--dry-run` prints
the verdict without merging.

The instrumented leg (`Accessibility journey (API 35)`) boots an emulator and
can fail before a single test runs. Its artifact `accessibility-api35-results`
carries the per-class logs — read those before suspecting the diff.

## Session logs

`~/.dsh/logs` and `~/.dsh/sessions` hold the transcripts of every chat on this
machine. They are the primary source for a retrospective.
