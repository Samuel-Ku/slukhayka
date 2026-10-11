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

Every PR runs the full matrix: the test partitions, Compose + Roborazzi, web
typecheck, recommendation eval, the release/migration/base guards, and Android
Lint. `gh pr checks <pr>` is the status; `gh pr checks <pr> | awk -F'\t' '$2=="fail"'`
names the losers.

The instrumented leg (`Accessibility journey (API 35)`) boots an emulator and
can fail before a single test runs. Its artifact `accessibility-api35-results`
carries the per-class logs — read those before suspecting the diff.

## Session logs

`~/.dsh/logs` and `~/.dsh/sessions` hold the transcripts of every chat on this
machine. They are the primary source for a retrospective.
