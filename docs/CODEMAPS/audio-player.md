# Audio Player Module

<!-- Generated: 2026-08-16 | Files scanned: 8 | Kotlin lines: ~1,790 -->

## Purpose

ExoPlayer (Media3) playback: the manager that owns the player instance,
the background MediaSession service, and the small playback-intelligence
helpers (smart rewind, shake detection, seek history, metrics, sleep timer).

## Key Files

```
app/src/main/java/com/slukhayka/audiobooks/player/AudioPlayerManager.kt  1373 lines  (the player)
app/src/main/java/com/slukhayka/audiobooks/player/PlaybackService.kt       77 lines  (MediaSession + notification)
app/src/main/java/com/slukhayka/audiobooks/player/ShakeDetector.kt        113 lines
app/src/main/java/com/slukhayka/audiobooks/player/SeekHistory.kt           70 lines
app/src/main/java/com/slukhayka/audiobooks/player/SmartRewind.kt           51 lines
app/src/main/java/com/slukhayka/audiobooks/player/PlaybackMetrics.kt       46 lines
app/src/main/java/com/slukhayka/audiobooks/player/PlaybackEventLog.kt      39 lines
app/src/main/java/com/slukhayka/audiobooks/player/PlaybackSettings.kt      25 lines
```

## Architecture

```
App.instance.playerManager            ← single instance, app-scoped (ADR-0002)
  └─ AudioPlayerManager(context, listeningState, getPlayableChapters)
       ├─ ExoPlayer + MediaController (Media3 session, UnstableApi opt-in)
       ├─ PlayerState: StateFlow — currentBook, chapters, chapterIndex,
       │    isPlaying, positionMs, durationMs, playbackRate, sleepTimerRemaining…
       ├─ nextChapter / togglePlayPause / seekTo / setSpeed / sleep timer…
       └─ chapter materialisation via sourceCatalog.getPlayableChapters
            (ADR-0007: editions own chapters, sources own physical tracks)

PlaybackService
  └─ MediaSessionService: foreground notification with transport controls,
     keeps audio alive when the Activity/ViewModel is gone
```

## The Pairing Seam (getPlayableChapters)

The manager never builds stream URLs itself. `SourceCatalog.getPlayableChapters`
yields chapter→track pairs (ADR-0007, ADR-0008) — the player asks for a book's
playable chapters and plays what comes back; the fallback (4read page fetch)
lives on the catalog path, not in the player.

## Helpers

- `SmartRewind` — single rewind rule (ADR-0003): position-dependent rewind
  distance (e.g. small rewind near chapter start, larger mid-chapter).
- `SeekHistory` — last-N seek trail (back-jump support).
- `ShakeDetector` — shake-to-rewind gesture (spec feature).
- `PlaybackMetrics` / `PlaybackEventLog` — rolling metrics + event ring buffer
  fed to `PlayerDebugOverlay` and the `playback_events` write path.
- `PlaybackSettings` — persisted per-book speed / settings.

## Sleep Timer

`sleepTimerFadeVolume(remainingSec)` — pure function: linear 1.0→0.0 fade over
the last 30 s, unit-tested boundary math. Timer state lives on `PlayerState`
(`sleepTimerRemaining`), the fade is applied to player volume.

## Dependencies

- **Inbound:** `App` (composition root), `MainViewModel` (player commands),
  `MainActivity` (MiniPlayerBar / PlayerScreen read `playerState`)
- **Outbound:** `androidx.media3.*` (exoplayer, session), `data.db.*`
  (entities, PlaybackEvent*), `data.listening.ListeningStateStore`,
  `data.catalog.SourceCatalog` (getPlayableChapters), `data.source.headersFor`/`sourceIdForUrl`

## Common Tasks

| Task | Touch |
|---|---|
| Change rewind behavior | `SmartRewind.kt` (pure, ADR-0003) |
| Add a transport action | `AudioPlayerManager.kt` + expose from `MainViewModel` + wire button |
| Change the media notification | `PlaybackService.kt` |
| Change sleep-timer fade | `sleepTimerFadeVolume` in `AudioPlayerManager.kt` |
| Add a playback event | `data.db.PlaybackEvent*` + `PlaybackEventLog` |

## Known Issues / Notes

- `AudioPlayerManager` is the second-largest file in the app (1,373 lines) —
  split candidate (transport vs. session vs. events).
- Playback lives in the process scope: the player + service are constructed in
  `App`, not in the Activity (backgrounding no longer kills playback).
- Tests use a fake player seam: `app/src/test/java/com/slukhayka/audiobooks/player/`
  (`FakePlayerBase`, `FakePlayerEngine`, `AudioPlayerManagerTest`,
  `SleepTimerTest`, `SmartRewindTest`, `SpeedAndRewindManagerTest`).
