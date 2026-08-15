---
status: accepted
---

# The book row splits into Works and Library Entries

`audiobooks` used to be three concepts in one row: the **Work** (mergeKey,
series, workId), the **Library Entry** (isFavorite, createdAt,
downloadProgress) and the per-row **metadata** (title/author/cover/durations).
`preferredSpeed` rode along as a fourth, unrelated concern. Every one of those
concepts has its own owner in the schema by now — the Work belongs to the
identity layer (#142/spec-23), the Listening State row owns playback
preferences (CONTEXT.md, keyed by Edition), and the library entry is the
user's copy of a book. Fusing them in one table couples the write paths: a
catalogue sync that touches series data and an import that touches the user's
favorites both have to fight over the same row, and nothing can express
"the user's copy of a Work" until the row is split.

## Decision

Split the fused columns out of `audiobooks` in an **expand–contract** step
(schema v15, on top of the ADR-0007 v14 split):

- **`works`** owns the Work identity and series: one row per book identity,
  keyed by the normalized MergeKey, carrying `seriesTitle` / `seriesUrl` /
  `seriesIndex` and the bibliographic fields (title/author/narrator/cover).
  This is the **same table as spec-23 (#142)** — one `works` definition, one
  meaning; there is no second catalog-only works table. `seriesUrl` (the 4read
  series-page link, the next-in-series membership signal) is added in the
  expand step.
- **`library_entries`** owns the Library Entry: one row per `audiobooks` row
  with `workId` (the pinned Work id for mergeable identities; the book's own
  id for blank-key/local rows that have no Works row), `isFavorite`,
  `createdAt` (the "recently added" stamp) and `downloadProgress`.
- **`playback_progress.preferredSpeed`** owns the per-book speed: the
  Listening State row is keyed by the Edition (ADR-0007), which is where
  CONTEXT.md places playback preferences. Null = "use the global default".
- **`audiobooks`** contracts to one concept: the metadata of the user's copy
  (title/author/narrator mirroring the Work, cover, genre, durations,
  sourceTreeUri). The fused columns (mergeKey, series*, workId, isFavorite,
  createdAt, downloadProgress, preferredSpeed) are dropped from the row in the
  v15 rebuild.

The migration back-fills everything before contracting: one `library_entries`
row per existing book (workId = mergeKey when present, else the book's id),
one Works row per mergeable library book (upserted merge-on-write by the
pinned key; an existing spec-23 row keeps its identity and only gains the
series fields it is missing), and `preferredSpeed` copied onto the matching
`playback_progress` row. Blank-key books whose spec-23 Works row already
exists are re-anchored to it by the normalized title|author key — but only
when that row actually exists, so a generic author never points at a phantom
Work.

Reads keep one shaped row for the UI: every DAO read of a book joins
`audiobooks` → `library_entries` → `works` (and `playback_progress` for the
speed) and fills the moved fields as `@Ignore` projections on
`AudiobookEntity`. Because Room 2.7 does not hydrate `@Ignore` fields from
`@Query` results, the DAO returns a dedicated `BookRow` projection and the
modules map it into the entity at the module boundary (`LibraryEntries`,
`SourceCatalog`, `OfflineDownloads`). `narrator` stays mirrored on both rows
until the Work-merge policy revisits the mergeKey — that policy change is out
of scope here.

## Consequences

- A catalogue sync that touches series data and an import that touches the
  user's copy no longer contend for the same row: series writes go to
  `works`, entry writes go to `library_entries`, writes to `audiobooks` never
  clobber favorites or series membership (the write path became a real
  upsert for the entry row).
- The Work is a first-class table shared with the browse layer (#142): the
  next-in-series and "recently added" reads both resolve through
  `library_entries.workId`, and a book's series membership lives in exactly
  one place.
- `preferredSpeed` reads/writes moved to the Listening State row; the player
  reads it from `PlaybackProgressEntity` and the old audiobooks column is gone
  (v15 contract step).
- The UI surface did not change: `AudiobookEntity` still carries the same
  fields, and every module that shaped a book row from the DAO now maps
  `BookRow` → entity at its own boundary.
- Schema v15 is pinned by `15.json` and the migration 14→15 is exercised by
  the JVM test suite (DeepModulesRoomTest) against a real v14 database.
