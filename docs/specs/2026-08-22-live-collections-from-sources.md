# Spec-37: живі добірки книжок з джерел — ТОП-100 sound-books та «Популярність» sluhayua

> **Status:** ready-for-agent. Синтезовано з автономної сесії `/grill-me`
> 2026-08-22 (Р1–Р9, усі за рекомендаціями). Жодного нового шва — тільки
> існуючий шов живих колекцій (ADR-0013).

## Problem Statement

Блок «Колекції» на Огляді сьогодні збирається лише з трьох статичних списків
нагород (Нобелівські лауреати, Шевченківська премія, Букер) та одного живого —
«Популярне зараз», який тягне OpenLibrary trending: англоцентричний список,
що рідко перетинається з українським каталожним union. Добірок мало, вони
майже не оживають, а найсвіжіші популярні аудіокниги ніколи не потрапляють на
поверхні відкриття — хоча самі джерела, які застосунок уже несе, публікують
власні списки популярності (ТОП-100 на sound-books.net, «Популярність» на
sluhay.com.ua).

## Solution

Тягнути ще дві живі добірки з уже верифікованих джерел крізь наявний шов живих
колекцій (ADR-0013): «ТОП-100 sound-books» із серверно відрендереної топ-
сторінки sound-books.net та «Популярне у sluhay.com.ua» з ендпоінта каталогу,
відсортованого за завантаженнями. Обидві їдуть спільним HTTP-транспортом,
годують той самий `CollectionMatcher` проти каталожного union на тих самих
тригерах оновлення, з'являються на екрані «Колекції» й блоці Огляду без жодних
змін UI; контракт best-effort (збій → нема колекції) і TTL-кеш per source —
як в існуючого живого списка.

## User Stories

1. As a listener, I want a «ТОП-100 sound-books» collection in Колекції, so that I can discover what is most popular on one of my audiobook sources right now.
2. As a listener, I want a «Популярне у sluhay.com.ua» collection, so that the most-downloaded books of another source reach my discovery surfaces.
3. As a listener, I want these collections refreshed automatically when the catalog refreshes, so that I never browse stale lists.
4. As a listener, I want only the entries my catalog actually carries to be shown, so that every card I see is playable.
5. As a listener, I want a collection book card to resolve-and-play exactly like the award-collection cards, so that all collections behave the same.
6. As a listener, I want a source being down to simply omit its collection, so that Огляд never breaks and never shows an empty shell.
7. As a listener, I want repeated refreshes within a session not to re-download the same lists, so that browsing stays fast and polite to the sources.
8. As a listener, I want each collection to say where its list comes from, so that I can judge why it looks the way it does.
9. As a listener, I want empty collections to disappear instead of rendering zero cards, so that Колекції only shows real shelves.
10. As a listener offline, I want the app to work exactly as before without live lists, so that live collections are a bonus, never a dependency.
11. As a listener, I want Cyrillic titles and authors matched correctly (ї/й preserved), so that the right books join the right collection.
12. As a listener, I want book titles containing dashes to still parse into title + author correctly, so that no entry is mangled before matching.
13. As the maintainer, I want both lists behind the existing live-collection seam, so that adding them changes no schema, no UI and no transport code.
14. As the maintainer, I want parsing pure-JVM and fixture-tested, so that an upstream shape change is caught in `./gradlew test`, not in production.
15. As the maintainer, I want all HTTP through the shared fetcher, so that header policy stays in one place (ADR-0006).
16. As the maintainer, I want entry caps on both lists, so that a huge upstream list cannot balloon the match workload.
17. As the maintainer, I want junk rows (missing author / blank title) dropped silently, so that only honest claims reach the matcher.
18. As the maintainer, I want stable ids and distinct display names for the new lists, so that diagnostics and the UI never confuse them with «Популярне зараз» or the static assets.
19. As the maintainer, I want 4read's popular block NOT duplicated as a collection, so that one surface owns one presentation (one tool, one place).
20. As the maintainer, I want the gated sluhayua endpoint's required header asserted by tests, so that the endpoint contract is pinned, not folklore.

## Implementation Decisions

- **Two new implementations of the existing seam** in the collections module;
  the seam interface itself unchanged (ADR-0013). No new seams anywhere.
- **Transport:** shared `HttpFetcher` only (ADR-0006). **Parsing:** pure JVM —
  `MiniJson` for the JSON endpoint; scoped regexes for the HTML page.
- **soundbooks source.** Fetches the site's ТОП-100 page; parses ONLY the
  main-list tiles (`short-item`/`short-title`) — never sidebar, comments or
  related blocks (all three were verified to carry book links too). Tile anchor
  text is «Назва - Автор»; split at the LAST " - " separator because titles
  contain dashes too. Rows without a parsable author or with a blank title are
  dropped. Id `soundbooks-top`, name «ТОП-100 sound-books».
- **sluhayua source.** Fetches the catalog endpoint sorted by downloads
  descending; the endpoint is XHR-gated — a plain GET serves an empty shell
  (verified), so the request carries the XMLHttpRequest header exactly like the
  adapter's other endpoints. Title = `bookName`, author = first element of
  `bookAuthor`. Id `sluhayua-popular`, name «Популярне у sluhay.com.ua».
- **Caps.** Both sources default-limit to 40 entries, mirroring the existing
  live source; the matcher hides non-matches anyway.
- **Registration order = display order.** The composition root appends both
  after the OpenLibrary source; `matchAll` preserves input order, so Огляд
  shows a stable sequence.
- **Provenance note.** Each list's sourceNote names its site and states the
  auto-refresh-on-catalog-refresh behaviour, phrased like the existing note.
- **Zero SourceCatalog changes.** The per-source TTL cache and best-effort loop
  are already generic by sourceId; a failing or shape-changed source yields no
  collection, never throws, never breaks the union refresh.
- **No persistence, no UI changes.** Live lists are matched against the catalog
  union on the same triggers as today; nothing lands in Room; empty
  collections are dropped by the matcher (ADR-0012/0013 semantics).
- **Recorded rejections.** 4read «Популярне» as a collection (already an Огляд
  section row — duplicate affordance), sluhay.com (challenge wall,
  session-bound), lihtar.in.ua (no top page found), OpenLibrary monthly
  trending (English-centric, low match yield).

## Testing Decisions

- **What makes a good test here:** observable behaviour at the seam — canned
  fixture text served through the existing FakeFetcher at the injected
  endpoint, then assert the returned collection lists (id, name, entries,
  caps, junk dropping, last-dash split); failure and changed-shape cases
  assert an empty result and never-throws. No network, no implementation-
  detail assertions.
- **Single test location:** both new sources, mirroring
  `OpenLibraryTrendingSourceTest` (happy path, fetch failure, changed upstream
  shape, limit cap) — prior art for fixture style and assertions.
- **Header contract pinned:** FakeFetcher records extra headers in call
  order; the sluhayua test asserts the XHR header was actually sent.
- **Integration already covered:** live-matched-like-static, failing-source-
  contributes-nothing and TTL caching are pinned by
  `SmartCollectionsRepositoryTest`; the pure matcher has its own suite. No new
  integration tests.

## Out of Scope

- New screens, components or per-collection affordances (refresh button,
  follow/subscribe, notifications about a changed list).
- Persisting live lists to Room or syncing them across devices.
- Fetching beyond page 1 of either endpoint.
- New static award assets; a 4read «Популярне» collection; sluhay.com and
  lihtar sources; OpenLibrary monthly/hourly variants; API-key providers
  (NYT, Goodreads).
- Any change to matcher normalization, MergeKey rules or merge behaviour.

## Further Notes

- Endpoints verified live 2026-08-22: the sound-books top page renders exactly
  100 unique book tiles server-side (before its comments section); the
  sluhayua downloads-sorted endpoint returns clean Cyrillic `bookName` /
  `bookAuthor` JSON behind the XHR gate — the same gate as the adapter's other
  endpoints, documented since spec-11.
- The lists are provenance-bearing Metadata Assertions about Works: they claim
  membership, the local matcher decides against the union, nothing is stored.
- On landing, CONTEXT.md's «Live collections» paragraph gains the two new
  sources' names — domain doc updated together with code per CONTRIBUTING.
