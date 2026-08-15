status: accepted
---

# MainViewModel thins: screens observe the five modules directly

The five-module split (ADR-0002, #140) left MainViewModel re-exposing the
modules through a forwarding layer: ~14 members were zero-depth
`module.X.stateIn(...)` re-exposures and ~7 were pure 1:1 forwarders
(`viewModelScope.launch { module.X() }`). A 1447-line ViewModel that merely
passes through what the modules already expose is dead weight: every
forwarding StateFlow added a second subscription site and every forwarder
added a function to grep, test and keep in sync.

## Decision

MainViewModel drops the forwarding layer entirely. Screens read module flows
directly and call module suspend functions directly — the precedent already
set by `playerManager`, a public field screens call through:

- **Injection idiom (#154, the tracer bullet)** — a screen receives the
  specific modules it reads from as composable parameters, wired from the
  top-level app composable out of the single adapter (the ViewModel's public
  module fields, `viewModel.libraryEntries` / `viewModel.listeningState` /
  `viewModel.sourceCatalog` / `viewModel.offlineDownloads`). LibraryScreen
  is the reference: `LibraryScreen(viewModel, libraryEntries,
  listeningState, ...)` — orchestration and navigation stay on the ViewModel,
  data reads come in as parameters. HomeScreen follows as batch 2 (#156):
  `HomeScreen(viewModel, sourceCatalog, ...)`; ListenScreen as batch 3 (#158):
  `ListenScreen(viewModel, libraryEntries, sourceCatalog, ...)`;
  BookDetailScreen and PlayerScreen as batch 4 (#159):
  `BookDetailScreen(viewModel, listeningState, offlineDownloads, ...)` and
  `PlayerScreen(viewModel, libraryEntries, ...)`. All screens now read module
  data through parameters; nothing is left to migrate.
- **Flows** — screens collect the module's cold/state flow in place:
  `module.flow.collectAsState(initial = ...)` for cold flows (an initial
  value is required), `.collectAsState()` for the module's own StateFlows.
  A flow built by a function (`listeningState.getAllListeningStats()`) is
  `remember`ed once per composition so recomposition never restarts the
  subscription.
- **Suspend calls** — user actions run on the composition scope:
  `rememberCoroutineScope().launch { module.suspendFun(...) }`.
- **No per-screen ViewModels** — the app is a single activity with hand-rolled
  navigation; per-screen VMs would add NavBackStackEntry machinery for no
  benefit and fork the shared flows' subscription.

MainViewModel keeps only what has depth:

- **Composition** — `libraryBooks`, `listenBlocks`, `hiddenListenBlocks`,
  `recentProgress`, `selectedBook`/`selectedBookChapters`/`selectedBookBookmarks`,
  `catalogDownloadProgress`/`catalogDownloadedKeys`, `nextInSeries`,
  `recommendedBooks`, `workFeed` — combine/map/flatMapLatest over module
  flows, still hot via `stateIn`/`cachedIn`.
- **Navigation** — `selectedTab`, the pushed-surface selections (book,
  web-source, series, genre, top-100, people, person), `showFullPlayer`.
- **Orchestration** — debounced search, stale-result guards, download/import
  message composition, the resume start-position decision, listen-pref
  wiring, the background embedding pass, and the actions that span modules
  (delete/remove stop the player and clear navigation; imports compose a
  message and remember the tree URI).

The pure parts of that orchestration were extracted as platform-free
functions with JVM tests (prior art: LibraryModel, ListenComposer):

- `OutcomeMessages` — the download/import/rescan outcome strings
  (`downloadOutcome`, `importOutcome`, `rescanOutcome`), pinned by
  `OutcomeMessagesTest`.
- `computeResumeStart` (+ `ResumeStart`) — the ONE resume decision: explicit
  chapter request vs. saved progress, then the ADR-0003 smart rewind, pinned
  by `ResumeStartTest`. The ViewModel keeps the paired side effect (clearing
  the pause marker).

## Consequences

- MainViewModel shrank from ~1447 to ~1300 lines and exposes only
  depth-bearing members; the forwarding StateFlows (`allBooks`,
  `favoriteBooks`, `downloadedBooks`, `listeningStats`, `allBookmarks`,
  `catalogSections`, `isCatalogLoading`, `catalogGenres`, `sourceFeeds`,
  `isFeedsLoading`, `unifiedCatalog`, `isUnifiedCatalogLoading`) and the
  pure forwarders (`toggleFavorite`, `deleteBookmark`, `removeOfflineDownload`,
  `refreshCatalog`, `loadSourceFeeds`, `loadUnifiedCatalog`, `isStreamOnly`)
  are gone. Two of the re-exposures (`favoriteBooks`, `downloadedBooks`) were
  already dead.
- The module interfaces are unchanged: cold flows stay cold, no hot
  `stateIn` was added to the modules, and no new ViewModel interface was
  introduced (still one adapter).
- A cold module flow collected by several screens means several Room
  subscriptions — Room flows are multi-collector safe, and each screen's
  subscription dies with its composition.
- Internal consumers of the dropped re-exposures moved to the module's own
  flow (`recommendedBooks`/`refreshEmbeddingVectors`/`openRecommendedBook`
  read `sourceCatalog.unifiedCatalog` directly).
- Screen behavior is byte-identical: the same flows feed the same composables
  and the same suspend functions run on the same dispatchers (the composition
  scope is the main dispatcher, matching the former `viewModelScope.launch`
  on the UI-facing calls; module calls that used `Dispatchers.IO` were
  already launching their own IO context inside the module).
