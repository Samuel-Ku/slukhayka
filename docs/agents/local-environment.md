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

A web-only diff takes **no build slot**: the slot lives in the partition loop
that calls `scripts/test-all.sh`, and a web diff selects no partition. A diff
that touches `web/` and Android paths together does both, and the web steps run
first — a red one stops the run before the first Gradle task — so only that mix
queues for the slot.

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
on the machine whose OOM note is above — and a full local pass over them is not
how they get answered here. The last attempts are recorded: on 2026-09-05 the
SDK 36 cohort stalled on a worker that answered neither jstack nor anything
else and was killed (exit 137, 6m27s), and in another run it hit a
two-minute-per-task timeout before finishing; SDK 35 and the default cohort
passed, each in its own run
(`docs/audits/2026-09-05-545-overview-covers.md:43`,
`docs/audits/2026-09-05-546-book-detail.md:35`). Neither audit established the
cause, and both hand the full check to CI. So a lane runs the partitions its
diff touched, through the wrapper, and names them; the three native cohorts are
answered by CI, and "the full set was not run locally" is the honest sentence
for a PR body, not a shortcut.

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

## Wide windows on a phone

Not every width question needs a tablet: a density override makes the window
report a different number of dp, and the layout follows. Two lines are in play
— 600 dp for the rail and the wide panes, and 840 dp for the player pane
(`app/src/main/java/com/slukhayka/audiobooks/ui/adaptive/WindowLayout.kt`,
`PlayerPaneMinWidthDp`). `scripts/wide-window.sh <dp>` does the whole recipe:

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
