# [Spec-15] Netflix-style catalog of ALL books, no browser in the UI, unified library

> **Status:** Draft — synthesized from the 2026-08-13 self-grilling (user delegated the interview; recommendations locked). Pending tickets on GitHub.
> **Tracker:** filed as issue (labels `spec-15`, `ready-for-agent`).

## Problem Statement

From the listener's perspective, the app is still a 4read-flavoured player: the Огляд tab shows 4read's catalogue rows plus per-source «Нове з …» strips, but there is no single place where **all books of all sources** sit side by side as one Netflix-style storefront. Books from WebView-pattern sources (sluhay.com, sluhayknigi.com — behind Cloudflare) are only reachable through an in-app browser surface, which breaks the product's calm native feel and its security posture. Duplicates are theoretically impossible (MergeKey), but catalogue browsing does not yet apply the same Work-level dedup as search and import, so the same book can still appear twice in different rows. Downloading a whole book for offline listening works but lives only on the detail screen — not on catalogue cards. And a book's detail page shows a single source's description/rating, not what every source says about it.

The root causes: (1) the catalogue surface is per-source strips, not a deduplicated union; (2) WebView-pattern sources have no native catalogue path; (3) the browser surface is a first-class UI destination instead of a debug-only tool; (4) catalogue cards carry no download affordance; (5) detail aggregation exists for one source only.

## Solution

From the listener's perspective, one **native Netflix-style catalog** of every book from every source, deduplicated into single Work cards. No browser anywhere in the UI — the WebView surfaces become debug-only tooling (and a one-time catalogue hydration path), and «Відкрити на сайті» opens the system browser instead of an in-app WebView. Tapping any card opens a native detail page that aggregates descriptions, ratings, and narrators from **all** sources that carry the Work, with a one-tap «Download» that fetches the whole book for offline listening, and «Play» that starts instantly.

## User Stories

1. As a listener, I want one screen where every book from every source appears in a deduplicated Netflix-style grid, so that I can browse the whole catalog without thinking about where a book lives.
2. As a listener, I want the same book from two sources to appear exactly once, with both source badges on its card, so that I never see duplicates.
3. As a listener, I want to search once across all sources and see one card per book, so that results are clean.
4. As a listener, I want to tap any catalogue card and listen immediately, so that I never have to add a book to the library before playing it.
5. As a listener, I want a one-tap «Download» on a catalogue card that fetches the whole book, so that I can take it offline without opening a detail page.
6. As a listener, I want a downloaded book to play from the local copy even with no network, so that offline listening works on a plane or in a car.
7. As a listener, I want the book detail page to show descriptions, ratings, and narrators from every source that carries the book (each labelled with its source), so that I can learn more before listening.
8. As a listener, I want the detail page to keep the enriched profile (author, narrator, genres, rating, series, related books) for every source's parse, so that no door loses metadata.
9. As a listener, I want «Відкрити на сайті» to open the system browser, so that the app never feels like it switches into a website.
10. As a listener, I want books that only exist on WebView-pattern sources (sluhay.com, sluhayknigi.com) to appear in the native catalog, so that "all books" actually means all books.
11. As a listener, I want the library (Медіатека) to filter by All / Listening / Finished / Downloaded / Local / Online / Favorites, so that I can organise my collection.
12. As a listener, I want per-source listening position preserved, so that resuming a book after switching sources does not reset progress.
13. As a listener, I want the first-run screen to offer «Переглянути каталог» and «Додати власні файли», so that a fresh install is never a dead end.
14. As a maintainer, I want the browser surface hidden behind a debug flag, so that no release build ships an in-app browser.
15. As a maintainer, I want the WebView catalogue hydration to be a debug-only one-time tool that snapshots a source's catalogue into Room, so that release builds never need a session.
16. As a maintainer, I want catalogue enumeration to run through the existing `SourceAdapter` seam (new catalogue methods), so that a markup change fails only that source's fixture tests.
17. As a maintainer, I want Work-level dedup applied to catalogue browsing, reusing `MergeKey`, so that one rule governs search, import, and browse.
18. As a maintainer, I want the download affordance on catalogue cards to reuse the existing offline-download path, so that stream-only gating stays in one place.
19. As a maintainer, I want detail aggregation to reuse the existing per-source page fetch, so that no third parser variant appears.
20. As a maintainer, I want the whole change tested on the JVM with fixtures and fake adapters, so that no device is needed to pin the behaviour.

## Implementation Decisions

- **Catalog = a deduplicated union over the adapter seam.** The `SourceAdapter` interface gains catalogue enumeration (e.g. `fetchCatalog(limit)` / category pages) alongside the existing `search` / `fetchNew` / `fetchBookPage`. The repository merges the union through `MergeKey` into Work rows, exactly as import and search already do. One rule, one place. Sources without a full catalogue endpoint enumerate via their category pages (sound-books, audiobook-mp3, lihtar, sluhayua all have them; 4read already has the native catalogue).
- **Work-level dedup in browse.** Cards in catalogue rows are Work cards: one card per normalized (title + author + narrator), with a source badge per carried source. Reuses `MergeKey` unchanged (ADR-0001: different narrations stay separate Works).
- **No browser in release builds.** `BuildConfig.DEBUG` gates the WebView surfaces (`FourReadWebScreen`, `WebSourceBrowserScreen`). In release, «Відкрити на сайті» launches the system browser via an ACTION_VIEW intent. The 4read legacy browser door is removed from the UI entirely (its seam-tested import doors remain in code for fixtures).
- **WebView catalogue hydration (debug-only).** A debug-only one-time tool passes the source's Cloudflare challenge in a hidden session, crawls the catalogue (sitemap/category pages in batches), and snapshots books (metadata + cover + book URL) into Room as normal Source rows through the existing import path. Release builds render these natively; streaming/downloads use the existing per-source Referer seam (already verified on-device: HTTP 206, no 403). Catalogue freshness is a periodic re-hydration, not a live browser.
- **One-tap download on catalogue cards.** The card's download affordance calls the existing offline-download path (`downloadAudiobookOffline`): per-chapter parallel fetch, progress on the card, local playback. `streamOnly` sources hide the affordance (lihtar; WebView sources per T1 verdicts).
- **Detail = per-source aggregation.** The detail page iterates the Work's Source rows, fetches each source's page through its adapter, and renders per-source blocks: description, rating, narrator, genres — each labelled with the source badge. Aggregate profile stays for the primary source. Honest scope: sources expose descriptions + ratings, not user-written reviews; no review scraping.
- **Instant play from catalogue.** Tapping a card plays the book through `playFromSource` (import is transparent), with per-source position restore. No explicit "add to library" step before listening.
- **Unified library (Медіатека).** Filter chips: All / Listening / Finished / Downloaded / Local / Online / Favorites; sort by recently listened / added / title / author / progress / duration; card shows author, series + part, progress, time left, download status, small source badge. Deletion stays three-level (remove from library / delete downloaded copy / delete files — explicit confirm).
- **Navigation shape unchanged:** Слухати · Огляд · Медіатека, mini-player pinned above the bottom bar. Огляд becomes the storefront; Слухати stays listening-first (continue hero, recent, series next, downloaded, «Нове»).

## Testing Decisions

- **What makes a good test:** external behaviour — a book from two sources is one card; a catalogue card downloads whole; a debug build shows the browser entry while a release build does not; a WebView-source book appears in the catalog from a hydrated snapshot. Fixtures pin markup per source; fake adapters pin the repository/UI logic; no device needed for the core.
- **Modules tested:** adapter fixture tests per source (catalogue enumeration, new markup); repository seam tests with fake adapters (union dedup, instant play, download gating, hydration import); UI tests / snapshot tests for the storefront grid, the card download affordance, the per-source detail blocks, and the debug-gated browser entry; `BuildConfig.DEBUG` gating test.
- **Prior art:** existing `SourceFeedsRepositoryTest` / `GlobalSearchRepositoryTest` (fake adapters through the repository seam), per-source `*AdapterTest` fixtures, `MergeKey` unit tests, snapshot tests under `ui/snapshots/`, and the spec-14 seam pattern (pure parser module + repository seam). The debug-gating prior art is the existing `BuildConfig.DEBUG` overlay gate in the player.

## Out of Scope

- User-written reviews / review scraping (sources expose descriptions and ratings only).
- Android Auto, Cast, widgets, sync — separate efforts (already charted).
- Changing the app identity, repo name, or source ids (locked in the rebrand spec).
- A visible in-app browser in release builds — by definition.
- Social features, achievements, AI recommendations, complex equaliser (existing product-vision out-of-scope list).
- Physical file deletion without explicit confirmation (stays three-level).

## Further Notes

- The wayfinder browse-expansion ticket (#44) is absorbed by this effort: genres/authors become per-source catalogue questions.
- sluhay.com and sluhayknigi.com are the two WebView-pattern sources; their hydration tool is the only place a session is ever used, and it is debug-only.
- The phone session #88 found two live-markup bugs (JSON `\u003C`/`\uXXXX` escapes in WebView captures and sluhayua's allcards JSON) — both fixed with regression tests; catalogue hydration must reuse the same unescape path.
