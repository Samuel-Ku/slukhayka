# [Spec] Multi-source catalog: an aggregator over free Ukrainian audiobook sources — 2026-08-10

> **Status:** Approved — decisions locked in a grilling session on 2026-08-10 (Q1–Q6). Not yet implemented.
> **Source:** Grilling session «час розширювати асортимент» + code-fact checks. 6 resolved decisions, seams confirmed.
> **Tracker:** GitHub issues filed from this spec (one issue per ticket, T1–T6, `spec-10` + `ready-for-agent` labels).

## Problem Statement

The app's assortment is bound to a single source, 4read.org. A listener who searches for a book that 4read does not carry gets nothing, even when the same book is freely available on one of several other Ukrainian audiobook sites (Sluhay, Sound-Books, Sluhayknigi and others). The catalog cannot answer "which sources carry this book", the library cannot hold several editions of one work, and every new source would today be wired in by copy-pasting the 4read fetch+regex parser and sprinkling `sourceUrl.contains(...)` checks across the code.

From the user's perspective: **«Я шукаю книгу — а її немає, хоча вона є в іншому безкоштовному джерелі».** The product promise from the vision — «єдина медіатека для українських онлайн-аудіокниг і власних файлів» — is not met while online books come from one site.

## Solution

Turn the app into an **aggregator**: a unified native catalog over several free Ukrainian audiobook sources. All sources are equal citizens; a book may have several sources; the library card is per **Work**, playback position is per **source**; downloads work from every source whose ToS allows them (stream-only otherwise).

**Milestone 1 (this spec):** global search across all verified sources + «Нове з кожного джерела» feeds + import/play/download from any verified source, built on a Work-level identity with a narrator-aware merge key. The existing 4read fetch+regex path is refactored into the first `SourceAdapter` with no behavior change. The full per-source catalog (genres/authors/narrators browsing) is a later milestone — it subsumes the wayfinder ticket «Browse tab expansion» (#44).

**Milestone 2 (future, not this spec):** full per-source catalogs and genre/author/narrator browsing, once M1 proves the source pool.

## User Stories

1. As a listener, I want to search once across all sources, so that I find a book even when 4read does not carry it.
2. As a listener, I want each search result to show which source carries the book, so that I can judge where it comes from.
3. As a listener, I want to play a book found on any source directly in the app player, so that I never leave the app to listen.
4. As a listener, I want the same book found on several sources to appear as one card in results and library, so that my collection does not duplicate.
5. As a listener, I want to switch the source of a book on its page, so that I can pick a working link or a preferred narration.
6. As a listener, I want my playback position remembered per source, so that switching sources never corrupts my progress.
7. As a listener, I want to download chapters from any source whose terms allow it, so that I can listen offline.
8. As a listener, I want a «Нове з кожного джерела» feed, so that I can discover what appeared without browsing each site.
9. As a listener, I want a source badge on the book card, so that I can tell online / local / which site at a glance.
10. As a listener, when a book has several sources, I want the last-used source to play by default, so that I resume where I was.
11. As a listener, I want «Відкрити на сайті» to work for any source, so that there is always a fallback.
12. As a maintainer, I want each source's parsing isolated behind an adapter, so that a markup change breaks only that source.
13. As a maintainer, I want a new source admitted only after a spike verifies it against the four criteria, so that we never ship a broken, non-Ukrainian, or terms-violating source.
14. As a maintainer, I want the existing 4read import/play/download to keep working unchanged during the migration, so that no regression lands before the aggregator exists.
15. As a maintainer, I want local imported files to be a source of type LOCAL in the unified model, so that one library model covers everything.

## Implementation Decisions

### Source admission criteria (locked, applies to every current and future source)

1. **Ukrainian-language content** — the product's core promise («українські аудіокниги»), not a preference.
2. **Free and no registration** — the 4read model; we build no accounts, subscriptions, or payments.
3. **Technically playable** — direct audio URLs, no DRM, no player-only delivery. A source that only embeds a player is not an aggregator source, just a link.
4. **ToS/robots permits streaming** — verified in the spike before any parser is written. Downloads are allowed wherever streaming is allowed, unless the source's terms forbid downloading explicitly (then the source is stream-only).

### Source pool

- The pool is the user-provided list of 8 sites: **sluhay.com.ua, sluhay.com, sound-books.net, sluhayknigi.com, books-audio.in, audiobook-mp3.com/uk, md-eksperiment.org, notatky.com.ua/audiobooks, lihtar.in.ua** (the user's list carries 9 URLs across 8 named sites). All of them go under verification; we use as many as pass. No sources outside this list are added in M1.
- Pre-verified as real and Ukrainian: sluhay.com.ua, sluhay.com, sound-books.net, sluhayknigi.com. **Akniga.xyz is rejected** — it is a Russian-language mirror (fails criterion 1). ABUK (abuk.com.ua) is a paid store — outside the pool (criterion 2).
- Verification facts for each source: site live, Ukrainian content, free/no-registration, direct audio URL obtainable from the book page, search endpoint exists, "new" listing endpoint exists, download permitted by ToS/robots.

### Architecture

- **`SourceAdapter` interface** — one adapter per source, three operations for M1: `search(query)` → normalized book list; `fetchBookPage(url)` → cover + chapters (audio URLs, durations where available); `fetchNew()` → recent books feed. The existing 4read fetch+regex logic (`fetch4ReadPageDetails`, `searchAudiobooksOn4Read`, `extractAudioFromHtml`, `CatalogParser`) is refactored into the 4read adapter with **no behavior change** — this is the first adapter and the template for the rest.
- **Schema 7 → 8:** a `sources` table (source id, work id, source type 4READ/SLUHAY/…/LOCAL, url, stream-only flag, availability) and per-source playback position. `AudiobookEntity.sourceUrl` keeps its meaning (blank = local) and becomes the book's primary/current source URL; `isLocal` stays derived from it. ADR-0001 (separate Work, Edition, Source, listener state) becomes a requirement, not a concept: the library card is the Work; sources sit under it; listening state is keyed by source.
- **Merge key (dedup):** normalized (title + author + narrator) when the narrator is known, else (title + author). Normalization reuses the matcher machinery validated in the enrichment spike (#43 — title/author normalization, Cyrillic/Latin, punctuation, subtitle stripping). No audio fingerprinting — "same recording" is assumed when title+author+narrator match.
- **Local files** become a source of type LOCAL in the unified model; the #39 library filters (Локальні/Онлайн) keep working off the same distinction.

### Discovery (M1)

- **Global search:** query each verified source's search endpoint (4read search already exists; Sluhay `/find`; Sound-Books categories; Sluhayknigi genres — exact endpoints confirmed by the spike), merge results, dedup by merge key, show one card per Work with source badges.
- **«Нове з кожного джерела»:** per-source `fetchNew()` rows on the Explore/Слухати surface (pattern of the existing «Нове на 4read» rows).
- Full catalogs (genres/authors/narrators per source) are **not** in M1; they subsume wayfinder #44 later.
- The «4read Web» tab stays during M1 as a fallback and debugging surface; it is retired per-source once the native path covers that source.

### Behavior details

- Playback default when a Work has several sources: the last-used source; on first play from search, the source where the result was found.
- Found book → tap → import from that source (book + chapters into Room) → play, mirroring the current 4read import-and-play flow.
- «Відкрити на сайті» works for every source (currently 4read-only check).
- Downloads reuse the existing per-chapter download mechanism (it downloads `streamUrl`); for stream-only sources the download action hides.

## Testing Decisions

The four seams confirmed with the user (same set the listen-first spec used; no new seams):

1. **Parser seam** — each `SourceAdapter` is a pure JVM class tested against saved HTML fixtures of that source (prior art: `CatalogParserTest`). A markup change in one source fails only that source's fixture tests. The 4read refactor must keep its existing fixture tests green unchanged.
2. **Repository seam** — in-memory Room tests for schema 7→8 (sources table, stream-only flag), the merge/dedup upsert path, per-source position isolation, and the 6→7→8 migration chain against real v7 schema (prior art: `AudiobookRepositoryRoomTest`, existing migration tests 5→6, 6→7).
3. **Pure model seam** — merge key normalization and global-search result aggregation as pure functions (prior art: `LibraryModelTest`): dedup across sources, deterministic ordering, blank-author handling.
4. **Compose snapshot seam** — source badge on cards, source picker sheet, global search screen, «Нове» feed rows (prior art: `CatalogRowsSnapshotTest`, `LibraryComponentsSnapshotTest`, snapshot infra ADR-001).

Rules: no network in tests (fixtures and in-memory fakes only); tests assert external behavior, not parser internals; each iteration ends with `assembleDebug` + `testDebugUnitTest` green, device check where relevant, and a commit.

## Out of Scope

- **Full per-source catalogs** (genres/authors/narrators/short-long browsing) — later milestone; subsumes wayfinder #44.
- **Smart collections** (#43, external enrichment for mood collections) — separate effort; only its matcher normalization is reused.
- **Sources outside the user's list** (ABUK paid store, audioreads, ukrainianaudiobooks) — not in M1.
- **Audio fingerprinting / same-recording detection** — merge is metadata-based only.
- **Account/sync/payments** — none of the sources require or support them.
- **Removing the 4read Web tab** — deferred until native paths cover it.
- **Android Auto / Cast / widgets / backup-sync / accessibility overhaul** — separate milestones.

## Further Notes

- The spike (T1) is the immediate next step and is AFK-executable: verify all 8 listed sites against the four criteria, produce a per-source verdict table (live / Ukrainian / free / direct audio URL / search endpoint / new endpoint / download allowed) as a linked markdown asset, following the methodology of the «4read catalog data audit» (#31).
- ADR-0001 (separate Work, Edition, Source, listener state) is the design anchor of this effort; the spec intentionally implements what the ADR already promised.
- Prior art: the 2026-08-06 spec (SAF import, parser architecture, snapshot infra), the 2026-08-07 listen-first spec (seams, milestone discipline), #39 unified library (source badge, filters).
- Each iteration ends with: build (`./gradlew assembleDebug testDebugUnitTest`), device check on OnePlus 8 Pro (wireless ADB) where UI changed, and a commit.

## Tickets

- **T1 — Source pool spike** (research, AFK): verify the 8 sites against the four admission criteria; per-source verdict table; exact search/new endpoints. No blockers.
- **T2 — Sources schema & repository (7→8)**: `sources` table, per-source position, Work merge/dedup upsert with normalized key, migration test. Blocked by T1 (source id scheme and stream-only flags from the verdicts).
- **T3 — SourceAdapter layer + 4read refactor**: adapter interface, 4read adapter (behavior-neutral refactor), adapters for verified sources (search + book page + new). Blocked by T1.
- **T4 — Global search end-to-end**: aggregated search UI, dedup display, source badges, import-and-play from results. Blocked by T2 + T3.
- **T5 — «Нове з кожного джерела» feeds**: per-source rows on the listen/explore surface. Blocked by T2 + T3.
- **T6 — Downloads across sources**: verify/download mechanics on new sources, stream-only gating from verdicts. Blocked by T2 + T3.
