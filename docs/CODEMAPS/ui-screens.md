# UI Screens Module

<!-- Generated: 2026-08-16 | Files scanned: 21 | Kotlin lines: ~8,190 -->

## Purpose

Every Compose screen plus the `ui/library/` helpers they compose. This is the
largest module in the app (~30 % of all Kotlin). Per ADR-0008 the screens read
the deep modules themselves — they receive module instances as constructor
parameters from the composition root, not through the ViewModel.

## Key Files

```
app/src/main/java/com/example/ui/screens/HomeScreen.kt            1395 lines
app/src/main/java/com/example/ui/screens/BookDetailScreen.kt      1405 lines
app/src/main/java/com/example/ui/screens/LibraryScreen.kt          985 lines
app/src/main/java/com/example/ui/screens/PlayerScreen.kt           943 lines
app/src/main/java/com/example/ui/screens/ListenScreen.kt           852 lines
app/src/main/java/com/example/ui/screens/WebSourceBrowserScreen.kt 595 lines
app/src/main/java/com/example/ui/screens/GlobalSearchResults.kt    242 lines
app/src/main/java/com/example/ui/screens/Top100Screen.kt           236 lines
app/src/main/java/com/example/ui/screens/PeopleScreen.kt           205 lines
app/src/main/java/com/example/ui/screens/SeriesScreen.kt           201 lines
app/src/main/java/com/example/ui/screens/SourceFeeds.kt            152 lines
app/src/main/java/com/example/ui/screens/BookListScreen.kt         132 lines
app/src/main/java/com/example/ui/screens/GenreScreen.kt             38 lines
app/src/main/java/com/example/ui/screens/PersonBooksScreen.kt       37 lines

app/src/main/java/com/example/ui/library/LibraryModel.kt   219 lines  (LibraryBook model)
app/src/main/java/com/example/ui/library/ListenComposer.kt 211 lines  (reorderable listen blocks)
app/src/main/java/com/example/ui/library/ListenPrefsStore.kt  82 lines
app/src/main/java/com/example/ui/library/BookPlayButton.kt   78 lines
app/src/main/java/com/example/ui/library/OutcomeMessages.kt  74 lines
app/src/main/java/com/example/ui/library/BookTimeline.kt     52 lines
app/src/main/java/com/example/ui/library/ResumeStart.kt      53 lines
```

## Screen Map

| Screen | Opened from | Purpose |
|---|---|---|
| `ListenScreen` | Tab «Слухати» (first tab, spec-9) | Listening panel: 8 reorderable blocks (continue, next-in-series, recently added, …) via `ListenComposer`; «відкрити джерело» CTA for WebView sources |
| `HomeScreen` | Tab «Огляд» | Storefront: sections, genres, series, top-100, people, duration buckets, smart collections, unified catalog, source feeds |
| `LibraryScreen` | Tab «Медіатека» | Own library: works/editions, downloads, favourites, listening stats |
| `BookDetailScreen` | Any book card | Work page: editions (rendition cards, ADR-0011), chapters, «Інші начитки», related books, series-universe header |
| `PlayerScreen` | MiniPlayerBar tap | Full player: transport, bookmark dialog, sleep timer, speed sheet, debug overlay |
| `SeriesScreen` | Series link | Series/cycle page with universe membership |
| `GenreScreen` | Genre chip | «Аудіокниги жанру» listing |
| `Top100Screen` | Огляд | `/top-100.html` listing |
| `PeopleScreen` / `PersonBooksScreen` | Огляд | Виконавці/Автори index + one person's books |
| `GlobalSearchResults` | Search | Cross-source search results (merged) |
| `SourceFeeds` | Огляд | «Нове з кожного джерела» feed per adapter |
| `WebSourceBrowserScreen` | Listen CTA (debug only) | In-app WebView for Cloudflare-bound sources (sluhay) |
| `BookListScreen` | Series/Genre reuse | Generic book grid |

## Library Helpers (`ui/library/`)

- `LibraryModel` — `LibraryBook` UI model mapping DB rows (editions, sources,
  download state) to cards.
- `ListenComposer` — the spec-15 «Слухати» block engine: 8 named blocks,
  reorderable/hideable via `ListenPrefsStore`; `BlockId` set is unit-tested.
- `BookPlayButton` / `BookTimeline` / `ResumeStart` / `OutcomeMessages` —
  small card widgets: play affordance, chapter progress bar, resume-position
  decision, and per-action user-facing message strings.

## Dependencies

- **Inbound:** `MainActivity` (tab + overlay dispatch), navigation state from `MainViewModel`
- **Outbound:** deep modules as parameters — `libraryEntries`, `sourceCatalog`,
  `listeningState`, `offlineDownloads`, `durationEnrichment`,
  `chapterDurationProbe`, `seriesUniverses`, `playerManager` (via ViewModel for player commands); `ui/theme/*`, `ui/components/*`, `ui/library/*`

## Common Tasks

| Task | Touch |
|---|---|
| Fix a visible screen bug | The screen file itself — most screens are self-contained composables reading module StateFlows |
| Change a card layout | Shared card composables in `ui/components/DesignSystem.kt` + per-screen cards |
| Reorder the Слухати panel | `ListenComposer.kt` block order + `MainViewModel` reorder actions |
| Add a new section to Огляд | `HomeScreen.kt` + `SourceCatalog` cache for the data |

## Known Issues / Notes

- Home/BookDetail/Player screens are each >900 lines — split candidates; keep
  their `testTag`s stable (Roborazzi snapshots + UI tests pin them).
- The screens' module parameters are wired once in `MainActivity` — new screens
  should follow the same parameter pattern instead of reaching for `App.instance`.
