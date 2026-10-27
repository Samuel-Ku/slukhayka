# UI System Module

<!-- Generated: 2026-08-16 | Updated: 2026-10-27 (2026 direction «Папір і голос», ADR-0033 stays the legacy vocabulary) -->

## Purpose

Design tokens and the shared components every screen composes. Two vocabularies
live here side by side: the legacy `Color`/`Type`/`Dimens` + v1.4 components
(ADR-0033) and the **2026 direction «Папір і голос»** (`PaperInk*`). Read this
before changing colors, typography or any cross-screen component; for the 2026
direction the contract lives in the design spec, not in this map.

## Key Files

```
app/src/main/java/com/slukhayka/audiobooks/ui/theme/Color.kt          78 lines  (palette)
app/src/main/java/com/slukhayka/audiobooks/ui/theme/Type.kt          123 lines  (typography)
app/src/main/java/com/slukhayka/audiobooks/ui/theme/Theme.kt         112 lines  (AudiobookTheme, dark scheme)
app/src/main/java/com/slukhayka/audiobooks/ui/theme/Dimens.kt         39 lines  (spacing/sizes, SpaceAboveMiniPlayer)
app/src/main/java/com/slukhayka/audiobooks/ui/theme/PaperInk.kt      464 lines  (2026 palette, type scale, dimens, shapes, schemes)
app/src/main/java/com/slukhayka/audiobooks/ui/theme/PaperInkTheme.kt  74 lines  (PaperInkTheme, PaperInkThemedContent, LocalPaperInkDesign, LocalContentDensity)
app/src/main/java/com/slukhayka/audiobooks/ui/theme/AppearancePreferences.kt 81 lines (theme mode, density, player-always-ink persistence)
app/src/main/java/com/slukhayka/audiobooks/ui/theme/ColorContrast.kt  (contrast checks used by the schemes)

app/src/main/java/com/slukhayka/audiobooks/ui/components/paperink/PaperInkCards.kt      (ScreenTitle, SectionHeader, Progress, HeroCard, PosterCard, BookRow)
app/src/main/java/com/slukhayka/audiobooks/ui/components/paperink/PaperInkChrome.kt     (PaperInkMiniPlayerBar, PaperInkNavigationBar/NavItem)
app/src/main/java/com/slukhayka/audiobooks/ui/components/paperink/PaperInkCover.kt     (flat paper cover + ground)
app/src/main/java/com/slukhayka/audiobooks/ui/components/paperink/PaperInkTabHeader.kt (tab header with wordmark, optional gear/back)

app/src/main/java/com/slukhayka/audiobooks/ui/components/PosterCard.kt        ~370 lines  (v1.4 C2 — the ONE 120×168 poster)
app/src/main/java/com/slukhayka/audiobooks/ui/components/BookRow.kt            ~330 lines  (v1.4 C3 — the ONE flat list row)
app/src/main/java/com/slukhayka/audiobooks/ui/components/OpenWebSourceRow.kt    ~60 lines   (v1.4 E2 — the ONE browser-door row, BookRow-styled)
app/src/main/java/com/slukhayka/audiobooks/ui/components/MetadataChip.kt        ~120 lines  (v1.4 C4 — language/source/plain chip)
app/src/main/java/com/slukhayka/audiobooks/ui/components/SectionHeaders.kt      ~100 lines  (v1.4 C1 — two-level AppSectionHeader)
app/src/main/java/com/slukhayka/audiobooks/ui/components/AppTabHeader.kt        ~70 lines   (v1.4 C5 — one tab-header model)
app/src/main/java/com/slukhayka/audiobooks/ui/components/DesignSystem.kt         141 lines  (canonical EmptyState / EmptyStateRow)
app/src/main/java/com/slukhayka/audiobooks/ui/components/CatalogCoverImage.kt
app/src/main/java/com/slukhayka/audiobooks/ui/components/BookCoverImage.kt       139 lines  (Coil)
app/src/main/java/com/slukhayka/audiobooks/ui/components/IndexScreenScaffold.kt  (one pushed-index chrome)
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
  count subtitle + actions); its screens render the canonical
  `EmptyState` directly (T13, #574 — no per-screen state facades).
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

### 2026 direction «Папір і голос» (`ui/theme/PaperInk*`, `ui/components/paperink/`)

The direction merged in #1490. It is a **second visual system**, not a restyle of
the v1.4 one: unmigrated surfaces keep their legacy branch until they are moved,
and the seam is explicit.

- `PaperInkTheme(darkTheme)` — «Папір» (`#F1EEE4`) / «Чорнило» (`#0E1411`).
  Sound accent (progress, play, current chapter) is the lamp role
  (`#A96A12` / `#EFC36C`, tertiary); quiet actions are verdigris (primary).
- `PaperInkThemedContent` picks the scheme from the surface it wraps; useful
  when a nested surface's background is not the app's current theme.
- `LocalPaperInkDesign` — the migration seam. A shared component renders the
  paper branch only when this flag is true; **never restyle a shared component
  unconditionally** — an ungated change repaints every legacy screen and churns
  golden snapshots.
- `PaperInkType` — Literata for titles, Onest for UI (ScreenTitle 34/36,
  SectionTitle 21/26, BookTitleHero 24/29, BookTitlePage 25/31,
  BookTitlePlayer 28/32, RowTitle 15/20, Body 16/24, Meta 13.5, Label 12,
  Micro 11, Timer/TimerXL tnum).
- `PaperInkDimens` / `PaperInkShapes` — SpacePage 20, SpaceSection 32,
  covers 48/58/84/104/132/272, row 112, mini player 64, seek 44, play 72.
- `AppearancePreferences` — persisted «Авто / Папір / Чорнило», «Зручно / Компактно»
  and the player-always-ink exception (decision A: the player is always ink).
- `paperink/` primitives are the preferred names for new screens; the legacy
  host components (`BookRow`, `PosterCard`, `NavigationChip`, `SectionHeaders`,
  `DesignSystem`, `MiniPlayerBar`) carry gated paper branches and keep their
  old test tags/semantics.

Source of truth, including per-screen decisions and the accepted gaps:
`docs/specs/2026-10-26-ui-refresh-2027.md`; the browsable prototype and frames:
`docs/prototypes/2026-ui-refresh/`. Golden snapshots:
`app/src/test/snapshots/` (Roborazzi, recorded on the worker; the nine
drift-prone frames are pinned by `SnapshotGoldenBytesGuardTest`).

## Dependencies

- **Inbound:** `ui/screens/*`, `MainActivity` (MiniPlayerBar in Scaffold)
- **Outbound:** `androidx.compose.*`, `androidx.compose.material3`, Coil,
  `player.AudioPlayerManager` (overlay reads PlayerState / PlaybackEventLog)

## Common Tasks

| Task | Touch |
|---|---|
| Change brand color / palette (legacy screens) | `Color.kt` + `Theme.kt` |
| Change the 2026 direction | `PaperInk.kt` + `paperink/` primitives — and keep the change behind `LocalPaperInkDesign` for shared components |
| Move another screen to the 2026 direction | wrap it in `PaperInkThemedContent` / `PaperInkTheme`, add the paper branch, record its goldens |
| Change type scale | `Type.kt` |
| Add a shared component | `components/` — but first check the v1.4 canonical set (ADR-0033); a new variation without collapsing an old one is a defect |
| Tune spacing | `Dimens.kt` |
| Add a debug diagnostic panel | extend `PlayerDebugOverlay.kt` |

## Known Issues / Notes

- `PlayerDebugOverlay` is ~371 lines of debug-only UI — keep it behind
  `BuildConfig.DEBUG`; it is the largest single component.
- Snapshot tests pin the design system in
  `app/src/test/java/com/slukhayka/audiobooks/ui/snapshots/` (45 classes,
  143 goldens in `app/src/test/snapshots/`) — run them after theme changes.
- Two vocabularies coexist on purpose: a screen either renders legacy or paper.
  Do not mix them inside one component branch, and do not remove the legacy
  branch while its screens are unmigrated.
- Compact density shrinks rows/posters/covers, not touch targets.
- The paper prototype is a browser imitation, not a Compose render; the
  implemented truth is the snapshot set.
