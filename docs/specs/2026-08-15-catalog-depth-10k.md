# [Spec-23] Каталог на 10k+: персистентний Work-каталог, гідрація категорій, нескінченний злитий фід

> **Status:** Draft — synthesized from the 2026-08-15 grilling (user delegated the interview; recommendations locked). Pending tickets on GitHub.
> **Tracker:** filed as issue (labels `spec-23`, `ready-for-agent`).

## Problem Statement

Sources claim 10 000+ audiobooks, but the app never feels that deep. «Увесь каталог» fetches at most ~60 books per source with a default `limit` of 40–60, the union is ephemeral (kept in memory for ~15 minutes, nothing persisted), and there is no pagination or endless scroll at all — a user physically sees a few hundred books, not ten thousand. The two root causes are **depth** (full category hydration never lands — it was deferred as M2 follow-on from spec-13) and **flow** (no infinite feed).

## Solution

1. **Persist the catalog in Room** as two tables — a `Work` (identity-level book: normalized title+author+narrator, MergeKey) and an `Edition`-per-source (source id, source URL, stream-only flag, cover, per-source metadata). Dedup happens **merge-on-write**: when a catalog row is written, it is matched against the existing Work by the normalized MergeKey (the same title+author normalization already validated by the spec-10 matcher and already present as `findByMergeKey` in the DAO) and folded into the same Work instead of creating a duplicate.
2. **Hydrate full catalogs per source** — first 4read (largest clean, server-fetched catalog), then the WebView sources (sluhay, sluhayknigi) through a live session using the existing hydration seam, then sound-books/sluhayua. WebView sources hydrate on open (session is live), not in the background.
3. **Explore becomes one infinite merged feed**: search + genre/mood chips on top, curated rows (Рекомендовано, Нове з джерел) below, then an **endless merged catalog** (Paging 3, no duplicates) with filters (source / genre / sort). One card per Work, with a «N джерел» badge.
4. **Duplicate-free everywhere** — merge-on-write gives this for free: one Work card per book regardless of how many sources carry it; the book page shows a «Джерела» sub-list (2–5 playback variants) instead of duplicate cards.
5. **Performance on the 10k scale** — Room + Paging 3, `LazyColumn`, thumbnails only for the visible viewport.

Each step is its own commit with tests and a CI gate (assembleDebug + testDebugUnitTest + Kover).

## User Stories

1. As a user, I want to scroll the catalogue endlessly, so that it feels like a real library rather than a fixed handful of rows.
2. As a user, I want the catalogue to grow toward thousands of books over time, so that there is always something new to find.
3. As a user, I want one card per book even when several sources carry it, so that I never see duplicates.
4. As a user, I want to know which sources carry a book, so that I can pick the source with the best audio or availability.
5. As a user, I want to filter the feed by source, genre, and sort, so that I can navigate a huge catalogue.
6. As a user, I want the catalogue to survive restarts, so that the app does not refetch everything on every open.
7. As a user, I want playback to work from a Work card regardless of which source it came from, so that the merged catalogue is not just a browse surface.
8. As a maintainer, I want hydration results persisted, so that a background or on-open hydration pass incrementally grows the catalogue instead of replacing an ephemeral view.
9. As a maintainer, I want merge-on-write at the storage layer, so that dedup does not depend on which screen happened to be open.
10. As a maintainer, I want each source's catalog fully enumerated over time, so that «Нове з джерел» and search cover the whole catalogue, not the first page.

## Implementation Decisions

- **Schema (additive, migration bump):** new `works` table (id, mergeKey UNIQUE, normalized title/author/narrator, series info, cover, `addedAt`) and `editions` table (id, workId FK, sourceId, sourceUrl, streamOnly, cover, duration, per-source fields, `addedAt`). Existing `audiobooks` stays the listening/library table; the catalog Work/Edition tables are a browse layer, linked to an `AudiobookEntity` only when the user adds/plays the Work.
- **Merge-on-write, not merge-on-read.** The current union merges `GlobalSearchResult`s in memory via `MergeKey` at read time (TTL ~15 min). Spec-23 moves the merge into the write path: any catalog write normalizes (title|author|narrator via the existing normalization), looks up the Work by mergeKey, and either creates the Work + first Edition or appends an Edition to the existing Work. `findByMergeKey` already exists in the DAO; this extends that seam to `works`/`editions`.
- **Hydration via the existing seams:** 4read through its server fetch (no Cloudflare) with category/section iteration; WebView sources through the existing `hydrateWebSourceCatalog` seam (live session, hydrates on open); sound-books/sluhayua through their catalog endpoints. Hydration is incremental and idempotent (merge-on-write means re-hydration cannot duplicate).
- **Feed:** `androidx.paging:paging-runtime` + `paging-compose`; a `PagingSource` over the `works` table joined with its editions; filters (source/genre/sort) as composable `PagingSource` parameters or a query layer; the Explore feed composes curated rows (recommendations, «Нове з джерел») followed by the endless merged list.
- **Badges:** one card per Work with a compact «N джерел» badge; the book page gains a «Джерела» section listing the variants (source name, quality/stream-only marker) and the play/download action routes through the existing per-source policy.
- **Out of scope by decision:** full catalogue-wide genre/author/narrator browse per source (that is per-source catalog browse, deferred until hydration lands); WebView background hydration without a live session; changing the identity-merge normalization (reuse, don't reinvent); Android Auto; any change to the listening/library tables.

## Testing Decisions

- **What makes a good test:** external behaviour — writing the same book from two sources yields one Work with two Editions; re-hydration does not duplicate; the PagingSource pages through a large synthetic `works` table without gaps or duplicates; a filtered query returns only matching Works; a Work card shows the source badge count.
- **Modules tested:** Room migration test (vN → vN+1 preserves existing `audiobooks`/`chapters` data), merge-on-write unit tests over the in-memory DAO (existing seam), hydration idempotency tests over the source adapters' fixtures (existing fixtures in the repo), PagingSource tests over a synthetic DAO, and a snapshot test of the merged Work card with the source badge.
- **Prior art:** the existing Room schema-migration test (schema history is verified in CI), `MergeKey` normalization tests, source-adapter fixture tests, `ui/snapshots/` roborazzi tests, and the project CI gate (assembleDebug + testDebugUnitTest + Kover 15/9).

## Out of Scope

- Per-source genre/author/narrator browse screens («повний каталог sluhay/sluhayknigi» as browse tabs) — deferred until hydration lands; the merged feed + filters covers the need first.
- WebView background hydration without a live session — WebView sources hydrate on open only.
- Reinventing identity normalization — the validated title+author (+narrator) MergeKey is reused.
- Android Auto, widget changes, and any change to the listening/library tables.

## Further Notes

- The first hydration target is 4read (largest clean catalog); sluhay/sluhayknigi follow through a live session; sound-books and sluhayua through their catalog endpoints.
- Ticket chain (blocking edges): T1 (schema + merge-on-write) is the foundation; T2 (4read hydration) and T3 (WebView-session hydration) and T4 (infinite feed) all block on T1; T5 (source badges + «Джерела» section) blocks on T1. T2/T3 feed depth into the feed; T4 is verifiable with synthetic data before hydration lands.
- Existing separate initiative: a parallel session is splitting the library god module (#134–#140, ADR-0002). Spec-23 adds a browse layer on top of the DAO — the two touch the same DAO seam, so T1 should land cleanly on whichever DAO shape is current; no conflict is expected because spec-23 is additive (new tables) and ADR-0002 promises the Room schema is untouched.
