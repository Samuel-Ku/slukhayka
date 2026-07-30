# Audio Player Module

<!-- Generated: 2026-07-30 | Files scanned: 5 | Token estimate: ~700 -->

## Purpose

Plays audiobook chapters via ExoPlayer (Media3 1.3.1), persists playback progress to Room, exposes a persistent mini-player bar at the bottom of every screen, and a full-screen overlay player with chapter skip, seek, bookmark, and sleep timer.

## Entry Points

- `AudiobookRepository.playAudiobook(book, ...)` (called from screen `onPlayClick`) →
  `MainViewModel.playAudiobook` → `AudioPlayerManager.loadAndPlayBook(book, chapters, ...)`
- `MiniPlayerBar` (always-visible composable in `MainActivity` bottom bar)
- `PlayerScreen` (full-screen overlay triggered by `MainViewModel.setShowFullPlayer(true)`)

## Key Files

```
app/src/main/java/com/example/player/AudioPlayerManager.kt        526 lines
app/src/main/java/com/example/ui/screens/PlayerScreen.kt           488 lines
app/src/main/java/com/example/ui/components/PlayerDebugOverlay.kt  296 lines
app/src/main/java/com/example/ui/components/MiniPlayerBar.kt      168 lines
app/src/main/java/com/example/ui/components/SleepTimerSheet.kt    110 lines
```

## Architecture

```
[screen onPlayClick]
  └─ MainViewModel.playAudiobook(book)
       ├─ AudiobookRepository.getBookSync / getChaptersList / getProgressSync
       └─ AudioPlayerManager.loadAndPlayBook(book, chapters, initialChapterIndex, initialPositionSeconds, autoPlay)
            └─ ExoPlayer (Media3) + MediaSessionService
                 └─ position updates → MainViewModel.updateProgress → Room (PlaybackProgressEntity)
```

## Public APIs

```kotlin
// AudioPlayerManager
fun loadAndPlayBook(book, chapters, initialChapterIndex, initialPositionSeconds, autoPlay)
fun togglePlayPause()
fun nextChapter() / previousChapter()
fun selectChapter(index)
fun seekTo(ms)
fun play() / pause()
val playerState: StateFlow<PlayerState>
fun release()
```

## State (data class)

`PlayerState` lives in AudioPlayerManager.kt:
- `currentBook: AudiobookEntity?`
- `currentChapterIndex: Int`
- `chapters: List<ChapterEntity>`
- `isPlaying: Boolean`
- `currentPositionMs: Long`
- `durationMs: Long`
- `playbackSpeed: Float`
- `sleepTimerRemainingMs: Long?`

## Dependencies

- **Inbound:** `MainViewModel` (via `playerManager` field), `MainActivity` (passes playerState to MiniPlayerBar/PlayerScreen)
- **Outbound:** `AudiobookRepository` (chapters, progress), `androidx.media3.exoplayer.ExoPlayer`, Room (`PlaybackProgressEntity`)
- **Compose UI:** Material 3, Material Icons (Filled — see audit warnings for AutoMirrored migration)

## Common Tasks

| Task | Touch |
|---|---|
| Change playback behavior | `AudioPlayerManager.kt` |
| Add new player control | `PlayerScreen.kt` (composable) + state in `PlayerState` |
| Change MiniPlayerBar layout | `MiniPlayerBar.kt` |
| Add sleep timer preset | `SleepTimerSheet.kt` |
| Show debug info | `PlayerDebugOverlay.kt` (currently always-on — Phase 2 audit candidate) |

## Known Issues (Phase 2 candidates)

- `MiniPlayerBar.kt:46` — always-true condition
- `PlayerScreen.kt:444` — deprecated `Divider` (use `HorizontalDivider`)
- `PlayerDebugOverlay.kt` — always visible; consider gating on debug build only
- No notification/foreground service for background playback (Phase 2 audit)
