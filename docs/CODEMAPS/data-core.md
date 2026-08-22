# Data Core Module

<!-- Generated: 2026-08-16 | Files scanned: 22 | Kotlin lines: ~5,750 -->

## Purpose

The domain core: the Room schema (19 tables, v19) and DAO, the Source Catalog
browse/sync/search module, library entries, listening state, offline downloads,
and the two throttled background duration passes. Nearly every other module
depends on the DAO and the Work/Edition/Source model.

## Key Files

```
app/src/main/java/com/slukhayka/audiobooks/data/db/AudiobookDatabase.kt   982 lines  (v19, migrations)
app/src/main/java/com/slukhayka/audiobooks/data/db/AudiobookDao.kt        681 lines
app/src/main/java/com/slukhayka/audiobooks/data/db/Entities.kt            585 lines  (19 entities)
app/src/main/java/com/slukhayka/audiobooks/data/db/BookRow.kt              97 lines  (join row models)
app/src/main/java/com/slukhayka/audiobooks/data/db/FailureCategory.kt      75 lines
app/src/main/java/com/slukhayka/audiobooks/data/db/PlaybackEventFilter.kt  35 lines
app/src/main/java/com/slukhayka/audiobooks/data/db/PlaybackEventPolicy.kt  84 lines

app/src/main/java/com/slukhayka/audiobooks/data/catalog/SourceCatalog.kt  1055 lines
app/src/main/java/com/slukhayka/audiobooks/data/catalog/CatalogParser.kt   453 lines

app/src/main/java/com/slukhayka/audiobooks/data/entries/LibraryEntries.kt 354 lines
app/src/main/java/com/slukhayka/audiobooks/data/listening/ListeningStateStore.kt 204 lines
app/src/main/java/com/slukhayka/audiobooks/data/downloads/OfflineDownloads.kt    241 lines
app/src/main/java/com/slukhayka/audiobooks/data/metadata/MetadataAssertions.kt   367 lines
app/src/main/java/com/slukhayka/audiobooks/data/metadata/StoredMetadataScrub.kt  47 lines
app/src/main/java/com/slukhayka/audiobooks/data/merge/MergeKey.kt                 59 lines
app/src/main/java/com/slukhayka/audiobooks/data/duration/DurationEnrichment.kt    79 lines
app/src/main/java/com/slukhayka/audiobooks/data/duration/ChapterDurationProbe.kt 111 lines
app/src/main/java/com/slukhayka/audiobooks/data/duration/DurationBuckets.kt       68 lines
app/src/main/java/com/slukhayka/audiobooks/data/duration/MpegAudioFrame.kt       138 lines
app/src/main/java/com/slukhayka/audiobooks/data/duration/StreamProber.kt         109 lines
app/src/main/java/com/slukhayka/audiobooks/data/EditionId.kt                     36 lines
app/src/main/java/com/slukhayka/audiobooks/data/Hashing.kt                       40 lines
```

## Database Schema (v19, 19 tables)

| Table | Entity | Purpose |
|---|---|---|
| `works` | `WorkEntity` | Bibliographic work (title, author, series refs) — ADR-0009 split |
| `editions` | `EditionEntity` | One rendition per narrator/language of a work (ADR-0010) |
| `sources` | `SourceEntity` | Per-edition-per-source availability |
| `work_sources` | `WorkSourceEntity` | Work ↔ source mapping |
| `source_tracks` | `SourceTrackEntity` | Physical stream URLs (ADR-0007: sources own tracks) |
| `audiobooks` | `AudiobookEntity` | Legacy flat book rows (migration bridge) |
| `chapters` | `ChapterEntity` | Chapter metadata, owned by edition |
| `library_entries` | `LibraryEntryEntity` | User library membership (favourite, added) |
| `bookmarks` | `BookmarkEntity` | User bookmarks |
| `playback_progress` | `PlaybackProgressEntity` | Last position per book |
| `listening_stats` | `ListeningStatEntity` | Daily listened-seconds rollup |
| `playback_events` | `PlaybackEventEntity` | Playback event journal (spec-13) |
| `playback_failures` | `PlaybackFailureEntity` | Failed streams for retry policy |
| `tombstones` | `TombstoneEntity` | Deleted-book tombstones (ADR-0005) |
| `corrections` | `CorrectionEntity` | Universe corrections (spec-26) |
| `series` / `series_members` / `universes` | `SeriesEntity` / `SeriesMemberEntity` / `UniverseEntity` | Series-universe cache (spec-25) |
| `edition_settings` | `EditionSettingsEntity` | Per-edition playback settings |

## Modules in This Folder

### SourceCatalog (data/catalog/)
ADR-0002 deep module owning browse & sync: 4read sections, genres, series,
top-100, people, source feeds, the unified catalogue union, global search and
catalogue hydration — plus the caches behind them (new-feed TTL, per-adapter
catalogue cache). Constructing it performs NO network I/O; `App` makes the
explicit sync call (`fetchCatalogSections`). `CatalogParser` is the 4read
homepage/section HTML parser (spec-11: one parser for 4read).

### LibraryEntries (data/entries/)
Delete/remove/favourite/metadata + library reads over the DAO (ADR-0009).

### ListeningStateStore (data/listening/)
One listening state (recent book, progress, daily stats) shared by the player
and the ViewModel (ADR-0002).

### OfflineDownloads (data/downloads/)
Download/remove/cache-clear over the catalog's chapter fetch.

### MetadataAssertions + StoredMetadataScrub (data/metadata/)
Single place applying metadata rules on the write path (ADR-0004): titles
(spec-24 T1) and descriptions (#264). The startup scrub pass fixes pre-rule
rows idempotently.

### MergeKey (data/merge/)
Work-level dedup key for multi-source import (spec-10).

### Duration passes (data/duration/)
`DurationEnrichment` (spec-18: throttled page-fetch duration fill), `ChapterDurationProbe`
(spec-24: HEAD/ranged-GET stream probing), plus pure `DurationBuckets` /
`MpegAudioFrame` / `StreamProber` helpers.

### Primitives (data/ root)
`EditionId` (stable edition id scheme) and `Hashing` (sha256/content-hash for
local-file dedup) — used across import, entries and downloads.

## Dependencies

- **Inbound:** `App` (constructs SourceCatalog, LibraryEntries, …), `player.AudioPlayerManager`, `ui/*` screens
- **Outbound:** `androidx.room.*`, `androidx.paging.*`, `data.source.*` (adapters via constructor), `data.imports.LibraryImport` (catalog DAG edge, #138)

## Common Tasks

| Task | Touch |
|---|---|
| Add a table / column | `Entities.kt` + `AudiobookDatabase.kt` migration + `app/schemas/` export (Room version bump) |
| Add a browse section to Огляд | `SourceCatalog.kt` cache + parse path + `HomeScreen.kt` |
| Fix a catalog parse bug | `CatalogParser.kt` + its fixture tests |
| Change download policy | `data/downloads/OfflineDownloads.kt` |
| Change dedup/merge rules | `data/merge/MergeKey.kt` |

## Known Issues / Notes

- `AudiobookDatabase.kt` (982 lines) holds DAO-implementation + migrations —
  the largest data file; migrations must stay append-only (Room schema exports in `app/schemas/`).
- `audiobooks` is a legacy bridge table — new code should target
  `works`/`editions`/`sources`; the flat entity survives for existing UI reads.
- Tests: `app/src/test/java/com/slukhayka/audiobooks/data/repository/` holds the Room-backed
  module tests (DeepModulesRoomTest, SourceFeedsRepositoryTest, …);
  `UniverseMigrationsTest` covers schema migrations.
