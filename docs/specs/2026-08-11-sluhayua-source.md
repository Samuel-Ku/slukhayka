# [Spec] sluhay.com.ua source (sluhayua) — 2026-08-11

> **Status:** Draft — proposed next wave after spec-10 M1. **T1 spike resolved 2026-08-11: PASS** (see `docs/wayfinder/research/sluhayua-spike.md`). Tickets not yet filed.
> **Source:** T1 spike verdicts (`docs/wayfinder/research/source-pool-spike.md` + `sluhayua-spike.md`): sluhay.com.ua is the only remaining server-fetch candidate — live, Ukrainian, free (donations), no Cloudflare. The playlist is inline in the book page; audio comes from `/play` per file; search and «new» come from the `/find/allcards` JSON API.
> **Tracker:** planned as spec-11 issues (`spec-11` + `ready-for-agent` labels), pending approval.

## Problem Statement

The aggregator (spec-10 M1) covers 4read + soundbooks + audiobookmp3 + lihtar — all server-fetch. One more source from the user's original pool is technically eligible for the same native path: **sluhay.com.ua**. It has a working search (`/find`, `/find/genre=`), a full sitemap, and no Cloudflare, but its book pages load the audio playlist via an undiscovered XHR call from minified player JS — so today the adapter cannot be written. Until the endpoint is found and the adapter lands, books that only sluhay.com.ua carries are invisible to the app.

From the user's perspective: **«Книга є на sluhay.com.ua — а в застосунку її немає, хоча джерело підходить під усі критерії».**

## Solution

Add sluhay.com.ua as a native server-fetch source (id `sluhayua`) using the proven spec-10 pattern:

1. **Spike (T1):** **done** — verdict PASS. Playlist is inline in the book page HTML; audio per chapter comes from `GET /play?bookId&fileId` (requires only `X-Requested-With: XMLHttpRequest`, no cookies/CSRF); search and «new» come from `GET /find/allcards` (JSON with real title/author/narrator/cover/duration); `sort=time&order=desc` gives newest first; `robots.txt` fully open, no ToS restriction — downloads allowed.
2. **Adapter (T2):** `SluhayuaAdapter` implementing `search()` (`/find/allcards`), `fetchBookPage()` (page → inline playlist length + `og:title`/`og:description` → `/play` per file), `fetchNew()` (`sort=time`), with fixture tests from the spike captures — mirroring SoundBooksAdapter/AudiobookMp3Adapter. Requires one `HttpFetcher` extension: per-request extra headers (`X-Requested-With`).
3. **Wiring (T3):** register `sluhayua` in the repository's adapter registry (global search + feeds are already registry-driven — no new UI), verify merge against other sources via the existing `MergeKey`, device check.

Native-first: no WebView involvement; the source behaves exactly like the other server-fetch adapters.

## User Stories

1. As a listener, I want to search once and find books that only sluhay.com.ua carries, so that the aggregate catalog grows.
2. As a listener, I want a book found on sluhay.com.ua to play in the app player, so that I never leave the app.
3. As a listener, I want the same book on sluhay.com.ua and another source to appear as one card with two badges, so that my library does not duplicate.
4. As a listener, I want the «Нове з кожного джерела» feed to include sluhay.com.ua, so that I can discover its additions.
5. As a listener, I want to download from sluhay.com.ua if its terms allow it (else stream-only), so that offline listening works where permitted.
6. As a maintainer, I want sluhayua parsing isolated behind the adapter with fixture tests, so that a markup change fails only that source.

## Implementation Decisions

- **Source id:** `sluhayua` (from the T1 spike's recommended scheme).
- **Adapter seam:** the existing `SourceAdapter` interface — three operations, no new architecture. The `HttpFetcher` seam gains an optional per-request extra-headers param (`X-Requested-With: XMLHttpRequest` is the only gate on `/play` and `/find/allcards`); `FakeFetcher` serves by URL and ignores headers, so fixture tests keep working.
- **Merge:** existing Work-level `MergeKey` (normalized title + author [+ narrator when known]). The `/find/allcards` JSON already carries real Cyrillic `bookName`/`bookAuthor`/`audioAuthor` — no placeholder authors (spec-10 lesson, `68a3088`); blank `bookAuthor` (`[" "]`, collections) never merges, consistent with the T2 rule.
- **Downloads:** **allowed** — `robots.txt` has no Disallow, no ToS restriction found, the site tracks `downloadedTimes` (downloads are intended use). `DownloadPolicy.streamOnlyFor("sluhayua") = false`; the mp3 CDN serves range requests with a plain GET.
- **Feed:** `/find/allcards?sort=time&order=desc` — newest first, real metadata in the JSON (spec-10 lesson, `0f4bb20`).
- **Registry wiring:** `sourceAdapters` list in `AudiobookRepository` gains `SluhayuaAdapter`; feeds list gains it too. Global search, badges (`sourceDisplayName`), import-and-play and downloads work without UI changes.

## Testing Decisions

Same seams as spec-10 (no new seams):

1. **Parser seam** — `SluhayuaAdapterTest` against real HTML/JSON fixtures captured in the T1 spike (page HTML, player JS, XHR response). Prior art: `SoundBooksAdapterTest`, `AudiobookMp3AdapterTest`.
2. **Repository seam** — the existing registry-driven tests (`GlobalSearchRepositoryTest`, `SourceFeedsRepositoryTest`) already cover multi-adapter aggregation with fake adapters; sluhayua joins the same mechanism — no schema change (v8 already carries arbitrary source ids), so no migration work.
3. **Pure model seam** — merge behavior is unchanged and already covered; only a `sourceDisplayName` mapping (`sluhayua` → «Sluhay») is added.

Rules (spec-10): no network in tests; fixtures only; each iteration ends with `assembleDebug` + `testDebugUnitTest` green and a commit.

## Out of Scope

- **WebView-interception** for sluhay.com / sluhayknigi.com — a separate wayfinder effort (Cloudflare challenge; different pattern).
- **Full sluhay.com.ua catalog browsing** (genres/authors/narrators) — M2 concern, per-source catalog work.
- **Account features** — none; the site is free with donations, the app never logs in.

## Further Notes

- The spike (T1) ran as the wave's first step (AFK) and resolved with a PASS — the endpoint specs, fixture shapes and risks live in `docs/wayfinder/research/sluhayua-spike.md`.
- The WebView fallback is moot — the server-fetch path is confirmed; the WebView-pattern effort (sluhay.com / sluhayknigi.com) stays its own wayfinder map.
- Prior art: spec-10 (`2026-08-10-multi-source-catalog.md`) — adapter seam, merge key, download policy, registry-driven wiring.

## Tickets

T1 is resolved; T2–T3 remain:

- **T1 — sluhayua spike** (research, AFK): reverse-engineer the playlist-XHR endpoint from a live book page; verify direct playable audio; confirm `/find` server-render vs SPA; pick the «new» feed source; ToS/robots download verdict; capture fixtures. No blockers. **✅ Done** — verdict PASS, `docs/wayfinder/research/sluhayua-spike.md`.
- **T2 — SluhayuaAdapter** (task): `search` (`/find/allcards`) + `fetchBookPage` (inline playlist + `/play` per file) + `fetchNew` (`sort=time`) with fixture tests; `HttpFetcher` extra-headers extension; `DownloadPolicy` entry (allowed). Blocked by T1.
- **T3 — sluhayua wiring** (task): register the adapter in the repository registry; `sourceDisplayName`; global-search + feed verification; device check on OnePlus 8 Pro. Blocked by T2.
