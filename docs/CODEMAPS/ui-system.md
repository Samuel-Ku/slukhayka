# UI System Module

<!-- Generated: 2026-08-16 | Updated: 2026-09-07 (v1.4 canonical components, ADR-0033) -->

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
app/src/main/java/com/slukhayka/audiobooks/ui/theme/Dimens.kt         39 lines  (spacing/sizes, SpaceAboveMiniPlayer)

app/src/main/java/com/slukhayka/audiobooks/ui/components/PosterCard.kt        ~370 lines  (v1.4 C2 — the ONE 120×168 poster)
app/src/main/java/com/slukhayka/audiobooks/ui/components/BookRow.kt            ~330 lines  (v1.4 C3 — the ONE flat list row)
app/src/main/java/com/slukhayka/audiobooks/ui/components/OpenWebSourceRow.kt    ~60 lines   (v1.4 E2 — the ONE browser-door row, BookRow-styled)
app/src/main/java/com/slukhayka/audiobooks/ui/components/MetadataChip.kt        ~120 lines  (v1.4 C4 — language/source/plain chip)
app/src/main/java/com/slukhayka/audiobooks/ui/components/SectionHeaders.kt      ~100 lines  (v1.4 C1 — two-level AppSectionHeader)
app/src/main/java/com/slukhayka/audiobooks/ui/components/AppTabHeader.kt        ~70 lines   (v1.4 C5 — one tab-header model)
app/src/main/java/com/slukhayka/audiobooks/ui/components/DesignSystem.kt         141 lines  (canonical EmptyState / EmptyStateRow)
app/src/main/java/com/slukhayka/audiobooks/ui/components/CatalogCoverImage.kt
app/src/main/java/com/slukhayka/audiobooks/ui/components/BookCoverImage.kt       139 lines  (Coil)
app/src/main/java/com/slukhayka/audiobooks/ui/components/IndexScreenScaffold.kt  (one pushed-index chrome; Secondary states)
app/src/main/java/com/slukhayka/audiobooks/ui/components/NavigationChip.kt
app/src/main/java/com/slukhayka/audiobooks/ui/components/BookmarkDialog.kt       120 lines
app/src/main/java/com/slukhayka/audiobooks/ui/components/SleepTimerSheet.kt      121 lines
app/src/main/java/com/slukhayka/audiobooks/ui/components/SpeedSheet.kt           139 lines
app/src/main/java/com/slukhayka/audiobooks/ui/components/PlayerDebugOverlay.kt   371 lines
app/src/main/java/com/slukhayka/audiobooks/ui/components/MiniPlayerBar.kt        166 lines
app/src/main/java/com/slukhayka/audiobooks/ui/components/CastButton.kt
app/src/main/java/com/slukhayka/audiobooks/ui/components/UpdateBanner.kt

app/src/main/java/com/slukhayka/audiobooks/ui/BookDisplay.kt          40 lines  (displayAuthor extension)
app/src/main/java/com/slukhayka/audiobooks/ui/DurationBooks.kt        32 lines  (short/long bucket DTO)
```

## What Lives Here

### Theme (`ui/theme/`)
- `AudiobookTheme` (Compose, light+dark, dynamic color opt-in), the typography
  scale in `Type.kt`, the color roles in `Color.kt`, spacing/dimens in `Dimens.kt`.

### Components (`ui/components/`)
The v1.4 canonical vocabulary (ADR-0033) is a closed set — new surfaces build
on these, never a fourth card/chip/header. The old twins (CatalogBookCard,
CompactBookCard, UnifiedCatalogCard, CollectionBookCard, CatalogSeriesCard,
AudiobookListItem, LanguageBadge, SourceBadgePill, TagPill, OverviewGroupHeader,
CatalogRowHeader, AuthorDiscoveryScaffold) are deleted, not deprecated.
- `PosterCard` — the ONE portrait card (120×168): title/author/duration/
  progress-hairline/caption/dismiss/download slots + entity and
  GlobalSearchResult convenience overloads.
- `CycleCard` — the ONE landscape series card (in PosterCard.kt).
- `BookRow` — the ONE flat list row: 64 dp cover (URL or entity), genre,
  badges, stats, progress hairline, leading/trailing slots, footnote slot;
  entity overload carries the library-list row contract.
- `MetadataChip` — the ONE non-interactive chip (language/source/plain slots).
- `AppSectionHeader` — the ONE two-level section header (group/section,
  count + action slots) in SectionHeaders.kt.
- `AppTabHeader` — the ONE tab-header model (title + optional action + the
  collapsible search pattern on Огляд/Медіатека).
- `DesignSystem` — the canonical `EmptyState` / `EmptyStateRow`.
- `IndexScreenScaffold` — the ONE pushed-index chrome (title + optional
  count subtitle + actions); hosts the index empty/loading/message states.
- `CatalogCoverImage` / `BookCoverImage` — cover rendering (URL / entity).
- `MiniPlayerBar` — the persistent floating bar; `BookmarkDialog`,
  `SleepTimerSheet`, `SpeedSheet` — modal overlays; `PlayerDebugOverlay` —
  debug-only internals; `CastButton`, `UpdateBanner`, `NavigationChip`,
  `Accessibility` — misc chrome.

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
| Add a shared component | `components/` — but first check the v1.4 canonical set (ADR-0033); a new variation without collapsing an old one is a defect |
| Tune spacing | `Dimens.kt` |
| Add a debug diagnostic panel | extend `PlayerDebugOverlay.kt` |

## Known Issues / Notes

- `PlayerDebugOverlay` is ~371 lines of debug-only UI — keep it behind
  `BuildConfig.DEBUG`; it is the largest single component.
- Snapshot tests pin the design system in
  `app/src/test/java/com/slukhayka/audiobooks/ui/snapshots/` (DesignSystemSnapshotTest,
  LibraryComponentsSnapshotTest, …) — run them after theme changes.
