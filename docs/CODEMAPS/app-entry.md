# App Entry Module

<!-- Generated: 2026-08-16 | Files scanned: 4 | Kotlin lines: ~2,050 -->

## Purpose

Process-scoped composition root, the single Activity + navigation shell, and the
single ViewModel. After ADR-0002 the god-repository is gone: the five deep
modules are constructed in `App` and handed down to screens (ADR-0008), so this
module is the wiring layer you touch when adding a new dependency edge.

## Key Files

```
app/src/main/java/com/slukhayka/audiobooks/App.kt                  261 lines  (composition root)
app/src/main/java/com/slukhayka/audiobooks/MainActivity.kt         370 lines  (nav shell + AppBottomBar)
app/src/main/java/com/slukhayka/audiobooks/ui/MainViewModel.kt    1386 lines  (state + commands)
app/src/main/java/com/slukhayka/audiobooks/ui/BrowserGating.kt      30 lines  (debug-only WebView gating)
```

## Architecture

```
App.onCreate (Application)
  ├─ constructs every deep module lazily (ADR-0002):
  │    listeningState · libraryImport · sourceCatalog · offlineDownloads
  │    libraryEntries · durationEnrichment · chapterDurationProbe
  │    seriesUniverses · universeRefreshPass · storedTitleScrub · playerManager
  ├─ kicks off (Dispatchers.IO, best-effort, never blocks startup):
  │    sourceCatalog.fetchCatalogSections()
  │    storedTitleScrub.scrubOnce()          (idempotent, spec-24 T1)
  │    CuratedSeed.seed(Firestore, assets)   (no-op without Firebase keys)
  │    while(true) universeRefreshPass.runOnce(); delay(6h)   (spec-26 T7)
  └─ App.instance — late-init singleton (read by PlaybackService + tests)

MainActivity.onCreate
  ├─ enableEdgeToEdge(); Coil with allowHardware(false)
  ├─ AudiobookTheme { AudiobookApp() }
  └─ AudiobookApp:
       ├─ POST_NOTIFICATIONS runtime request (Android 13+)
       ├─ BackHandler: closes overlays before nav back
       ├─ Scaffold bottomBar = MiniPlayerBar + AppBottomBar (3 tabs)
       ├─ overlay when(...): WebSourceBrowser (DEBUG only) · SeriesScreen ·
       │    GenreScreen · Top100Screen · PersonBooksScreen · PeopleScreen ·
       │    BookDetailScreen
       └─ tab body: ListenScreen / HomeScreen / LibraryScreen
```

## Tabs (AppBottomBar, 3 items — spec #8 T4 + spec-9)

| Tab | Screen | Icon | testTag |
|---|---|---|---|
| `LISTEN` | `ListenScreen` | Headphones | `tab_listen` |
| `EXPLORE` | `HomeScreen` | Explore | `tab_explore` |
| `LIBRARY` | `LibraryScreen` | LibraryMusic | `tab_library` |

## MainViewModel Responsibilities

```kotlin
// Module handles exposed straight from App.instance (ADR-0008 — screens read
// the modules themselves; the ViewModel only wires):
val listeningState, libraryImport, sourceCatalog, offlineDownloads,
    libraryEntries, durationEnrichment, chapterDurationProbe,
    seriesUniverses, playerManager

// Navigation StateFlows
selectedTab · selectedBookId · showFullPlayer · selectedWebSource ·
selectedSeries · selectedGenre · selectedTop100 · selectedPeopleKind ·
selectedPerson

// Content StateFlows
playerState · libraryBooks · nextInSeries · listenBlocks · hiddenListenBlocks ·
recentProgress · searchQuery · selectedGenreFilter · cacheSizeFormatted

// Actions (selection of)
selectTab · selectBook · setShowFullPlayer · openWebSource/closeWebSource ·
openSeries/closeSeries · openGenre/closeGenre · openTop100/closeTop100 ·
openPeople/closePeople · openPersonBooks/closePersonBooks ·
playAudiobook · moveListenBlockUp/Down · hideListenBlock · restoreHiddenListenBlocks ·
dismissListenBook · refreshCacheSize · clearAllAudioCache
```

## Browser Gating (BrowserGating.kt)

Pure JVM enum `BrowserDestination { SYSTEM_BROWSER, IN_APP_BROWSER }`:
4read's «open on site» ALWAYS goes to the system browser; WebView-pattern
sources (sluhay) get an in-app surface in **debug builds only** (spec-15 T2).

## Dependencies

- **Inbound:** Android framework (Activity, Application)
- **Outbound:** every module — `data.*`, `player.*`, `ui.screens.*`, `ui.components.*`, `ui.theme.*`

## Common Tasks

| Task | Touch |
|---|---|
| Add a new module to the app | `App.kt` (construct), `MainViewModel.kt` (expose), screens take it as parameter (ADR-0008) |
| Add/remove a tab | `MainActivity.kt` AppBottomBar + when branch; keep `tab_*` test tags stable |
| Change back-press behavior | `MainActivity.kt` BackHandler block |
| Add a pushed overlay destination | `MainViewModel` state + `MainActivity` when-branch + BackHandler entry |
| Gate a feature by build type | `BrowserGating.kt` pattern + `BuildConfig.DEBUG` |

## Known Issues / Notes

- `MainViewModel` is the single ViewModel for the whole app (~1,400 lines) — a
  refactor candidate, but ADR-0008 already moved module reads into the screens.
- WebView browser surface is reachable only in debug builds; release routes the
  same action to the system browser in the ViewModel.
- Application id and namespace: `com.slukhayka.audiobooks` (renamed from the
  AI Studio scaffolding identity on 2026-08-16).
