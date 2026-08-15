---
status: accepted
---

# The library holds several rendition cards under one Work

ADR-0010 made the narrator an Edition property and promised the multi-Edition
library as future work: "importing a second narration of an already-imported
book merges into the existing card". That policy threw the second narration's
sources and listening state into the first card, where the user could not
reach them (the Edition rows existed, but the UI surfaced one card per Work).
This ADR completes the model: a Work with several narrations has several
**rendition cards**, and the book page lets the user pick the narration.

## Decision

- **Dedup is per rendition, not per Work.** `LibraryImport.importBookFromSource`
  resolves the incoming book by its Edition id
  (`EditionId.forBook(mergeKey, narrator, …)`, the narration identity). An
  incoming book whose Edition already exists merges into that card (the new
  source attaches to it — ADR-0007 chapter list stays the first source's).
  The same Work with a DIFFERENT narration creates a NEW card: its own
  `audiobooks` row (user copy), its own Library Entry (same `workId`), its
  own Edition (same `works.id`), chapters, source and tracks. One Work —
  several cards, each an Edition.
- **No schema change.** The v16 schema already supports it: `library_entries`
  is one row per audiobooks row, `workId` pins several entries to one Work,
  and `editions` are keyed by the narrator-carrying id. Multi-Edition is a
  write-path and UI change only.
- **The book page shows the siblings.** `BookDetailScreen` collects the
  library entries and renders the OTHER cards of the same Work in the
  «Інші начитки» block, sorted by narrator, each opening its own card. The
  pure filter `siblingNarrations(books, selfId, mergeKey)` is JVM-tested;
  blank-key rows (local imports) have no Work and never produce siblings.
- **Sources without narrator metadata stay distinct renditions.** The import
  path already substitutes a per-source rendition placeholder (`"4read
  narrator"`, `"sluhay narrator"`, …) when the page carries none; those are
  DIFFERENT Editions by design — a 4read copy and a sluhay copy of the same
  text are physically different recordings and get different cards, while a
  re-import of the same source resolves to the same rendition and merges.

## Consequences

- The library can show one card per narration of the same book; each card
  keeps its own progress, bookmarks, sources, downloads and speed
  (ADR-0001 per Edition, now reachable by the user).
- The merge-on-reimport guarantee narrows from "same Work" to "same
  rendition": re-adding the same book from the same source stays idempotent;
  adding a new narration adds a card instead of silently swallowing it.
- The «N джерел» badge and the Works feed stay Work-level (ADR-0010); the
  card-level source count is per rendition.
- Existing installations: the v16 migration already preserved two
  narration-cards as two entries of one Work — this ADR makes them visible
  and switchable instead of merged-and-hidden.
