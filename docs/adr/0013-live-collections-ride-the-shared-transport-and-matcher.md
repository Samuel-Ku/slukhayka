---
status: accepted
---

# Live collections ride the shared transport and the same matcher

Spec-16 shipped smart collections as static JSON assets matched locally
against the catalog union, and explicitly deferred network lists ("Live/lists
fetched from the network (bestsellers, Goodreads shelves) — deferred to a
future spec"). This ADR lands that future spec: a LIVE collection source seam
whose output feeds the SAME matcher as the static assets.

## Decision

- **A live collection is a collection.** The seam
  `LiveCollectionSource.fetchLiveCollections(): List<CollectionList>` returns
  lists in exactly the asset format; the catalog feeds them into the same
  `CollectionMatcher.matchAll(static + live, union)` on the same
  `refreshUnifiedCatalog` trigger. The UI block, the empty-hiding rule and
  the tap-through identity are unchanged — the «Колекції» block simply gains
  a «Популярне зараз» row when its matches exist.
- **One HTTP transport, best-effort by contract.** A live source fetches
  through the shared `HttpFetcher` (ADR-0006 — no raw connections anywhere),
  parses with the shared pure-JVM `MiniJson`, and returns an EMPTY list on any
  failure — a changed upstream shape, a network error, an empty payload. A
  failing source contributes no collection and never breaks the union refresh.
- **Keyless OpenLibrary trending is the first source**
  (`OpenLibraryTrendingSource`, `/trending/now.json` — the enrichment spike's
  keyless secondary source): one «Популярне зараз» list of
  (author, title) entries, capped at 40, English-centric by nature. The
  matcher hides non-matches, so only the books the catalog actually carries
  surface — the designed fallback, never gaps. Google Books (keyed) can
  replace or join it later behind the same seam.
- **TTL-cached per source, like the feeds.** Live lists are a session
  convenience: repeated refreshes within the 15-minute feed TTL reuse the
  session's fetched lists instead of re-fetching; a fresh session (or a TTL
  expiry) re-fetches so «Популярне зараз» stays current.
- **The parser was extracted, not duplicated.** The strict asset decoder's
  JSON parser became the shared internal `MiniJson`; `CollectionJson` keeps
  its strict public behavior (all its fixture tests unchanged) and the live
  source reuses the same parser — one parsing convention for the whole
  collections module.

## Consequences

- «Популярне зараз» appears in Огляд only when the union actually carries
  matched books; a network outage silently degrades to the static collections.
- A new live source (Google Books bestsellers with a key, a Goodreads shelf
  endpoint) is one more `LiveCollectionSource` implementation wired at the
  composition root — no changes to the catalog, the matcher or the UI.
- No schema change, no Room rows; the flow stays computed, never stored.
- Tests pin the live source against the real trending payload shape
  (FakeFetcher, no network), the failure/empty cases, the entry cap, and the
  repository recompute/TTL behavior through the fake-DAO seam.
