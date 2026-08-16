# Codemaps Index

<!-- Generated: 2026-08-16 | Modules: 8 | Files scanned: 119 | Kotlin lines: ~26,750 -->

Token-lean architecture documentation for the audiobook player app.
Each file covers one bounded area of the codebase. Read them in this order for a complete mental model.

## Modules

| # | File | What | Files | Lines |
|---|---|---|---|---|
| 1 | [app-entry.md](app-entry.md) | `App` composition root (ADR-0002), MainActivity navigation shell, MainViewModel | 4 | ~2,050 |
| 2 | [ui-system.md](ui-system.md) | Design tokens (Color/Theme/Type/Dimens) + shared components (MiniPlayerBar, dialogs, sheets, overlay) | 13 | ~1,640 |
| 3 | [ui-screens.md](ui-screens.md) | All 14 Compose screens + the 7 `ui/library/` helpers behind them | 21 | ~8,190 |
| 4 | [audio-player.md](audio-player.md) | AudioPlayerManager (ExoPlayer), PlaybackService (MediaSession), smart rewind, shake, sleep timer | 8 | ~1,790 |
| 5 | [data-core.md](data-core.md) | Room DB (v19, 19 tables), DAO, SourceCatalog (browse/sync/search), LibraryEntries, duration passes | 22 | ~5,750 |
| 6 | [data-import.md](data-import.md) | LibraryImport — the five import doors, folder rescan, curated + live smart collections | 13 | ~2,290 |
| 7 | [data-sources.md](data-sources.md) | SourceAdapter seam + 6 adapters (4read, soundbooks, audiobookmp3, lihtar, sluhayua, sluhay), WebView capture | 12 | ~2,050 |
| 8 | [data-enrichment.md](data-enrichment.md) | Series universes (Wikidata + Firestore + ML Kit), on-device recommendations (ONNX), home widget (Glance) | 26 | ~2,990 |

## Total Source Size

- **Kotlin source:** ~26,750 lines across **119 files** in `app/src/main/`
- **DB:** 19 Room tables, schema version 19 (`app/schemas/`)
- **Sources:** 6 playable adapters behind one seam
- **Tests:** 114 unit tests (`app/src/test/`) + 1 instrumented Espresso test (`app/src/androidTest/`)

## How to Read

Start with **`app-entry.md`** for the top-down mental model — every module is
constructed there and handed to the screens (ADR-0002, ADR-0008).

Then read **`data-core.md`** (the domain: Work/Edition/Source model, Room schema,
catalog browsing — almost everything depends on it) and **`data-sources.md`**
(the adapter seam the catalog, import and enrichment all ride).

**`data-import.md`** and **`data-enrichment.md`** are the two deepest feature
areas (ingestion + series universes/recommendations). **`audio-player.md`** is
orthogonal — same DB, separate surface. **`ui-system.md`** is design tokens +
shared components, read it before any theming work; **`ui-screens.md`** is the
largest module and the natural home for most UI bug fixes.

## Freshness

- Generated: 2026-08-16
- Source commit scanned: current `main` after the repo-cleanup (ADR-0016) and
the package rename to `com.slukhayka.audiobooks`
- Refresh rule: regenerate when the module-to-file mapping drifts by more than a
  few files, or after a feature that adds/removes a package under `app/src/main/`.
  The per-file header carries the scan date and line counts — update them on refresh.

## Tools & Targets

- Build: Gradle 9.3.1 + AGP 9.1.1, Kotlin 2.2.10, KSP, Room 2.7.0
- Snapshots: Roborazzi 1.59.0 (tests in `app/src/test/java/com/slukhayka/audiobooks/ui/snapshots/`)
- Coverage: Kover 0.9.9; secrets-gradle-plugin + google-services (Firebase, optional)
- Min / target SDK: 24 / 36 (compileSdk 36, minor API 1)
- Application id / namespace: `com.slukhayka.audiobooks`
