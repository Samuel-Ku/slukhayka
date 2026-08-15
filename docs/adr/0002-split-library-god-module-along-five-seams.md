---
status: accepted
---

# Split the library god module along five seams

The 2,131-line `AudiobookRepository` (88 public members) is split into five deep modules named after domain concepts: `SourceCatalog`, `LibraryImport`, `OfflineDownloads`, `ListeningStateStore`, and `LibraryEntries` (all in `data/repository/`, no `Repository` suffix). The repository is deleted, not kept as a facade — keeping it would preserve the 88-member interface the split exists to remove. Extraction is incremental: one seam per change, the repository delegates until the final change deletes it.

## Consequences

Dependency direction is a DAG: `OfflineDownloads → SourceCatalog → LibraryImport → {adapters, DAO}`; `LibraryEntries → OfflineDownloads` (downloaded-file cleanup); `ListeningStateStore → DAO`. The source-adapter list is composed in `App` and injected where needed. Per-source URL/header/stream-only policy stays as pure functions in `data/source`; the repository's wrapper methods are deleted, callers use the pure functions directly. Constructor side-effects are removed: the init-time auto-sync becomes an explicit `App`-level call on `SourceCatalog`. Tests construct one module over Room or `FakeAudiobookDao` instead of the whole graph; `MainViewModel`'s public surface is unchanged by the split. The player takes concrete modules (`ListeningStateStore`, `SourceCatalog`) — a `PlaybackStateStore` interface is deliberately not introduced until a second adapter justifies the seam.
