# [Spec] WebView-pattern sources: sluhay.com (first) — 2026-08-11

> **Status:** Approved — decisions from the wayfinder #70 grilling chain (#71 verdict PASS, #72 research, #73 UX self-grilling 2026-08-11). Adapter-first, similar shape to spec-11.
>
> **T1 implemented (2026-08-12)** — sluhayknigi spike closed (#78): same mp3 + Referer pattern as sluhay.com, playlist URL inline in page HTML, hydrate = server-fetch 200 with session cookies on BOTH sites, og:title/og:url/og:description confirmed (no og:image, no narrator). Asset: `docs/wayfinder/research/sluhayknigi-spike.md`, fixtures in `docs/wayfinder/research/fixtures/webview/`.
> **Tracker:** filed as `spec-13` issues (`spec-13` + `ready-for-agent` labels).

## Problem Statement

Two sources from the user's pool are behind a Cloudflare interactive challenge on every HTML path — **sluhay.com** and **sluhayknigi.com** — so the server-fetch adapter model (spec-10/spec-11) does not apply. Their books are invisible to the app. From the user's perspective: **«Книга є на Sluhay — а в застосунку її немає, і ніяк не знайти.»**

The wayfinder map #70 proved the way: a real WebView session passes the challenge (user action, once per clearance TTL — #72), `shouldInterceptRequest` catches the playback URL (#71), and the audio **is** playable by Media3 outside the session with a per-source `Referer` header (#71 verdict: 403 without → 206 `audio/mpeg` with `Referer: https://sluhay.com/`; the CDN is plain nginx, NOT Cloudflare).

## Solution

Add sluhay.com as the first WebView-pattern source: a fullscreen per-source browser surface (user passes the challenge, browses, searches), an interception layer that turns a playing book into a normal unified-library card (metadata from the page, chapters from the intercepted playback URLs), and playback/downloads through the app player with the per-source `Referer` header. **To keep discovery as 4read-like as possible, the Listen tab also gains a native «Нове з Sluhay» row**, hydrated from the homepage through the user's live WebView session (the real session, not an automatic CF bypass). sluhayknigi.com follows with the same pattern once its format is measured (T1). The source id is `sluhay` (badge «Sluhay»), distinct from the server-fetch `sluhayua` (sluhay.com.ua).

## User Stories

1. As a listener, I want to open a browser surface for a WebView source, so that I can find books that only Sluhay carries.
2. As a listener, I want to pass the Cloudflare challenge inside the app (not in a separate browser), so that I stay in one product.
3. As a listener, I want a book page in that browser to offer «Додати до медіатеки», so that the book becomes a normal library card.
3b. As a listener, I want a native «Нове з Sluhay» row on the Listen tab (like «Нове на 4read»), so that I discover sluhay additions without opening the browser first.
4. As a listener, I want the added book to play in the app player, so that I never switch apps to listen.
5. As a listener, I want the same book from Sluhay and another source to merge into one card, so that my library does not duplicate (narration-sensitive merge, spec-10).
6. As a listener, I want a «Sluhay» badge on the card, so that I know where the book lives.
7. As a listener, I want playback and downloads from Sluhay to work with the right headers automatically, so that 403s never appear.
8. As a listener, I want my position remembered per source, so that switching sources never corrupts progress.
9. As a maintainer, I want the interception layer to reuse `shouldInterceptRequest` (proven in #71) and NOT a JS bridge (SEC-003), so that the MITM vector stays closed.
10. As a maintainer, I want the per-source Referer threaded through one seam, so that sluhay and audiobookmp3 (same CDN family) never collide.
11. As a maintainer, I want sluhayknigi to join with the same pattern after its format is measured, so that no new architecture is invented per source.
12. As a listener, I want to search inside the browser session with the site's own search, so that discovery works without a server-fetch search endpoint.

## Implementation Decisions

- **Source ids / badges:** `sluhay` → «Sluhay» (sluhay.com), later `sluhayknigi` → «Sluhayknigi». Distinct from `sluhayua` (spec-11, server-fetch).
- **Browser surface (#73 decisions, self-grilling):** a fullscreen pushed destination per WebView source, generalizing the existing `FourReadWebScreen` fallback — NOT a bottom sheet, NOT a tab (product vision: WebView is a secondary surface). Entry points: a compact «більше книг на Sluhay →» row on the Listen tab and the existing «Відкрити на сайті» action on book pages.
- **Import — manual primary, auto-capture secondary:** on a book page inside the browser, a toolbar action «Додати до медіатеки» grabs page metadata + chapters and imports through the existing import path (Work row with mergeKey). If a playback URL was already captured (user pressed the site's «Слухати»), the same action reads it as «Додати цю книгу». No `addJavascriptInterface` — page data comes from `shouldInterceptRequest` observation + `evaluateJavascript` with an origin check against the source's own domain.
- **Metadata from the page:** `og:title` (title/author split on « - »), `og:description` (narrator from «читає X»), `og:image` (cover) — the same pattern `SluhayuaAdapter.fetchBookPage` already validates; fallback to `document.title`.
- **Chapters:** captured from the media requests the player makes (direct mp3 on `*.redirectto.cc`, playerjs, Range 206 — #71 verdict). One chapter per `track-N.mp3` captured; ordered by request sequence.
- **Per-source request headers — the key seam (T2):** the CDN (`redirectto.cc`) is shared by audiobookmp3 (needs `Referer: https://audiobook-mp3.com/uk`) and sluhay (needs `Referer: https://sluhay.com/`), so a URL-based heuristic cannot tell them apart. The source must carry its own headers: a per-source `referer` on the source row / chapter model, threaded into:
  - the **streaming path** — `DefaultHttpDataSource.Factory().setDefaultRequestProperties(...)` currently sends NO Referer (only UA), so today's audiobookmp3 streaming likely 403s too (latent, discovered here; verify on device) — T2 fixes both;
  - the **download path** — `downloadHeadersFor(streamUrl)` becomes source-aware (keeps the audiobookmp3 case working; adds the sluhay case).
- **Registry / merge:** the WebView sources join the same `SourceAdapter`-driven repository mechanisms where they can (badge, `sourceTypeOfUrl`, import-and-play) — with their `search()`/`fetchNew()` being session-bound (see below).

### Search and feeds for WebView sources

- **Global search** stays server-fetch (4read, soundbooks, audiobookmp3, lihtar, sluhayua). For WebView sources it offers a «відкрити в браузері джерела» action instead of results.
- **Native «Нове з Sluhay» row (4read-like discovery):** after the user has passed the challenge once, the app hydrates sluhay.com's homepage **through the live WebView session** (same cookies/UA — CF-valid by construction) and snapshots the poster rows (title/author/cover/link) into a native row on the Listen tab — the same `poster`-block markup `FourReadAdapter` already parses. Refresh when the session cookies are fresh; when stale (TTL expired, #72), the row shows a «відкрити Sluhay, щоб оновити» CTA instead of dead data. T1 measures whether the HTML can be fetched with cookies alone outside the WebView (then hydration is cheaper) or must go through the WebView's own load (DOM snapshot via `evaluateJavascript`) — either way the user's session does the CF gate, no bypass.
- **In-session search** remains the site's own search inside the browser surface.

## Testing Decisions

- **Interception seam (parser, JVM):** the captured-request log format from the #71 prototype becomes the fixture — `track-N.mp3` URL + order + Range headers → chapter extraction; page metadata parse (og tags) fixtures mirror `SluhayuaAdapterTest`. No network.
- **Headers seam (pure model):** the source-aware header function is a pure function (like `DownloadPolicy`): `headersFor(sourceId, streamUrl)` — pinned tests for sluhay → `Referer: https://sluhay.com/`, audiobookmp3 → `Referer: https://audiobook-mp3.com/uk`, others → empty. This is the highest JVM-able seam for the Referer threading; the player wiring is verified by build + device check.
- **Repository seam:** import from a WebView-source detail follows the existing import path — covered by the existing repository tests with fake adapters (no new schema: v8 already carries arbitrary source ids; per-source Referer rides on the source row, not a new table).
- **Device check (OnePlus 8 Pro):** the browser surface + challenge + import + playback with Referer, per the repo convention.

## Follow-on work (post-spec-13): full per-source catalogs (M2)

The T1 spike verdict unblocks this: **both sluhay sites hydrate — server-fetch returns 200 with session cookies and `cfChallenge=false`** — so category pages are reachable through the same hydration path T4 builds for the homepage. The category pages use the same `poster-item grid-item` row markup the homepage carries, so the parser T4 writes extends to them with no new architecture. Per-source genre/author browsing becomes a natural extension of the T4 row — the native «Нове з Sluhay» row generalizes into a native «Категорії Sluhay» surface.

- **F1 — Category-page spike** (research, AFK, reuses the T1 session): enumerate the catalog URL scheme of sluhay.com and sluhayknigi.com — genre listing URLs, pagination, whether author pages exist at all (book pages are a negative finding for narrators, so author/narrator facets must be verified on category pages before promising them), series pages. Capture fixtures from a live session per the T1 pattern. Verdict: which facets (genres / authors / narrators / series) exist and their exact URL shapes. No blockers (can run in parallel with T2–T4).
- **F2 — Catalog data seam** (task): extend the SluhayAdapter with per-facet catalog fetching — hydrate a category page through the session cookies (server-fetch, the T1 verdict), parse its poster rows with the T4 row parser, map to `SourceBookDetail`. Fixture tests per facet type. Blocked by F1 + T4.
- **F3 — Native browse surface per source** (task): the catalog surface for sluhay/sluhayknigi — facet list → book rows → import-and-play, reusing the T4 stale-session pattern (fresh cookies → hydrated rows; stale → «відкрити джерело, щоб оновити» CTA). Subsumes the spec-10 «Browse tab expansion» (#44) for these two sources. Blocked by F2 + T3.

Notes: search stays in-session (the site's own search inside the browser surface, spec-13 decisions) — the native catalog is read-only discovery, like T4. When F1–F3 land, the same catalog treatment applies to the server-fetch sources (spec-10 M2) through their own `SourceAdapter.fetchCatalog()` — the facet seam above should be designed source-agnostic so both source families share one browse UI.

## Out of Scope

- **Full per-source catalogs (M2)** — moved to Follow-on work above; genre/author browsing lands as F1–F3 once T2–T4 ship.
- **Automatic Cloudflare bypass** — the user passes the challenge themselves (map #70 boundary).
- **Automatic Cloudflare bypass / cookie forging** — the user's session (real cookies from the live WebView session) is the only credential; server-fetch uses it as-is (T1 verdict: 200, cfChallenge=false) — no bypass, no synthetic clearance.
- **Android Auto / Cast** for WebView sources — separate milestones.
- **The UX surface beyond the first source** — #73 decisions apply; sluhayknigi joins with the same surface after T1 measures its format.

## Further Notes

- The wayfinder map #70 has reached its destination — the way is clear and handed off here: #71 (interception proven: mp3 + Referer), #72 (session persistence), #73 (UX surface decisions). sluhayknigi's format is the only remaining fact — T1.
- The #71 prototype (`WebViewInterceptionPrototypeActivity`, THROWAWAY) is the reference for the interception layer; it stays until T2 lands, then is deleted.

## Tickets

- **T1 ✅ — sluhayknigi spike + page-metadata fixtures + hydrate mechanics** (research, AFK+HITL, closed #78 2026-08-12): sluhayknigi format = same mp3 + Referer as sluhay.com; **playlist URL inline in book page HTML** (`Playerjs({…file:"<id>.pl.txt"})`); hydrate verdict = **server-fetch 200 with session cookies on both sites** (`cfChallenge=false`); og:title/og:url/og:description confirmed, **no og:image** (cover in `data-src`), **no narrator in og-tags** (only «Ютуб канал диктора» link). Fixtures committed; verdict in `research/sluhayknigi-spike.md`.
- **T2 — WebView source: interception + metadata + per-source headers** (task): interception layer (shouldInterceptRequest → ordered chapters), page metadata → SourceBookDetail, the source-aware Referer seam threaded into streaming and download paths (fixing the latent audiobookmp3 streaming gap), fixture tests. Blocked by T1.
- **T3 — Browser surface + wiring** (task): fullscreen per-source browser (per #73 decisions), «Додати до медіатеки» panel, registry + badge «Sluhay», device check. Blocked by T2.
- **T4 ✅ — Native «Нове з Sluhay» row** (task): hydrate the homepage through the live WebView session per the T1 verdict (server-fetch with cookies, or DOM snapshot), parse the poster rows like `FourReadAdapter`, show the native row on the Listen tab with a stale-cookie CTA; refresh on fresh sessions. Blocked by T3. **Done** — `c9ace33` (10 files, 9 fixture/seam tests), closed [#81](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/81). Server-fetch with the WebView-jar cookies (`cf_clearance` etc.) per the T1 verdict; `SourceAdapter.sessionBound` marks WebView sources, the feed pipeline surfaces the «Відкрити Sluhay» CTA on an absent/stale session and re-hydrates on every refresh (no TTL cache) so a fresh challenge shows the row immediately. Remaining: device check on OnePlus 8 Pro.
