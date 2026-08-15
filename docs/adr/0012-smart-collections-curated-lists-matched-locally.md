---
status: accepted
---

# Smart collections: curated external lists matched locally against the catalog union

Огляд shows the catalog through its own structure — genres, series, top-100,
feeds. The enrichment spike (#30) measured ~65–75 % of the catalog as
externally matchable, yet no surface used that: the listener could not see
«what from the famous lists is actually available here». Spec-16 adds the
«Колекції» block — curated external lists (Нобелівські лауреати,
Шевченківська премія, Букер) matched locally against the catalog, showing
only books that are actually present, with no network, no API keys and no
schema change.

## Decision

- **The lists are static JSON assets.** Each collection is one file under
  `app/src/main/assets/collections/` with metadata (id, display name,
  source-of-list note) and entries (author, optional title, optional per-entry
  note). Adding a collection later is a new JSON file — a data change, not a
  code change. Loading goes through the existing context seam
  (`CollectionAssets`, the same `context.assets` the E5 model uses), best
  effort per file: a missing or malformed asset contributes no collection.
- **The decoder is a strict pure-JVM parser** (`CollectionJson`) — no org.json
  stubs, matching the source-adapter convention. It understands the curated
  shape (objects, string arrays, standard escapes incl. `\uXXXX`) and returns
  `null` on anything else; a malformed entry invalidates the whole collection,
  so a release review catches data bugs instead of silently dropping entries.
- **The matcher is pure JVM** (`CollectionMatcher`) and reuses the [MergeKey]
  normalization the catalog union itself merges on (case-fold, punctuation
  strip, whitespace collapse, subtitle cut), adding two collection folds:
  diacritics (NFKD + combining-mark drop, **Cyrillic-preserving** — ї/й must
  not decompose) and trailing parenthetical-annotation trimming
  («Кобзар (повне видання)» ≈ «Кобзар»).
- **A match requires at least author agreement.** Entry with a title → the
  card matches when normalized author AND title agree; title-less entry →
  every catalog card of that author (author-only fallback); blank author →
  never matches. An entry with no catalog match contributes nothing — the
  matcher never fabricates a card (the spike's hide-non-matches rule).
- **The flow rides the union recompute.** `SourceCatalog.smartCollections` is
  a StateFlow recomputed inside `refreshUnifiedCatalog` — the SAME trigger as
  the union itself — so a newly enumerated book appears in its collection
  after the next catalog refresh, with no listener action. Collections are
  computed, never stored: no Room rows, no schema change.
- **Empty collections are hidden.** `matchAll` drops collections whose match
  set is empty; the Огляд block renders one horizontal cover row per remaining
  collection and disappears entirely when none remains. Cards are the union's
  `GlobalSearchResult` cards — cover-first, uniform with the other Огляд rows;
  tapping resolves the Work through the same identity as any global-search
  card (import-and-play).

## Consequences

- The union is the match corpus, and it deliberately excludes 4read (its
  catalogue is natively browsed, spec-15). Collections therefore surface books
  present on the other verified sources; the curated lists are reviewed per
  release against what the sources carry, and non-matches hide by design. If
  the maintainer later wants 4read-covered collections, the corpus widens in
  the same one trigger point.
- No new dependency, no network, no build-time regression; the matcher and
  decoder are pinned by pure JVM fixture tests and the flow by the fake-DAO
  repository seam; the block by the Robolectric/Roborazzi snapshot seam.
- «Колекції» are catalog surfaces (like Series/Genre rows), not Library
  entries: a collection card always opens the same Work/Edition/Source
  identity as any other Огляд row.
- Live/bestseller fetching, mood tags, persistence and in-UI editing are
  explicitly out of scope (spec-16 Out of Scope).
