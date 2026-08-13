# [Spec-16] Playback events & position history

> **Status:** Draft — synthesized from the 2026-08-13 autonomous grilling of wayfinder #53 (user delegated the interview; recommendations locked). Pending tickets on GitHub.
> **Tracker:** filed as issue (labels `spec-16`, `ready-for-agent`).

## Problem Statement

From the listener's perspective, the app remembers where you stopped — but only barely. If you accidentally skip five minutes or an hour, the «Повернутися» (undo) offer exists only while the app stays in memory: close the app and the way back is gone. Resuming after a pause rewinds smartly (0/3/12/25 s by pause length), but there is no durable history of what actually happened during a listening session — no way to reconstruct a position after a crash, no way to know when a book was finished and re-started, no audit trail a future sync or listening-statistics feature could consume. The listening position is a single row per (book, source) that gets overwritten every time, so the product has no memory of listening *transitions* at all.

The root causes: (1) position history (`SeekHistory`) is in-memory only — it dies with the process; (2) all listening state collapses into one current-state row (`PlaybackProgressEntity`) that overwrites itself, so transitions (seek, chapter change, completion, source switch, timer stop) are never recorded; (3) there is no event model, so nothing downstream (sync diffs, listening intelligence, personalization) has data to consume; (4) completion and re-listening are only ever the current flag, never a cycle.

## Solution

From the listener's perspective, the app keeps a **durable, bounded memory of listening transitions** alongside the current position. The current position stays exactly as it is today (fast, per-source, authoritative), but every meaningful transition is appended to a small event log that survives restarts. That makes «Повернутися» work even after the app was closed, gives the product honest data about listening cycles (finish → re-listen), and lays the foundation for sync, listening statistics, and personalized recommendations — without changing how playback feels today.

The model is deliberately **hybrid**: the existing `PlaybackProgressEntity` remains the single source of truth for "where am I now" (the read model for the UI), and a new capped append-only `playback_events` table records *discrete transitions* only. No event sourcing, no replay-to-reconstruct — the state row is authoritative; the log is history that feeds undo, sync, and analytics.

## User Stories

1. As a listener, I want «Повернутися» to still work after I close and reopen the app, so that a big accidental skip is never irreversible.
2. As a listener, I want the undo offer to appear exactly once after a big jump (5+ minutes), so that I never lose my place twice.
3. As a listener, I want my position to resume per source as it does today, so that switching between 4read and Sluhay never resets progress.
4. As a listener, I want the smart rewind on resume to keep working across restarts, so that a long pause never replays the same rewind twice.
5. As a listener, I want finishing a book to be remembered even after I start it again, so that my listening history shows the full cycle (finished → re-listening).
6. As a listener, I want re-listening a finished book to start from the beginning without me having to rewind, so that a fresh listen is one tap.
7. As a listener, I want the app to remember chapter changes, timer stops, and source switches, so that my position history tells the true story of a session.
8. As a listener, I want the event log to stay small no matter how much I listen, so that my storage and DB never grow unboundedly.
9. As a maintainer, I want every event write to flow through the existing position-persistence path, so that capture is one call site, not scattered hooks.
10. As a maintainer, I want the event kinds to be stable strings, so that a future sync does not depend on enum ordinals.
11. As a maintainer, I want conflict resolution to be last-writer-wins on the state row with a deterministic tiebreak, so that concurrent devices merge without vector clocks.
12. As a maintainer, I want sync (later) to be able to diff events since a last-sync watermark, so that #56 gets a real delta source.
13. As a maintainer, I want listening intelligence and personalization (later) to consume event history, so that #60 and #62 have honest input data.
14. As a maintainer, I want the daily listening aggregate (`listening_stats`) untouched, so that statistics remain a separate, already-correct concern.
15. As a maintainer, I want playback failures to stay in their own ledger, so that errors are never mixed into position history.
16. As a maintainer, I want the schema migration 8→9 to be explicit and tested against a real v8 database, so that no user data is silently wiped.

## Implementation Decisions

- **Hybrid model — state row authoritative, event log as history.** `PlaybackProgressEntity` (bookId + sourceKey, chapterIndex, positionSeconds, lastListenedAt, isCompleted, lastPausedAtEpochMs) remains the only read model for "where am I now". It is never reconstructed from events. The event log is append-only, capped, and consumed only by undo, future sync, and future intelligence.
- **One new table, Room migration 8→9.** `playback_events(id autoincrement, bookId, sourceKey="", kind: String, chapterIndex, positionSeconds, fromPositionSeconds=null, timestamp, deviceId="")`, indices on `(bookId)`, `(sourceKey)`, `(timestamp)`. `kind` is a stable String; `deviceId` stays `""` until sync (#56) lands. `fromPositionSeconds` is set only for `SEEK`/`SOURCE_SWITCH` (the pre-jump position that undo returns to).
- **Only discrete transitions persist.** Kinds: `RESUME`, `PAUSE`, `SEEK` (jump ≥ 5 min — the same threshold as SeekHistory, wayfinder #25), `CHAPTER_CHANGE`, `TIMER_STOP`, `COMPLETED`, `RELISTEN`, `SOURCE_SWITCH`. Explicitly **not** persisted: periodic position ticks, sub-threshold seeks, play/pause shorter than a minute — noise, not history.
- **Capture at one call site — the repository's position persistence.** The repository's `updateProgress(bookId, chapterIndex, positionSeconds, sourceKey)` keeps writing the state row; the player already funnels every state change through it. Event capture happens in the same path: the repository gains `recordPlaybackEvent(bookId, sourceKey, kind, chapterIndex, positionSeconds, fromPositionSeconds)` which appends to the log and runs compaction. The player's transition points (seek with history, pause, resume, chapter change, timer stop, completion, source switch, relisten reset) call it; the player does not touch the DAO directly.
- **Undo semantics — SeekHistory becomes a facade over the log.** The undo candidate for (book, source) is the latest `SEEK`/`SOURCE_SWITCH` event with a ≥ 5 min jump, queried from the log; an in-memory cache serves the current session, the log serves restart. Undoing writes a new `SEEK` event back (the jump back is itself a transition). One undo only — the latest candidate — consistent with wayfinder #25.
- **Causality & concurrency — LWW, no vector clocks.** The state row is last-writer-wins by `lastListenedAt`, with a deterministic tiebreak on `(sourceKey, bookId)`. `lastPausedAtEpochMs` (smart rewind) stays on the state row in lockstep with its in-memory marker, exactly as today. Future sync (#56) diffs events from a last-sync watermark and resolves state conflicts by the same LWW rule.
- **Re-listening cycle.** Reaching the end logs `COMPLETED` (state keeps isCompleted=true). Starting playback of a completed book resets the state row (chapter 0, position 0, isCompleted=false) and logs `RELISTEN` — the completion stays in history as a finished cycle.
- **Compaction — bounded by design.** Cap 50 events per (book, source), FIFO delete beyond it; `SEEK` undo candidates older than 24 h are pruned (undoing yesterday's jump is noise). The state row is never compacted. The 50 / 24 h numbers are implementation defaults, not requirements. Daily `listening_stats` and the `playback_failures` ledger (wayfinder #52) are untouched and separate.
- **Source switch.** Events carry `sourceKey`; a switch is the last event on the old key followed by the first on the new. Cross-source position continuity remains the existing "max by lastListenedAt" rule of the library card (spec-10 T2) — not duplicated here.
- **Device switch.** Not a separate local event; `deviceId` on every event covers it. Actual multi-device merge behavior is #56's scope; this spec only fixes the model so #56 can diff.

## Testing Decisions

- **What makes a good test:** external behaviour — a big seek is undoable after restart; a small seek is not; completion followed by play is a finish→relisten cycle; the log never exceeds its cap; the state row is unaffected by compaction; migration 8→9 preserves a real v8 database. Fixtures and fake DAOs pin the logic; no device needed.
- **Modules tested:**
  - **Pure JVM model tests** — which transitions persist (thresholds, noise filtering), compaction rules (cap, 24 h prune), undo-candidate selection (latest ≥ 5 min per book/source), relisten reset, LWW tiebreak. Prior art: `SeekHistoryTest`, `LibraryModelTest`.
  - **Repository seam tests** — `recordPlaybackEvent` / `lastUndoCandidate` / `compactPlaybackEvents` through `FakeAudiobookDao` (existing), plus real-Room tests in `AudiobookRepositoryRoomTest` style covering the migration 8→9 path.
  - **Migration test** — open a real v8 database and run `MIGRATION_8_9`, mirroring the existing `MIGRATION_5_6` / `MIGRATION_6_7` / `MIGRATION_7_8` migration tests.
  - **Player integration tests** — `AudioPlayerManagerTest` (existing harness) asserts the transition points (seek ≥ threshold, pause, resume, chapter change, completion, relisten) append the right events through the repository.
- **Prior art:** `SeekHistoryTest` (pure history logic), `LibraryModelTest` (pure model), repository seam tests with `FakeAudiobookDao`, `AudiobookRepositoryRoomTest` migration tests, and the player test harness with its recording engine.

## Out of Scope

- Sync implementation itself — ticket #56 (this spec only fixes the model it diffs).
- Listening Intelligence features (#60), personalized Listen (#62), sleep-timer UX upgrades (#27) — consumers of the log, not this spec.
- Full event sourcing / replay-to-reconstruct — explicitly rejected; the state row is authoritative.
- Recording high-frequency position ticks or per-second telemetry.
- Changing `listening_stats` aggregation or the `playback_failures` ledger.
- Multi-device merge behavior, account identity, or `deviceId` provisioning — #56.

## Further Notes

- Reuses the 5-minute jump threshold from wayfinder #25 (`SeekHistory.DEFAULT_JUMP_THRESHOLD_MS`) — one constant, no divergence.
- The smart-rewind marker (`lastPausedAtEpochMs`) stays on the state row; the event log does not duplicate it (a `PAUSE` event records the transition, the rewind policy stays in #25's logic).
- The event log is the single future delta source for #56: sync diffs = events since the last-sync watermark; state conflicts = LWW on `lastListenedAt`.
- Migration 8→9 must be explicit and tested (the repo's audit flagged destructive fallback as data-loss risk — SF-007); never `fallbackToDestructiveMigration` for this change.
- Compaction parameters (50 events, 24 h) are defaults chosen by the implementer; only the *boundedness* is a requirement.
