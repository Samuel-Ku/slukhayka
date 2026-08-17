---
status: accepted
---

# Canonical covers from the shared metadata base (spec-30 T3, #218)

One book on three sources shows three different cover URLs, some of them
dead; a book whose source page never yielded a cover shows none, forever.
The app already had the shared metadata base (spec-30 T2 #217 — durations
keyed by Edition, client-first reads, Firestore hits mirrored into the local
database) and the curated-asset seed pattern (spec-26 — universes poured
from a bundled asset). The missing piece was the cover: a canonical URL per
Work, curated first, resolved client-first, mirrored locally.

## Decision

Covers join the shared metadata base as a third collection, `book_covers`,
keyed by the **Work mergeKey** (one cover per Work, shared across narrations
— unlike the Edition-scoped duration and the Source×Edition profile).

1. **Seam** — `SharedBookMetaStore.getCover(mergeKey)` /
   `getCovers(mergeKeys)` (batched, chunked whereIn — one read for the
   visible cards, never per Work) / `putCover(mergeKey, url, provenance)`.
   The document is `{ coverImageUrl, source, resolvedAt }`, pinned by the
   pure-JVM `CoverCodec` fixture tests; a corrupt/blank/non-http/overlong
   URL decodes to a miss, never a crash. `CoverSanity` is the honest-data
   gate (http(s)-only, bounded length).
2. **Client-first read precedence** — `SearchCoverResolver` (search cards)
   and `LibraryCoverResolver` (Медіатека rows missing a cover): a locally
   known cover wins outright (the shared tier is never consulted for that
   card), the shared base fills the gap in ONE batched read, and a hit is
   mirrored into the local row through the EXISTING cover write path
   (`AudiobookDao.updateCoverImageUrl`) — the same door the import paths
   use, so a known cover can never be clobbered (a row with a cover is
   never a candidate). The source's own claim on the card is the last
   resort. A card without a Work identity (blank mergeKey) is never
   consulted — the base has nothing keyed by it.
3. **Curated seed** — `CuratedCoverSeed` pours the bundled
   `assets/covers/curated_covers.json` at startup, keyed by the SAME
   mergeKey the read paths use (the app's own reads see the curated value —
   unlike the universe seed, whose `seed:` documents the app never reads).
   Idempotent by construction (a document key is replaced, never
   duplicated), best-effort and silent. The asset ships **empty**: the
   mechanism lands and is wired, but no URL is fabricated — the maintainer
   curates entries into the JSON as curation happens (honest-data,
   ADR-0014). Untrusted device cover write-backs stay deferred until
   AppCheck + reporting are in place (spec-30).
4. **Wiring** — one shared `FirestoreBookMetaStore` instance serves every
   consumer (profiles, durations, covers); the search pipeline resolves
   covers AFTER durations and before the search-cache write, so cached
   results carry the canonical shape; the startup one-shot seeds and then
   fills coverless library rows.

## Consequences

- A search/library card with no local cover shows the canonical URL (or
  nothing — degrade-never: no fabricated URL ever).
- A mirrored cover works offline and survives the source URL dying (US-5,
  US-9).
- Cards with a local (previously mirrored or imported) cover keep showing
  it — the shared tier never replaces what the listener already sees.
- Firestore reads stay batched to the visible cards; the free tier is not
  strained (spec-30 free-tier boundary).
- The curated asset being empty means the seed is a no-op in production
  until the maintainer curates entries — a deliberate, documented state.

## Considered options

- **Fill blank cards only.** Rejected: the ticket's «коли локальної
  обкладинки нема» reads against the spec's client-first precedence (local
  row → Firestore → source), and the local row IS the local cover — a card
  whose row has a cover shows it, a card whose row has none is the gap.
- **Device-written covers.** Rejected: covers are highly visible; until
  AppCheck + reporting exist, untrusted write-backs could poison the base.
  Curated-seed-first is the spec's own sequencing.
- **Rehosting covers into object storage.** Out of scope by spec-30 — the
  shared document holds the URL only.

## Follow-ups

- **Cover freshness** (~180-day floor, spec-30) is not implemented: covers
  have no staleness signal on the read path (a mirrored cover is local and
  wins forever). Revisit when untrusted write-backs land.
- **Canonical-preferred-over-source for covered cards**: today the source's
  claim shows when the local row and the base are empty; a later slice may
  prefer the canonical URL for stability (US-2) once freshness exists.
- **Device smoke test**: the Firestore layer is exercised by fixture +
  Robolectric tests only; the live base is verified by the device smoke
  tests of the shared metadata feature (spec-30 Testing Decisions).