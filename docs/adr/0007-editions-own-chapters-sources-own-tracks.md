---
status: accepted
---

# Editions own Chapters; Sources own tracks

The pre-v14 schema conflated two different owners of the same physical rows.
A `chapters` row carried BOTH the logical position anchor (chapterIndex,
title) AND the physical playback data (streamUrl, localFilePath, isDownloaded,
contentHash) — so a download mutated a chapter row, a local copy and a stream
were the same column, and the listening identity was keyed by
`(bookId, sourceKey)`: switching the source that plays a book was treated as a
listening-state transition (SOURCE_SWITCH), even though the rendition never
changed. The spec-23 catalogue additionally used a table named `editions` for
what is really one SOURCE carrying a Work — not a rendition at all.

## Decision

The domain split from CONTEXT.md becomes the schema. One schema bump
(v13 → v14) carries all of it:

- **A new domain `editions` table**: one row per rendition of a Work, with a
  deterministic id (`sha256(mergeKey|language)[0..24]`, or
  `sha256(bookId|language)[0..24]` when the mergeKey is blank) so the
  migration backfill and every future write agree on the SAME id for the same
  book. Exactly one Edition per existing book is backfilled — the mergeKey
  already includes the narrator, so there is no concept drift.
- **The spec-23 `editions` table is renamed to `work_sources`**: its row is
  one SOURCE carrying a Work (the glossary Source of the browse layer), never
  a rendition. The DAO/entities rename accordingly (`WorkSourceEntity`,
  `getWorkSourcesForWorkSync`, `countWorkSources`).
- **Chapters become purely logical**: the Edition's ordered subdivision. The
  physical playback columns (streamUrl, localFilePath, isDownloaded,
  contentHash) move to a new **`source_tracks`** table — one row per
  (chapter, source of the chapter's book), id `$sourceId_tr_${index+1}`. A
  chapter row never changes on download; the track rows do. Chapter → track is
  1:1 by index today; per-source chapter topology is future work.
- **Sources re-parent to the Edition**: the `sources` table gains
  `editionId` and ids are recomputed deterministically as `$type-$editionId`
  (same-type collisions get a numeric suffix by addedAt). The bookId columns
  on chapters/sources/progress/bookmarks are KEPT during this expand step —
  they contract in a later ticket (minSdk 24 forbids DROP COLUMN on chapters,
  so that table is rebuilt rather than altered).
- **Listening state is keyed by the Edition**: `playback_progress` re-keys
  from `(bookId, sourceKey)` to `editionId` — per book, the row with the
  latest `lastListenedAt` wins and the sourceKey shadows are dropped
  (lastPausedAtEpochMs rides along, so Smart Rewind keeps working).
  `bookmarks` gain `editionId`. The `playback_events` log keeps its
  `sourceKey` column as HISTORY — new rows write `""`, and the player no
  longer emits SOURCE_SWITCH at all (switching sources is not a
  listening-state transition).

## Why not

- **Keep per-source progress** (pre-v14): positions would keep fragmenting by
  source, and a source switch would lose the place — exactly what the
  glossary's Listening State definition forbids.
- **Keep physical columns on chapters**: every download/copy/hash mutation
  would keep re-touching the anchor rows, and the "which source does this
  chapter stream from" question would stay unanswerable.

## Consequences

- Progress and bookmarks belong to the rendition, not the source that
  happened to play it — switching sources mid-book keeps the position, and
  the event log stops treating a source switch as a listening transition.
- Downloads mutate only `source_tracks` rows; the Edition's logical chapter
  list is stable under offline operations, so the player's chapter → track
  resolution (`SourceCatalog.getPlayableChapters`) is the single pairing
  seam for playback and downloads.
- The migration is lossy only by design: per-book progress keeps the latest
  row and drops the older sourceKey shadows; everything else is preserved
  (tracks carry the physical data, chapters keep their logical data, sources
  keep their policy).
- The bookId columns and `edition_settings`' `(bookId, sourceKey)` key stay
  until the contract ticket — nothing reads `edition_settings` yet, so it is
  re-keyed when it is wired.
