# Room migration & id-stability risk inventory — wayfinder ticket «Room migration risk inventory» (#47)

Status: resolved 2026-08-10. Evidence: `AudiobookDatabase.kt`, `Entities.kt`,
`AudiobookDao.kt`, `AudiobookRepository.kt` (main branch, HEAD 3c3c731).

## What the schema looks like today

`@Database(version = 7, exportSchema = false)` with 5 entities
(`AudiobookDatabase.kt:10-20`):

| Table | PK | Notes |
|---|---|---|
| audiobooks | `id` TEXT | `bookId` — the identity at the center of everything below |
| chapters | `id` TEXT | generated as `"${bookId}_ch${n}"` (AudiobookRepository.kt:970, 1038) |
| bookmarks | `id` INTEGER autoGenerate | keyed by `bookId` |
| playback_progress | `bookId` TEXT | progress is per-book, not per-source |
| listening_stats | `dateIso` TEXT | aggregates only — no book reference |

The builder chains `MIGRATION_3_4 … MIGRATION_6_7` and then
`fallbackToDestructiveMigration(dropAllTables = true)`
(`AudiobookDatabase.kt:38-39`): **any version bump without a matching migration
wipes the whole library** — books, progress, bookmarks, stats.

## Book-id schemes in production code (verified)

| Scheme | Producer | Effect of re-import |
|---|---|---|
| `4read-<slug>` | `CatalogParser.bookId` (CatalogParser.kt:418-420); `importAudiobookFromHtml` (AudiobookRepository.kt:871-873) | Stable: same slug → same id → `getAudiobookById` short-circuit / upsert |
| `4read-custom-<timestamp>` | `importAudiobookFromHtml` when the slug is empty (AudiobookRepository.kt:871); same in `importAudiobookFromHtml` demo path (:987-989) | **New id per import → silent duplicate rows** for the same book, each with its own progress |
| `4read-search-<slug>` | offline-search fallback row (AudiobookRepository.kt:1458) | Stable for the same query text; a re-typed query creates a new row |

Slug normalization is inconsistent: the import path strips prefixes/suffixes and
collapses to lowercase, but there is no canonicalization for query parameters,
trailing slashes, or `/xfsearch/` URLs — two URL spellings of one book can pass
`getAudiobookById` and create duplicates even without empty slugs.

## What depends on `bookId` stability (every one of these breaks on re-id)

- `upsertCatalogBook` — upserts keyed by `book.id`; a changed id orphans the old row (AudiobookRepository.kt:227-299)
- `deleteBook` cascade — explicitly "entities have no FK constraints, the cascade is coordinated here" (AudiobookRepository.kt:299-323) → **a re-id or orphaned row survives the cascade**
- `getChaptersList` — chapters re-fetched by `bookId`, chapter ids derive from it
- `removeFromLibrary` (AudiobookRepository.kt:323) and `deletedCatalogBookIds` suppression set — both id-keyed
- Bookmarks (`BookmarkEntity.bookId`), progress (`PlaybackProgressEntity` PK = bookId), favorite flag

## Findings

1. **CRITICAL: `fallbackToDestructiveMigration` is a data-loss hole for exactly the kind of schema work the sync ticket needs.** Adding a server-provisioned id, a source table, or a listening-state table *will* bump the version; forgetting one migration wipes the library. `exportSchema = false` means Room never records the schema history, so nothing catches a missing migration except manual testing.
2. **HIGH: no canonical id.** The three schemes + timestamp fallback mean the same logical book can exist as several rows. Any migration that "fixes" ids will have to dedupe `4read-custom-*` rows, and the merge rule must decide which book keeps the progress/bookmarks — with zero FKs, orphaned rows are invisible until a `DELETE` audit runs.
3. **HIGH: `deletedCatalogBookIds` is an in-memory suppression set** (AudiobookRepository.kt:77) — not a tombstone. It dies with the process, so deleted catalogue books re-appear after a restart + catalogue sync. (Already known; the ticket's tombstone concept is the durable fix — a `tombstones` table survives `fallbackToDestructiveMigration` only if the migration is written, which is the point of finding 1.)
4. **MEDIUM: slug normalization inconsistency** (prefix/suffix only, no query-param/percent-decoding/`xfsearch` handling) is the input-side cause of duplicates; fixing it is a code change, not a migration.
5. **LOW-RISK areas:** `listening_stats` is date-keyed; `createdAt` (v6→7) and `preferredSpeed` (v5→6) migrations are append-only and safe patterns to copy. All existing migrations are `ALTER TABLE ADD COLUMN` — the codebase has never exercised a multi-table migration in production.

## Recommended guardrails for the next schema work (sync ticket, spec-10 recovery)

1. Bump `exportSchema` to a committed `schemas/` dir + CI check (`room.schemaLocation`), so every migration is diffed against the prior schema.
2. Replace `fallbackToDestructiveMigration` with a **no-op `Migration(7, 8)` that throws** during development — data-loss becomes a loud failure instead of a silent wipe.
3. Add a JVM migration test that opens a real v7 database (the codebase already has this pattern for 5→6 and 6→7 — `MIGRATION_5_6`/`MIGRATION_6_7` are `internal` for exactly that reason) before any version bump.
4. For any id-reconciliation work: dedupe `4read-custom-*` rows by (title, author) similarity, keep the newest `createdAt`, and migrate progress/bookmarks to the surviving row — before the schema change ships, not after.

## Verdict

Buildable and safe **if** the guardrails land in the same ticket as the schema
change; the current combination (destructive fallback + no exportSchema + no
canonical id + in-memory deletion set) makes any future version bump a
progress-losing event today.
