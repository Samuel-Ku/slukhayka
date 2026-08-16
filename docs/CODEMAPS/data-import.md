# Data Import Module

<!-- Generated: 2026-08-16 | Files scanned: 13 | Kotlin lines: ~2,290 -->

## Purpose

How content gets INTO the library: the five import doors behind one module
(`LibraryImport`), the local-folder tooling (scanner, planner, rescan), and the
smart-collection machinery (curated JSON assets + live sources matched locally).

## Key Files

```
app/src/main/java/com/slukhayka/audiobooks/data/imports/LibraryImport.kt    1284 lines  (the five doors)
app/src/main/java/com/slukhayka/audiobooks/data/imports/ImportPlanner.kt     262 lines  (local folder → plan)
app/src/main/java/com/slukhayka/audiobooks/data/imports/FolderRescan.kt      114 lines
app/src/main/java/com/slukhayka/audiobooks/data/imports/ImportPlan.kt         75 lines
app/src/main/java/com/slukhayka/audiobooks/data/imports/LocalFolderScanner.kt 83 lines  (DocumentFile walk)
app/src/main/java/com/slukhayka/audiobooks/data/imports/ImportGrantStore.kt   28 lines  (SAF tree-uri persistence)

app/src/main/java/com/slukhayka/audiobooks/data/collections/CollectionAssets.kt          32 lines  (context.assets loader)
app/src/main/java/com/slukhayka/audiobooks/data/collections/CollectionJson.kt            56 lines
app/src/main/java/com/slukhayka/audiobooks/data/collections/CollectionList.kt            26 lines
app/src/main/java/com/slukhayka/audiobooks/data/collections/CollectionMatcher.kt        112 lines  (local matching)
app/src/main/java/com/slukhayka/audiobooks/data/collections/MiniJson.kt                 134 lines  (tiny JSON reader)
app/src/main/java/com/slukhayka/audiobooks/data/collections/LiveCollectionSource.kt      26 lines  (seam)
app/src/main/java/com/slukhayka/audiobooks/data/collections/OpenLibraryTrendingSource.kt 59 lines  (live «Популярне зараз»)
```

## The Five Import Doors (LibraryImport)

| # | Door | Entry points |
|---|---|---|
| 1 | Explicit source import | `importBookFromSource` · `importFromSourceUrl` · `importAudiobookFrom4ReadUrl` |
| 2 | Captured-page import (ADR-0006) | `importWebSourcePage` · `importAudiobookFromHtml` — builds detail from HTML captured in the live WebView session |
| 3 | Local folder import | `importLocalAudioFile` · `importLocalAudioStream` · `importLocalAudioFolder` · `planLocalAudioFolder` · `applyImportPlan` · `importAudioEntries` |
| 4 | Rescan | `rescanLocalFolder` · `rescanAudioEntries` · `rescanAllLocalFolders` |
| 5 | Catalog upsert on import | `upsertCatalogBook` |

Behaviour contracts (kept exactly by tests):
- explicit re-adds clear Tombstones (ADR-0005);
- merging happens by the Work-level `MergeKey` (spec-10);
- local files dedupe by content hash (`Hashing.sha256Hex`, `contentHashOf`);
- the import trigger (`onWorkImported`) fires after a NEW work with a series
  enters — the composition root wires it to universe chain validation (spec-26 T8).

## Local Folder Pipeline

```
SAF tree-uri (ImportGrantStore) → LocalFolderScanner.scan (DocumentFile)
  → ImportPlanner (ImportPlan: adds/skips/removes by content hash)
  → LibraryImport.importAudioEntries / applyImportPlan
  → FolderRescan.computeDiff for subsequent rescans
```

## Smart Collections (spec-16 / spec-13)

- **Curated assets:** one JSON file per collection in `app/src/main/assets/`
  (loaded via the `context.assets` seam — `CollectionAssets.load`). Adding a
  collection = adding a JSON file, no code change. Loading is best-effort per
  file: a malformed asset contributes nothing, never crashes.
- **Live sources:** the `LiveCollectionSource` seam, implemented today by
  `OpenLibraryTrendingSource` (keyless trending fetch over the shared HTTP
  transport, refreshed on the union refresh).
- **Matching:** `CollectionMatcher` matches catalogue rows to collection
  entries locally (title/alias-aware); `MiniJson` is the embedded JSON reader.

## Dependencies

- **Inbound:** `App` (constructs `libraryImport`), `SourceCatalog` (DAG edge — catalog doors persist through the import path)
- **Outbound:** `data.db.*` (DAO, entities), `data.source.SourceAdapter` (injected list — no adapter constructed here), `data.merge.MergeKey`, `data.metadata.MetadataAssertions`, `data.universe` (via `onWorkImported` callback)

## Common Tasks

| Task | Touch |
|---|---|
| Add an import door | `LibraryImport.kt` + its Room-backed test in `data/repository/` or `data/imports/` |
| Add a curated collection | New JSON in `app/src/main/assets/collections/` |
| Add a live collection source | Implement `LiveCollectionSource` + register in `App.kt` |
| Change local-file dedup | `data/Hashing.kt` + `ImportPlanner` |
| Change rescan behavior | `FolderRescan.computeDiff` |

## Known Issues / Notes

- `LibraryImport.kt` is the largest data file (1,284 lines) — the five doors
  share the same mapping helpers; splitting per-door files is a refactor candidate.
- WebView-pattern doors (2) require a live session and are surfaced as
  «відкрити джерело, щоб оновити» CTAs when the session is stale (spec-13).
- Tests: `LibraryImportTriggerTest`, `ImportPlannerTest`, `FolderRescanTest`,
  `LocalFolderScannerTest`, `ImportGrantStoreTest`, `CollectionJsonTest`,
  `CollectionMatcherTest`, `OpenLibraryTrendingSourceTest`.
