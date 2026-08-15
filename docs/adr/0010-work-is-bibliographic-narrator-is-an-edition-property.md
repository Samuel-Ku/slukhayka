---
status: accepted
---

# The Work is bibliographic: mergeKey = title|author; the narrator is an Edition property

The Work-level dedup key used to be `title|author|narrator` — the narrator
participated in the identity "when known", so two different narrations of the
same text became two separate Works, and the narrator was mirrored onto the
Works row, the audiobooks row and the Edition row. That contradicts the
domain glossary: a **Work** is the abstract authored book, *independent of
narration*; an **Edition** is the rendition distinguished by narrator,
language and chapter structure. The narrator is rendition identity, not Work
identity — three copies of it are three places for the same fact to drift.

## Decision

- **The Work key is bibliographic:** `MergeKey.keyFor(title, author)`.
  The narrator is deliberately excluded, so every narration of the same text
  resolves to ONE Work. Two different narrations imported from two sources
  attach two sources to one Work; a catalogue search card merges both
  narrations under one card.
- **The narrator is an Edition property.** It lives in exactly two places:
  the domain `editions` row (the rendition's narrator) and the `audiobooks`
  row, which is the user's copy of one rendition and mirrors the Edition —
  never the Work. The `works` table loses its narrator column (schema v16).
- **The Edition id carries the narrator:** `EditionId.forBook` becomes
  `hash(mergeKey|narrator|language)` (bookId fallback for blank keys). This
  preserves ADR-0001 — incompatible narrations never share listening state —
  now guaranteed by the rendition id instead of by splitting the Work.
- **Schema v16 re-keys existing rows** (expand–contract, single migration):
  works rows are re-keyed on the narrator-less key (rows that only differed
  by narrator MERGE into one Work; the earliest row wins, later duplicates
  enrich its series fields via COALESCE), `library_entries.workId` and
  `work_sources.workId` re-point, and every Edition id is recomputed with its
  narrator and remapped across chapters / sources / bookmarks /
  playback_progress. The works + work_sources tables are rebuilt together in
  an FK-safe order (the new work_sources validates its re-keyed workIds
  against works_new, which becomes `works`); the migration test runs with
  `PRAGMA foreign_keys = ON` to pin that.
- **The library keeps one rendition per Work for now.** Importing a second
  narration of an already-imported book merges into the existing card (the
  first narration's Edition stays the anchor; a second source of the same
  type re-syncs that card's copy — last import wins). The multi-Edition
  library (several rendition cards under one Work) is future work; the
  Edition-id scheme already guarantees that when it lands, distinct
  narrations get distinct, stable rendition ids.
- Existing installations that already have two narration-cards survive the
  migration intact: their audiobooks rows stay separate, both entries
  re-point to the one merged Work, and their two Editions keep distinct ids.

## Consequences

- The Works table is one row per book identity, however many narrations and
  sources carry it; the «N джерел» badge now counts every source of every
  narration.
- No narrator column on `works` — one less field to mirror on the import and
  catalogue write paths; the narrator flows to the Edition (and the
  audiobooks copy of it) at import time.
- ADR-0001 is enforced by the rendition id: two narrations of one Work can
  never share a progress row or a bookmark even though they share the Work.
- The v13→v14 migration keeps its historical edition-id formula
  (`EditionId.forBookLegacy`) untouched; v16 is the single point where the
  id scheme changes.
