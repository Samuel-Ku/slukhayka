# Local environment

The facts a lane needs on this machine. Commands are the source of truth; this
file says which ones exist and why they are shaped that way.

## Worktrees

One lane, one worktree, one branch, off `origin/main`:

```sh
git worktree add .worktrees/<lane> -b <branch> origin/main
cp local.properties .worktrees/<lane>/local.properties
```

Worktrees live under `.worktrees/` (git-excluded) and leave with
`git worktree remove --force .worktrees/<lane>` once their PR is merged.

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

## Partitions

`AGENTS.md` names them. The wrapper forwards its arguments, so
`-Ptest.selectedClasses=<FQCN,...>` works through it, and `--rerun` is how a
fresh run is forced after a mutation.

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
