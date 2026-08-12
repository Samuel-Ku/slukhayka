# [Spec] One 4read parser behind a complete source seam — 2026-08-11

> **Status:** Approved (2026-08-11) — synthesized from the architecture review (three explore agents: data/source, player, UI; vocabulary: codebase-design). Top recommendation (#1); follow-ups #2-#8 tracked separately. **T1 implemented (2026-08-12)** — `082ab1c`, closed [#83](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/83).
> **Source:** architecture review of `data/source`, `player`, `ui` — the repository's private 4read parser fork bypassing the `SourceAdapter` seam.
> **Tracker:** filed as issue [#82](https://github.com/Samuel-Ku/4read-audiobooks-player/issues/82) (labels `spec-14`, `ready-for-agent`) + tickets #83–#88.

## Problem Statement

From the listener's perspective, 4read books come into the library through several doors — a link import, a WebView book page, the in-app search, a SQLite database from another app — and each door produces a slightly different card. Detail pages lose rating, genres, and series. Occasionally a book appears in the library that does not exist anywhere (a fabricated fallback copy). The app cannot test any of this, because the parsing logic lives inside a 2030-line repository that no test can construct cheaply.

The root cause is architectural: the repository carries a **second, private, untested implementation** of the 4read parser (its own regexes, its own HTTP client, its own URL encoder) that bypasses the `SourceAdapter` seam the app already has — because that seam's `SourceBookDetail` cannot carry rating, genres, series, or related books, so every caller that needs those fields is forced around the seam.

## Solution

One parser, behind a complete seam. The repository stops parsing and stops fetching: all 4read markup and HTTP knowledge moves behind the existing `SourceAdapter` interface, whose `SourceBookDetail` grows to carry the full book profile (rating, genres, series, related books). The WebView HTML import becomes a small pure module that maps page DOM to `SourceBookDetail` and is *implemented on top of* the adapter — not a third parser variant. The fabricated fallbacks are deleted. Import behavior is unchanged from the listener's point of view, except that all doors now agree.

## User Stories

1. As a listener, I want rating, genres, and series shown on a book's detail page, so that I can judge a book before listening.
2. As a listener, I want "related books" to work from a 4read detail page, so that I can discover similar titles.
3. As a listener, I want the same 4read book added via link, via WebView page, or via in-app search to produce the same card, so that I never see two disagreeing versions of one Work.
4. As a listener, I want a missing book to be reported as missing, so that my library never shows a book that does not exist.
5. As a listener, I want importing a book from another app's database to keep working, so that I can migrate my library.
6. As a listener, I want audio extraction to keep working for both the page pattern and the playerjs pattern, so that every 4read book plays.
7. As a maintainer, I want all 4read markup parsing in exactly one module, so that a markup change is fixed in one place.
8. As a maintainer, I want all 4read HTTP in exactly one module, so that user-agent/header policy is uniform.
9. As a maintainer, I want the book id scheme ("4read-slug") defined in exactly one place, so that ids cannot diverge across import paths.
10. As a maintainer, I want the repository to stop owning parser and transport code, so that it can shrink and its tests get cheap.
11. As a maintainer, I want the WebView HTML import tested as a pure function with page fixtures, so that markup regressions are caught on the JVM.
12. As a maintainer, I want the adapter's public claim "the repository delegates to me" to become true, so that code comments stop lying.
13. As a maintainer, I want no behavioral change to imports while this refactor lands, so that listeners are not disrupted.
14. As a maintainer, I want the refactor to respect ADR-0001, so that Work/Edition/Source identity separation is preserved.

## Implementation Decisions

- **Complete the existing seam (no new module for 4read).** `SourceBookDetail` grows the four fields callers currently bypass the seam to obtain — rating, genres, series (name + position), related books. The per-source adapter fills them. Trimmed type shape (decision-rich part, from the review):

  ```
  SourceBookDetail {
    ...existing fields (title, author, cover, chapters, description, language, narrator)
    rating: Double?
    genres: List<String>
    series: SeriesRef?   // name + position when known
    related: List<RelatedBook>
  }
  ```

- **One parser, one transport.** `FourReadAdapter` becomes the only module that knows 4read markup (detail page, audio extraction, playerjs) and the only module that talks to 4read over `HttpFetcher`. Its existing per-adapter tests pin the markup, so the fork's behavior is provably redundant before deletion.

- **Delete the repository's private fork:** its own audio-extraction regexes, its own HTTP client, its own URL encoder, and the 29 loose regex literals. The five public methods that currently route around the seam (`fetchRelatedBooks`, `getChaptersList`, `refreshBookCoverAndDetails`, `importAudiobookFrom4ReadUrl`, `searchAudiobooksOn4Read`) become thin calls that pass the enriched `SourceBookDetail` through.

- **WebView HTML import becomes a small pure module** (`WebViewHtmlParser`): page DOM in, `SourceBookDetail` out, implemented on top of the adapter's data shape. No third parser variant — it reuses the adapter's extraction helpers.

- **Legacy SQLite import stays** as a row-copy of a book record into the library (no markup parsing involved) — unchanged behavior.

- **Delete the fabricated archive.org fallbacks** (four copies: 2/3/3/2 chapters) that violate the repo's own "refuse to fabricate" doctrine; the missing-book case surfaces as absent, per user story 4.

- **Id scheme owned by the adapter:** "4read-slug" is produced in exactly one place.

- **No schema changes.** Room schema is untouched (v8 already carries arbitrary source ids); ADR-0001 identity rules are preserved; `Source Binding` and `Listening State` handling is untouched.

## Testing Decisions

- **A good test pins external behavior through the seam:** given a page/HTML fixture, the adapter produces the expected `SourceBookDetail` fields; given a stream URL, the expected playable URL. Tests never reach into parser internals or the repository's private state.
- **Parsing — existing per-adapter seam, extended fixtures:** `FourReadAdapterTest` grows fixtures for rating, genres, series, and related books, mirroring the no-network fixture style already used by `FourReadAdapterTest`, `SluhayuaAdapterTest`, `AudiobookMp3AdapterTest`, `SoundBooksAdapterTest`, and `LihtarAdapterTest` (all JVM, `FakeFetcher`-driven).
- **WebView HTML import — new pure-function tests:** page-DOM fixtures → `SourceBookDetail`, same fixture style as `SluhayuaAdapterTest` og-tag parsing; `FakeFetcher` not needed (pure input/output).
- **Repository import flows — existing repository seam:** import-through-link, import-through-WebView, and SQLite import stay covered by the existing repository tests with fake adapters (`AudiobookRepositoryRoomTest`, `GlobalSearchRepositoryTest`, `SourceFeedsRepositoryTest` patterns).
- **Fabrication regression:** a fixture asserting a missing book yields absent (not a forged fallback), as a direct pin of user story 4.
- **Device check** per repo convention (`docs/phone-test/PLAN.md`): import a 4read book via search and via the WebView path; verify detail fields render and playback works.

## Out of Scope

- Architecture-review follow-ups #2 (capability flags + shared scraper), #3 (offline downloader module), #4 (PlayerState split), #5 (smart-rewind unification + sourceKey bug), #6 (delete `WebViewInterceptionPrototypeActivity`), #7 (MainViewModel split), #8 (stranded UI rules) — each is its own future spec.
- Any UI changes (detail screens already consume the fields; rendering is out).
- Room schema changes; `Source Binding`/`Listening State` changes; new sources.
- Changing import behavior beyond making the doors agree.

## Further Notes

- The review's deletion test is the strongest argument: the adapter's fixtures already pin the same markup, so deleting the fork removes behavior with zero regression risk by construction.
- `FourReadAdapter` currently carries a comment claiming the repository delegates to it — the spec makes that claim true rather than editing it away.
- Follow-up candidates after this one, per the review's recommended order: #6 (20-minute deletion), #5 (fixes a live progress bug), #2, #4.

## Tickets

- **T1 — Complete the source seam: `SourceBookDetail` carries the full book profile** (#83): `SourceBookDetail` grows rating/genres/series/related; `FourReadAdapter` parses them (no-network fixtures); detail-refresh / related-books / chapter-list callers served through the seam; absent stays absent. No blockers. **✅ Done** — `082ab1c` (238 tests), closed with the full resolution.
- **T2 — Search door: 4read search through the seam** (#84): the legacy `searchAudiobooksOn4Read` route becomes a thin call over the adapter's `search()` + enriched detail. Blocked by T1.
- **T3 — Link-import door: import-by-URL through the seam** (#85): `importAudiobookFrom4ReadUrl` becomes a thin call passing the enriched detail; the door produces the same card as search/WebView. Blocked by T2.
- **T4 — WebView door: `WebViewHtmlParser` pure module** (#86): page DOM → `SourceBookDetail`, on top of the adapter's data shape; replaces the inline parse in `importAudiobookFromHtml`. Blocked by T3.
- **T5 — Contract: delete the private fork, the fabricated fallbacks, unify the id scheme** (#87): delete what remains of the fork (`importAudiobookFromHtml`'s inline parse, the archive.org chapter fallbacks, `Parsed4ReadData.ratingVotes`); the id scheme lives in one place. Blocked by T4.
- **T6 — Verify on device: import doors + missing-book behavior** (#88): import a 4read book via search and via the WebView path; verify detail fields render and playback works. Blocked by T5.
