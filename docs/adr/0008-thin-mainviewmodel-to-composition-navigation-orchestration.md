---
status: accepted
---

# MainViewModel thins to composition, navigation, and orchestration; screens observe the modules directly

ADR-0002 split the god repository into five deep modules and froze MainViewModel's public surface so no screen changed. That freeze is lifted: MainViewModel forwards ~105 public members to the modules — ~14 are forwarding StateFlows (`module.X.stateIn(viewModelScope, WhileSubscribed(5000), initial)`), a literal re-exposure with zero depth, and ~7 are pure 1:1 forwarders (`viewModelScope.launch { module.X() }`). A forwarding member is an interface with no depth behind it: deleting it from the ViewModel moves a line, not a concept.

## Decision

MainViewModel keeps only depth-bearing members; screens reach the modules directly for the rest.

- **Composition stays.** libraryBooks, listenBlocks, durationBooks, recentProgress, selectedBook/chapters/bookmarks, catalogDownloadProgress/DownloadedKeys, nextInSeries are combine/map/flatMapLatest over module flows — real logic, not forwarding. They stay hot via `stateIn`.
- **Navigation stays.** selectedTab, the pushed-surface selections (book, web-source, series, genre, top-100, people, person), showFullPlayer. One owner for the hand-rolled stack.
- **Orchestration stays.** Debounced search, stale-result guards, download/import message composition, resume start-position decision, listen-pref wiring, and actions that span modules (delete/remove stop the player and clear navigation; import composes a message and remembers the tree URI).
- **Forwarding flows are deleted.** A screen observes a module flow directly: `module.allBooks.collectAsState(initial = emptyList())`. The forwarding `stateIn(WhileSubscribed(5000))` shell is not load-bearing — Room re-query on tab switch is cheap, and `collectAsState(initial)` preserves the immediate-initial semantics — so the shell adds an interface without a seam.
- **Pure forwarders are deleted.** A screen calls the module's suspend function directly (Room suspend functions are main-safe). This extends the precedent already in the code: `playerManager` is a public field screens call through.

The result is one thin activity-scoped ViewModel, not per-screen ViewModels: the app is a single activity with hand-rolled navigation, and per-screen ViewModels would add NavBackStackEntry machinery for no benefit while forking the shared flows' subscription (locality is served by the modules; the ViewModel is where cross-screen composition and navigation live).

## Consequences

Screens become data-in / events-out composables (BookListScreen, GlobalSearchResults, SourceFeeds already are); AudiobookApp wires modules and callbacks. The ViewModel's testable logic — download/import message mapping, resume start-position computation — is extracted to pure functions with JVM tests, prior art LibraryModel and ListenComposer; the remaining wiring is covered by the existing snapshot tests. Module interfaces do not change: they keep cold flows and add no hot stateIn, and no new ViewModel interface is introduced (still one adapter — a hypothetical seam). The refactor lands in expand–contract batches by screen, each green, with a sequencing note that the resume batch follows #147 (ADR-0003, one Smart Rewind rule) to avoid re-touching the same resume path.
