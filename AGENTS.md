# Agent instructions

AI agents working in this repository should first read:

- `CONTEXT.md` — the domain glossary and ubiquitous language
- `docs/adr/` — architectural decisions and their rationale
- `CONTRIBUTING.md` — how to contribute and what the repository expects
- `docs/agents/local-environment.md` — the shared build slot, offline flags,
  partitions and work claims on this machine

All other guidance lives in the documentation tree; this file only points to
it. The repository welcomes external contributions (see `CONTRIBUTING.md`).

## Voice

Every text that leaves this repository (README, release notes, posts, issue
replies) must sound like the author's own voice. Read `docs/voice.md` before
writing any of them.

## Agent skills

### Issue tracker

Issues and PRDs live in GitHub Issues. See `docs/agents/issue-tracker.md`.

### Work coordination

Before starting or delegating issue work, read `docs/agents/work-coordination.md`
and check the shared issue reservations, including the latest issue comments.

### Triage labels

Use the five canonical triage labels with their default names. See
`docs/agents/triage-labels.md`.

### Domain docs

This is a single-context repository with root `CONTEXT.md` and `docs/adr/`.
See `docs/agents/domain.md`.

## Testing and verification

The suite runs as **partitions**, one Gradle task each: `:app:testPureJvm`,
`:app:testRoomNativeSdk35`, `:app:testRoomNativeSdk36`,
`:app:testRoomNativeDefault`, `:app:testRoomRoBolectricOnly`,
`:app:testComposeRoborazzi`. A partition task takes no `--tests`; narrowing a
run means `-Ptest.selectedClasses=<FQCN,...>`.

A hand-picked class list proves nothing about the rest of its partition, so
narrow by **path**, never by class: `scripts/test-changed.sh` maps changed
paths to the partitions that cover them, and that mapping is complete for the
files you touched. Run those partitions before calling a change verified, and
name them. A selected run once hid a broken existing test in the same
partition and reddened CI; the full matrix on CI stays the exhaustive gate, so
a local run is for feedback, not for proof of the whole suite.

- `scripts/test-changed.sh` is the default entry point. A path no partition
  claims (among them `app/src/main/res/values*/strings.xml`) falls back to the
  full suite; run it and say so.
- Run the full suite when the task asks for it, and before a release tag.
- `./gradlew :app:lintDebug` is the static check: Android Lint against the
  checked-in `app/lint-baseline.xml`, so a new issue outside the baseline fails.
- Before a release tag, run `scripts/pre-release.sh` — the same gate CI runs on
  `release/**` (dependency-PR report + live YouTube contract canary). See
  `docs/runbooks/component-updates.md`.
