# UI System Module

<!-- Generated: 2026-08-16 | Files scanned: 13 | Kotlin lines: ~1,640 -->

## Purpose

Design tokens and the shared components every screen composes: theming
(Color/Type/Dimens), the floating MiniPlayerBar, dialogs/sheets, the debug
overlay, plus two tiny pure UI helpers. Read this before changing colors,
typography or any cross-screen component.

## Key Files

```
app/src/main/java/com/slukhayka/audiobooks/ui/theme/Color.kt          78 lines  (palette)
app/src/main/java/com/slukhayka/audiobooks/ui/theme/Type.kt          123 lines  (typography)
app/src/main/java/com/slukhayka/audiobooks/ui/theme/Theme.kt         112 lines  (AudiobookTheme, dark scheme)
app/src/main/java/com/slukhayka/audiobooks/ui/theme/Dimens.kt         39 lines  (spacing/sizes)

app/src/main/java/com/slukhayka/audiobooks/ui/components/MiniPlayerBar.kt        166 lines
app/src/main/java/com/slukhayka/audiobooks/ui/components/DesignSystem.kt         161 lines  (shared surfaces/cards)
app/src/main/java/com/slukhayka/audiobooks/ui/components/BookCoverImage.kt       139 lines  (Coil)
app/src/main/java/com/slukhayka/audiobooks/ui/components/BookmarkDialog.kt       120 lines
app/src/main/java/com/slukhayka/audiobooks/ui/components/SleepTimerSheet.kt      121 lines
app/src/main/java/com/slukhayka/audiobooks/ui/components/SpeedSheet.kt           139 lines
app/src/main/java/com/slukhayka/audiobooks/ui/components/PlayerDebugOverlay.kt   371 lines

app/src/main/java/com/slukhayka/audiobooks/ui/BookDisplay.kt          40 lines  (displayAuthor extension)
app/src/main/java/com/slukhayka/audiobooks/ui/DurationBooks.kt        32 lines  (short/long bucket DTO)
```

## What Lives Here

### Theme (`ui/theme/`)
- `AudiobookTheme` (Compose, light+dark, dynamic color opt-in), the typography
  scale in `Type.kt`, the color roles in `Color.kt`, spacing/dimens in `Dimens.kt`.

### Components (`ui/components/`)
- `MiniPlayerBar` — the persistent floating bar in the Scaffold bottom area;
  shows current book + play/pause/skip, tap opens the full `PlayerScreen`.
- `DesignSystem` — shared card/surface composables reused across screens.
- `BookCoverImage` — Coil image with placeholder/fallback handling.
- `BookmarkDialog`, `SleepTimerSheet`, `SpeedSheet` — modal overlays driven by
  `PlayerScreen` state.
- `PlayerDebugOverlay` — debug-only overlay exposing player internals
  (state, events, playback metrics).

### UI helpers (`ui/` root)
- `AudiobookEntity.displayAuthor` — blanks seeded placeholder authors
  ("4read.org", …) so screens don't repeat the source name under every title.
- `DurationBooks(short, long)` — the «За тривалістю» row buckets from the
  pure `DurationBuckets` module.

## Dependencies

- **Inbound:** `ui/screens/*`, `MainActivity` (MiniPlayerBar in Scaffold)
- **Outbound:** `androidx.compose.*`, `androidx.compose.material3`, Coil,
  `player.AudioPlayerManager` (overlay reads PlayerState / PlaybackEventLog)

## Common Tasks

| Task | Touch |
|---|---|
| Change brand color / palette | `Color.kt` + `Theme.kt` |
| Change type scale | `Type.kt` |
| Add a shared component | `components/`, register in `DesignSystem.kt` if it's a surface |
| Tune spacing | `Dimens.kt` |
| Add a debug diagnostic panel | extend `PlayerDebugOverlay.kt` |

## Known Issues / Notes

- `PlayerDebugOverlay` is ~371 lines of debug-only UI — keep it behind
  `BuildConfig.DEBUG`; it is the largest single component.
- Snapshot tests pin the design system in
  `app/src/test/java/com/slukhayka/audiobooks/ui/snapshots/` (DesignSystemSnapshotTest,
  LibraryComponentsSnapshotTest, …) — run them after theme changes.
