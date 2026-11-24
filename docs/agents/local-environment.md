# Local environment

The facts a lane needs on this machine. Commands are the source of truth; this
file says which ones exist and why they are shaped that way.

## At a glance

The rules that decide whether a command runs at all. Each one has cost a run;
each links to the section that explains it.

| before you… | the rule | see |
|---|---|---|
| run anything heavy | the slot wants **3500 MB free** and **swap growth ≤ 2000 MB** | [The memory guard](#the-memory-guard) |
| run anything heavy | **kill the idle Gradle daemon first, by PID**: `jps -l \| grep GradleDaemon`, then `kill <pid>`. **Never `pkill -f 'GradleDaemon'`** — that pattern also matches the shell invoking it and kills the caller | [Mutations](#mutations) |
| run a mutation | **lower the daemon's heap, not the client's**: `GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx3g"`. `GRADLE_OPTS="-Xmx3g"` leaves the build at `-Xmx4g` and only raises the client ceiling | [Mutations](#mutations) |
| check a build's result | **write the exit code to a file**, not through a pipe: `(cmd > log 2>&1; echo $? > exit)`. `cmd \| tail` reports `tail`'s status, so `exit 0` can sit beside `BUILD FAILED` | [Mutations](#mutations) |
| compile anything | a lane **inherits** `local.properties` from the copy that created it — a stale `sdk.dir` (this machine has seen `/opt/android-sdk`, which does not exist) makes Gradle answer `SDK location not found`. The SDK here is `/home/stealth/android-sdk`, or set `ANDROID_HOME` | [Worktrees](#worktrees) |
| trust a mutation | `outcome: killed` is caught; `no-verdict` and `interrupted` are **not** verdicts | [Mutations](#mutations) |
| run Gradle | only through `.worktrees/gt` — **a file**, not a directory, not on `PATH` | [The build slot](#the-build-slot) |
| trust `AGENTS.md` / `docs/agents/` in this copy | run `scripts/check_guidance_freshness.py` — it fetches `origin/main` and names every covered guidance file that differs | [Guidance freshness](#guidance-freshness) |

## Worktrees

The main checkout is **not** `main`: it sits on whatever branch another chat
left there, and `git fetch` updates `origin/main` without touching its working
tree. Reading a file straight out of it silently answers about that other
branch — a grep for a catalog once returned 33 entries where `main` had 60.
Read `main`'s files with `git show origin/main:<path>`, or from a lane's
worktree, and never commit in the main checkout.

One lane, one worktree, one branch, off `origin/main`:

```sh
scripts/new-lane.sh <issue> <slug>
```

That command is the manual sequence below, all of it — and the second line is
why the first line alone is not enough (#1332): `local.properties` is
git-excluded, so it never arrives in a fresh worktree by itself, and an Android
build there dies with `SDK location not found` (lane #1187 lost a run to it).
The manual pair stays written down as what the command must replace, not as a
recipe to run by hand:

```sh
git worktree add .worktrees/<lane> -b <branch> origin/main
cp local.properties .worktrees/<lane>/local.properties
```

If the file is not in the checkout root, `new-lane.sh` still creates the lane
and says in its facts that an Android build will not work in that copy, and
why. It never invents the file.

Worktrees live under `.worktrees/` (git-excluded) and leave with
`git worktree remove --force .worktrees/<lane>` once their PR is merged.

Commit every coherent step as you finish it. A long uncommitted diff is lost
work, not caution: a lane that ran for hours before its first commit has one
point of failure, and the step boundaries are also what make a review read the
change the way it was built.

A rebase rewrites the commit hashes, so a PR body that cited one now cites a
commit nobody can find. After a rebase, refresh the hash in the PR body — or
cite the branch's `HEAD` instead of a hash.

## Guidance freshness

Guidance is read from the copy in front of you, so a long-lived lane or the
repository root left on another branch can serve a stale `AGENTS.md`. That is
not hypothetical: a session read a root copy that still named `GLOSSARY.md`
while `origin/main` had `CONTEXT.md`, `docs/agents/local-environment.md` and the
merge gate — and merged a PR with `gh pr merge`, never knowing the gate
existed. The same session's checkout also had uncommitted guidance copies, so
the always-loaded file described a repository that did not exist.

```sh
scripts/check_guidance_freshness.py            # 0 = matches origin/main, 1 = drift
scripts/check_guidance_freshness.py --no-fetch # offline: only as fresh as the local ref
git show origin/main:AGENTS.md                  # the version that matters
```

It fetches `origin/main` first (comparing against a stale remote-tracking ref is
the false PASS the tool exists to prevent; an unreadable remote is exit 2, not
freshness). It covers `AGENTS.md`, `CONTRIBUTING.md`, `docs/agents/**` and
`docs/CODEMAPS/**`, and it only reads: a lane may legitimately change guidance
in its own PR, and that is drift until the PR merges. Run it at the start of a
session in a copy you did not create from fresh `origin/main`, and whenever a
rule seems to be missing.

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

One read answers both halves — free or busy, and everyone naming the slot:

```sh
python3 scripts/build-slot-status.py          # build slot: … — free|busy
python3 scripts/build-slot-status.py --json   # the same shape for tooling
```

`free|busy` is the lock probed non-blockingly, not inferred from a file; each
listed process carries its pid, elapsed time and `running`/`waiting` role (a
waiter has no children yet). Named limit: `flock` does not expose the owner, so
"who holds it" is derived from that process list, and a process that rewrote its
own command line is invisible — the command reports what it can see, and says
"no process names the slot" rather than guessing.

### The measured ceiling

The ceiling and the memory guard are chosen by measurement, not by taste. The
numbers, the runs behind them, and the guard's failure modes are in
`local-environment-measurements.md`.

### The memory guard

See `local-environment-measurements.md` for the thresholds, what the guard stops,
and the honest limits of both.

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

Three rules decide whether a mutation run reaches a verdict at all. All three are
measured on the shared slot, and each has already cost a run:

- **Lower the *daemon's* heap, not the client's.** `mutate.sh` reaches gradlew
  through `test-all.sh`, which runs `--no-daemon`, while `gradle.properties`
  asks for `-Xmx4g`. In Gradle 9 a `--no-daemon` run still forks a single-use
  daemon, and that daemon takes its heap from `org.gradle.jvmargs` — **not** from
  `GRADLE_OPTS`, which only sizes the client launcher. Override the property:

  ```sh
  env TMPDIR=/tmp GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx3g" ../gt \
      scripts/mutate.sh --patch .mutations/patches/<name>.patch --class <FQCN>
  ```

  `GRADLE_OPTS="-Xmx3g"` on its own leaves the build heap at `-Xmx4g` and only
  raises the client ceiling.

- **Kill the idle Gradle daemon first, by PID.** A sleeping daemon holds
  2.4–3.4 GB RSS, so `MemAvailable` never reaches the guard's 3500 MB floor and
  the run is refused before it starts. `jps -l | grep GradleDaemon`, then
  `kill <pid>` — **not `pkill -f 'GradleDaemon'`**: that pattern also matches the
  shell invoking it, so it kills the caller.

- **Write the exit code to a file, not through a pipe.** `cmd | tail` reports
  `tail`'s status, so an `exit 0` can sit next to `BUILD FAILED` in the log.
  Use `(cmd > log 2>&1; echo $? > exit)`.

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
4. A new guard (a test that makes a claim about the repository rather than about a
   function) also gets an entry in `scripts/guard_witnesses.py` — its target and
   the mutation that must redden it. `python3 scripts/guard_witnesses.py --guard
   <target>` runs just that entry; CI replays every enrolled one on each push.
5. A new number in a file `scripts/doc_numbers.py` scans (a count of a registry, a
   place in a series) also gets an entry there — the record it is derived from, and
   the line it sits on. A number that arrives with a command makes that command
   part of the claim: every `scripts/…` it cites must exist and declare its
   options, so a citation cannot be invented and stay green.
   `python3 scripts/doc_numbers.py --list` names every enrolled number with what it
   must be, so a claim about the tree carries the command that reproduces it
   rather than a reader's trust.

The merge step is described under "CI" below.

## Gates

The gates that grew out of `## Mutations` now live in
[`gates.md`](gates.md): the empty-shape mutant, the guard witnesses, the
declared field set, and the documented numbers, each with what it costs and
what it cannot see.

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
`npx vitest run`. The selector does not declare those steps: it reads the command
out of `scripts/test-changed.sh` itself (`scripts/test_partitions.py`,
`web_check_steps()`), so the record cannot drift from the executor that runs it,
and `scripts/tests/test_test_wrappers.py` pins that the wrapper executes those
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
    --reservation <name> [--plan <existing-path>]
```

`--plan` is optional and, when named, must be a path that exists in
`origin/main`; without it the record carries `null`. A lane's plan does not
exist yet at lane-creation time, so a fabricated `docs/plans/…` path there is a
record saying one thing while reality says another (#1337).

One command does the whole lane dispatch, and it is the one to use:
`scripts/new-lane.sh <issue> <slug>`. It fetches `origin/main`, reserves the
ticket through the claim script above (a busy ticket is exit 2 and **no copy is
created**), refuses when `.worktrees/w<issue>` already exists (two lanes never
share a copy — that is how `commit --amend` and `reset --hard` once wiped
another lane's edits), creates the copy from the **fresh `origin/main`** rather
than the current HEAD, carries `local.properties` into the copy when the
checkout root has one — and names that fact in its output; with no such file it
still creates the lane and says an Android build will not work there, and why
(#1332) — and prints the briefing facts as the output of commands instead of
recollection: the branch, the base `sha`, the guard registry's
`--count`, the job limit, the ticket's premise against `origin/main` (through
`issue_premise.py --issue` — a stale ticket is **named** there and the lane is
still created, while an unreadable ticket is exit 2 and no copy is made), the
carried `local.properties`, the reservation's `plan` field (`null` unless a
named path really exists in `origin/main` — #1337), and
the commands the lane runs itself.
`scripts/new-lane.sh --release <issue>` removes the lane's copy once it has
nothing to lose — it refuses when the copy has uncommitted changes or a rebase
in flight, and it never deletes the branch; `merge-pr.sh` names this command
after a merge instead of removing a possibly-foreign worktree itself. It reads
git inside a path only when that path is a **live lane checkout** — a recorded
worktree that git does not mark `prunable`, whose `--show-toplevel` is the copy
itself. A stray directory, or a stale `prunable` record with a directory
recreated on the same path, is never read as the lane: it would make git walk up
to the surrounding repository and report the coordinator's tree as the lane's.
Such a path is either cleared (an empty directory is removed, a stale record is
pruned) or left alone and named — a non-empty path whose contents are unknown is
not touched. The registry is
`<git-common-dir>/agent-work-claims/<issue>.json`, shared by every chat on this
machine; the reservation is never dropped automatically — deleting the claim's
file releases it, and that is done once the ticket is closed.
Publish the reservation as an issue comment before coding;
`docs/agents/work-coordination.md` owns the protocol.

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

### The job's time is measured where its limit applies

`script-tests` **is capped by** `timeout-minutes: 8` — **480 s**, and that number is
read out of `ci.yml` rather than copied into prose: `python3
scripts/ci_job_time.py --check-docs` refuses any seconds figure the docs claim
about this job that is neither that limit nor a *labelled* local measurement.

The job's own time cannot be a figure here at all, because it moves with every
run; what lives here is the **relation** — the limit is more than twice the
slowest run in the window *that built this tree*. `python3 scripts/ci_job_time.py`
prints each of those runs with its branch and event, its headroom and its ratio —
and, where the window crosses a change, each run's revision — and exits `1` when
the floor stops holding. The window is
one source on purpose: the floor is a claim about `main`, so the mechanism asks
the API for `main` pushes only (`branch=main&event=push`) — a slow run on
somebody's PR cannot move the floor it does not belong to, and a run that arrives
from another branch despite the filter is an unknown rather than a number.
`main` alone is not the whole source, though: successive `main` runs build
*different trees*, and a merge changes the code — and the job's time with it.
Each run therefore carries its `head_sha`, and the floor is computed from the
runs whose revision equals this tree's (`git rev-parse HEAD`) and no others; the
rest are printed by name, marked as another tree and excluded, and a window in
which no run built this tree is an unknown (exit `2`) rather than the newest
number from some other tree. "The last five" is not "always": GitHub keeps a
bounded history,
so the mechanism asks the API for a window with slack, takes only *completed*
runs, and `--runs N` moves it — the green line names the N it used. If fewer than
five completed `main` pushes are in the fetched page, the mechanism refuses
(exit `2`) instead of measuring a narrower window: "no `main` run in the window"
is not a number.

A local run of the same suite measures this machine, not the runner: it is
**173.2 s locally on this shared machine under load 6.26** (2026-10-14), and
**201.2 s** the same day under load 8.15 — the local number moves with the load,
which is why it cannot be the job's time. `python3 scripts/ci_job_time.py
--local` prints it with the label, the number and the label together, because
the two are one fact. The series that filed this ticket
measured **159–239 s** locally under load 5.4 on this shared machine, and read
it as the job's time; the mechanism now refuses that reading — an unlabelled
local figure is a finding rather than a pass. It does not touch `ci.yml`:
raising `timeout-minutes` is the owner's call, not a fix for a red number.

Where those seconds go is a separate command: `python3 scripts/suite_time.py`
reads the same command out of the `script-tests` block, takes the modules from the
directory by pattern, and measures each module — its tests *and* what sits outside
them. `unittest --durations` cannot see `setUpClass`, and that is exactly where the
gates run (`test_verdict_field_mutants.RepositoryTest` drives
`verdict_field_mutants.py` from there), so the mechanism measures the gap before a
test and charges it to that test's module. `--module <file>` measures one module
again in its own run; `--list` names the command, the directory and the modules and
measures nothing. Its numbers are local for the same reason the `--local` ones are:
it prints them through `ci_job_time.local_label`, with the machine's load beside
them, and never as the job's time.

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
