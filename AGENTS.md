# Agent instructions

AI agents working in this repository should first read:

- `CONTEXT.md` — the domain glossary and ubiquitous language
- `docs/adr/` — architectural decisions and their rationale. Open a decision
  when the task touches its concept, its rules, or a reference in the diff;
  the directory is a list to choose from, not reading for its own sake
- `CONTRIBUTING.md` — how to contribute and what the repository expects
- `docs/agents/local-environment.md` — the shared build slot, offline flags,
  partitions and work claims on this machine

All other guidance lives in the documentation tree; this file only points to
it. The repository welcomes external contributions (see `CONTRIBUTING.md`).

**A copy is not `main`.** This file is read from whichever copy you work in — a
lane, a clone, the repository root — and the root may sit on someone else's
branch with uncommitted edits. When the question is about the state of the
repository rather than the tree in front of you, read `origin/main` or start a
fresh copy with `scripts/new-lane.sh`: a file missing here may exist there, and
a count taken here may be stale. This has already produced a wrong "the file
does not exist" once. Before trusting this file in a long-lived copy, run
`scripts/check_guidance_freshness.py` — it fetches `origin/main` and names every
guidance file in its set (`AGENTS.md`, `CONTRIBUTING.md`, `docs/agents/**`,
`docs/CODEMAPS/**`) that differs, in one line. That drift is how a session
merged a PR without ever reading the merge gate; `--no-fetch` is the explicit
offline opt-out and is only as fresh as the local ref.

When a path that should exist is missing, `scripts/whereami.sh` prints the
branch, ahead/behind `origin/main`, the file-set difference (only-in-main /
only-here) and the uncommitted count in one read. Its exit code is about the
FILE SET, not the branch name: a content-only branch has an equal file set and
can exit 0 while not being `main`; `--quiet` is the branch verdict, with the
named limit that a detached HEAD counts as not-main. A tree where git itself
refuses with `fatal: this operation must be run in a work tree` is bare —
`scripts/new-lane.sh` names that symptom and the remedy (`git config core.bare
false`) in its first lines.

## Continuity

Do not stop early. A finished step is a reason to pick up the next one, not to
end the turn. When something blocks a step, name the blocker and move to
independent work instead of waiting on it — a blocker is a report, never a
result. Stop only when the task is done, the user says so, or continuing would
be irreversible.

## Voice

Every text that leaves this repository (README, release notes, posts, issue
replies) must sound like the author's own voice. Read `docs/voice.md` before
writing any of them.

## Agent skills

### Issue tracker

Issues and PRDs live in GitHub Issues. See `docs/agents/issue-tracker.md`.

A ticket is filed from the state of `main`, not from the working copy: a path a
ticket names is either in `origin/main` or marked `NEW:` as something that
ticket will create. `scripts/issue_premise.py` decides that with `git`, so an
uncommitted change on someone else's machine cannot pass for the repository —
#1261 was filed that way and had to be closed. See the same document,
"Премиса тікета мусить існувати в `main`".

### Work coordination

Before starting or delegating issue work, read `docs/agents/work-coordination.md`
and check the shared issue reservations, including the latest issue comments.
`scripts/pr-snapshot.py` answers that in one read — open PRs with base/head and
pending/failed checks, the reservations of the issues each PR closes, and the
last comment on them — instead of a round of `gh pr view` / `gh pr checks` per
question. A reservation outside that set is not shown: the whole registry is
`python3 scripts/agent-work-claim.py status`.

### Triage labels

Use the five canonical triage labels with their default names. See
`docs/agents/triage-labels.md`.

### Domain docs

This is a single-context repository with root `CONTEXT.md` and `docs/adr/`.
See `docs/agents/domain.md`.

### UI and design

Read `docs/CODEMAPS/ui-system.md` before any theming or cross-screen component
work, and `docs/CODEMAPS/ui-screens.md` for screen-level fixes. The 2026
direction «Папір і голос» has its own contract in
`docs/specs/2026-10-26-ui-refresh-2027.md` (decisions, per-screen answers,
accepted gaps) and its browsable prototype in
`docs/prototypes/2026-ui-refresh/`; the implemented truth is the Roborazzi set
in `app/src/test/snapshots/`. Two visual vocabularies coexist on purpose
(legacy v1.4 and paper); shared components render the paper branch only behind
`LocalPaperInkDesign`.

## The merge gate

`scripts/merge-pr.sh` — green checks and a fresh `review:pass`, or no merge
(squash only, never deletes the branch); call it instead of `gh pr merge`. The
verdict is re-issued with `scripts/review-pass.sh <PR>`, not `gh pr edit
--add-label`: on a PR that already has the label the latter is a silent no-op,
so the verdict keeps its old timestamp and the gate refuses (#1259). See
`docs/agents/work-coordination.md` ("Вердикт огляду і мердж").

The same verdict is published as the `Review gate` CI check
(`.github/workflows/review-gate.yml`, logic in `scripts/review_gate.py`): it
fails without a `review:pass` label, or when the label predates the PR head.
Once that check is required in branch protection, a plain `gh pr merge` cannot
even present a green board without the verdict — the script gate stays the
final word, the check makes it visible where every tool looks.

## Remote worker

Механічну роботу, якій не потрібен цей екран, виконуй на воркері, а не
локально. Машини, їхні користувачі, ключі й ліміти — `docs/agents/machines.md`;
як віддати задачу й межі автономії — `docs/agents/swarm.md`. Для однієї довгої
команди без тікета — `scripts/swarm/remote-run.sh`: preflight, стрім логу,
віддалений PID, прибирання групи на обриві й `--kill-orphans`; межі — у тому ж
`swarm.md`.

## Testing and verification

The suite runs as **partitions**, one Gradle task each: `:app:testPureJvm`,
`:app:testRoomNativeSdk35`, `:app:testRoomNativeSdk36`,
`:app:testRoomNativeDefault`, `:app:testRoomRobolectricOnly`,
`:app:testComposeRoborazzi`. A partition task takes no `--tests`; narrowing a
run means `-Ptest.selectedClasses=<FQCN,...>`.

A hand-picked class list proves nothing about the rest of its partition, so
narrow by **path**, never by class: `scripts/test-changed.sh` maps changed
paths to the partitions that cover them. A change under `app/src/main/java/`
that the selector maps to partitions also compiles `:app:compileDebugAndroidTestKotlin`
first: the device suites live in `app/src/androidTest/` and a public composable
signature can break that compile while every JVM partition stays green. The
full-suite fallback is unchanged and does not run this compile. For a changed production package that
map is the deepest package at or above it that has tests, taken whole, plus the
tests declared directly in each package above that one — a `ui.components`
change reaches `ui`'s own tests, not the rest of `ui` — and a path no test
claims falls back to the full suite. Run those partitions before calling a
change verified, and name them. A selected run once hid a broken existing test
in the same partition and reddened CI; the full matrix on CI stays the
exhaustive gate, so a local run is for feedback, not for proof of the whole
suite.

- `scripts/test-changed.sh` is the default entry point. A path no partition
  claims (among them `app/src/main/res/values*/strings.xml`) falls back to the
  full suite; run it and say so. `web/**` is the one exception: it maps to the
  web check (`npm ci`, `tsc --noEmit`, `vitest run`), which needs no JDK.
- Run the full suite when the task asks for it, and before a release tag.
- `./gradlew :app:lintDebug` is the static check: Android Lint against the
  checked-in `app/lint-baseline.xml`, so a new issue outside the baseline fails.
- `scripts/mutate.sh` is the tool for the mutation check `CONTRIBUTING.md`
  requires of new logic: write a patch that breaks it and see whether its test
  goes red. `--class` reaches JVM test sources under `app/src/test/java`,
  `--python` the unittest files under `scripts/tests/`; both leave the same
  record and answer `status` in the same words. Nothing invents mutations or
  finds survivors, so which mutation to write is the author's judgement. The
  recipe and its honest limits are in `docs/agents/local-environment.md`
  ("Mutations").
- Before a release tag, run `scripts/pre-release.sh` — the same gate CI runs on
  `release/**` (dependency-PR report + live YouTube contract canary). See
  `docs/runbooks/component-updates.md`.
