# Playback observability audit — wayfinder ticket «Playback observability audit» (#52)

Status: resolved 2026-08-10. Evidence: `AudioPlayerManager.kt` (886 lines),
`PlaybackService.kt` (77), `PlayerDebugOverlay.kt` (296) — main, HEAD 3c3c731.

## What the player observes today

**Single listener, one error channel.** `AudioPlayerManager` attaches exactly
one `Player.Listener` at player creation (`AudioPlayerManager.kt:167-218`):
`onPlaybackStateChanged` handles `STATE_READY` (buffer flag, duration
persistence, one-shot resume seek, autoplay) and `STATE_ENDED`
(`onChapterCompleted`); `onPlayerError` logs and routes to
`reportPlaybackFailure` (`:210-216`).

**Failure contract** (`reportPlaybackFailure`, `:321-334`): set state flags
(not playing, no stream URL, `audioEngineMode = "Playback error"`) and a
single user-facing `lastErrorMsg` — «Цю главу зараз не вдалося відтворити.
Спробуйте пізніше або інший розділ.» The specific `errorCodeName` from the
`PlaybackException` is logged (`Log.w`, `:212`) but **not carried into
PlayerState** — it is overwritten by the generic message.

**Same path for three failure sources**: `PlaybackException` (`:216`),
45 s prepare timeout (`:281`), and exception in `prepareChapter` (`:298`).

## Findings

1. **No crash/error reporting at all.** No Crashlytics (not a dependency —
   `firebase.ai`/`appcheck` are, Crashlytics is not), no analytics, no counters.
   Errors exist only in `Log.w`/`Log.e` lines (`AudioPlayerManager.kt:212,
   277, 297, 379, 417, 444, 465`). There is **no way to know** from outside
   whether the app's most failure-prone feature (stream playback) is failing —
   no counts, no per-source failure rates, no retention.
2. **User-visible error loses the cause.** `PlayerState.lastErrorMsg` is
   overwritten (`:328`) with the generic copy; the code, URL, and chapter are
   only in logcat. The `PlayerDebugOverlay` (long-press debug surface,
   `PlayerDebugOverlay.kt:187, 219-222`) shows stream URL + generic message —
   a user can report "playback failed" but not "HTTP 404 on chapter 3 of book X".
   The failure-to-success history (audit CR-002: wrong-audio hotfix) shows this
   channel matters: `reportPlaybackFailure` deliberately does **not** fabricate
   audio (`:305-320`) — observability is what would have caught the old bug.
3. **No retry policy, no metrics, no user-visible diagnosis.** The failure
   contract is «state + message»; there is no retry counter, no
   stream-switch attempt (e.g. try `m3u8` fallback), no `PlayerState`
   diagnostics beyond the string fields. `PlaybackService` is a thin
   `MediaSessionService` wrapper (`PlaybackService.kt:34-77`) that owns no
   player — observability must live in `AudioPlayerManager`, and currently
   lives nowhere.
4. **State is observable (good baseline).** `PlayerState` (a data class with
   book/chapter/isPlaying/isBuffering/audioEngineMode/lastErrorMsg, `:50`,
   `:262`) is a single source of truth in Compose + the debug overlay, and
   `onPlaybackStateChanged`+`onPlayerError` are the natural hooks for metrics.

## Recommended shape for a playback-observability ticket

1. **In-app failure ledger (no backend needed):** persist `(timestamp,
   bookId, chapterIndex, errorCodeName, streamUrl host, audioEngineMode)`
   rows in Room (new small table) on every `reportPlaybackFailure`; expose a
   «Чому не грає» surface showing the last N failures with the real code —
   this is the same pattern as the search benchmark's evidence-first approach:
   data before guesses.
2. **Structured log events:** keep a lightweight ring buffer of player events
   (prepare/ready/error/timeout/retry) with chapter+URL, exportable via the
   debug overlay («Скопіювати журнал») instead of logcat-only.
3. **Instrumentation seam:** a `PlaybackMetrics` object called from
   `onPlaybackStateChanged`/`onPlayerError` (counts per book/source, failure
   rate = failures/attempts, prepare latency, source-host distribution) — pure
   JVM-testable like `importAudioEntries`, so the fix for «мало інформації про
   збої відтворення» lands with tests.
4. **Keep the human contract:** `lastErrorMsg` should carry
   `errorCodeName` + short host hint (e.g. «Помилка сервера (HTTP 404) — s1.
   reasd.org») while `reportPlaybackFailure` stays the single funnel.

## Verdict

**BUILDABLE — the seams exist.** The player already has a single listener and a
single failure funnel; adding a Room ledger + ring buffer + metrics object is
S–M and needs no new dependencies. This is a prerequisite-quality ticket for
any of the stream-health complaints, and it is the only way to get
crash-less but broken playback (the CR-002 class of bug) visible.
