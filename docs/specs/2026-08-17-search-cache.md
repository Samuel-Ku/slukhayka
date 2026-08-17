# Spec-33: Кеш результатів пошуку — не спамити джерела повторними запитами

> **Status:** ready-for-agent. Синтезовано з автономної сесії `/grill-me`
> 2026-08-17 (Q1–Q8, усі за рекомендаціями). Один новий шов (`SearchCache`).

## Problem Statement

Кожен пошук у каталозі б'є напряму в два джерела, які мають справжній
пошуковий ендпоінт — 4read і sluhayua. Один і той самий популярний запит
(«Шевченко», «Гаррі Поттер») виконується знову і знову кожним користувачем із
нуля, хоча результат за кілька годин не змінюється. Це навантажує сайти джерел
без потреби, і з ростом бази навантаження лише зростатиме.

## Solution

Кешувати в спільному сховищі **мерджений результат пошуку** за нормалізованим
текстом запиту. Перший користувач, який щось шукає, резолвить запит у джерел і
зберігає результат у кеш; наступні користувачі з тим самим запитом отримують
готовий результат без звернення до сайтів. Кеш живе коротко (~24 години), щоб
не застаріти, а негативні (порожні) результати свідомо не кешуються.

## User Stories

1. As a listener, I want a popular search to return instantly from a shared cache, so that I do not wait for the source sites.
2. As a listener, I want the same search I did yesterday to be re-served quickly, so that the app feels responsive.
3. As a listener, I want my search to still hit the source when no one has searched it before, so that new and unique queries keep working.
4. As a listener, I want a cached result to include covers, narrators and durations, so that the cached list looks as rich as a live one.
5. As a listener, I want search results to be fresh enough that a newly added book appears within a day, so that the cache does not hide new releases for long.
6. As the maintainer, I want repeated identical queries deduplicated across users, so that 4read and sluhayua search endpoints are not hammered.
7. As the maintainer, I want the query normalized before it is keyed, so that casing and extra spaces do not split one query into many cache entries.
8. As the maintainer, I want only positive results cached, so that an unbounded long tail of unique miss queries does not bloat the base.
9. As the maintainer, I want cached results written back with provenance and sane limits, so that the shared base stays trustworthy and bounded.
10. As the maintainer, I want writes protected by App Check and the existing Firestore rules, so that the search cache cannot be poisoned.
11. As the maintainer, I want the cache behind a pure-JVM seam with degrade-never semantics, so that a cache failure never breaks search.
12. As the maintainer, I want the cached path to reuse the same merge and duration resolution as the live path, so that both paths return identical shapes.
13. As the maintainer, I want the free Firestore tier to remain the operating boundary, so that search caching stays free forever.

## Implementation Decisions

- **Cache the merged result.** The cached document is the post-merge search
  result list (covers, narrators, durations included), not raw per-source
  matches — it is exactly what the UI renders.
- **Key by normalized query.** The query is trimmed, case-folded and
  whitespace-collapsed before it becomes the cache key, so equivalent queries
  share one entry.
- **Short freshness window.** A cached result is considered fresh for ~24
  hours; after that the query re-resolves from the sources and the entry is
  refreshed.
- **Positive results only.** Empty result sets are never cached, so unique
  long-tail misses stay bounded and still cost one site hit each.
- **Read/write flow.** Search consults the cache first: a fresh hit returns
  without touching the sources; a miss or a stale entry resolves from the
  sources, merges, then writes the result back best-effort.
- **One new seam.** A `SearchCache` interface exposes get/put for a query and
  its merged result, with a pure-JVM codec for the document shape and a
  Firestore implementation as thin glue — mirroring the shared-book-metadata
  seam.
- **Reuses existing seams.** The live path keeps using the existing merge and
  duration resolution; the cached path feeds the same merge output shape.
- **Access hardening and limits.** Writes are App Check-gated (existing rules)
  and carry provenance (`fetchedAt`); a result is bounded to a sane number of
  cards, each requiring a title and at least one source URL.
- **No interplay with profile precedence.** Search is a separate read path; the
  profile read precedence (Override → Room → profile → source) is unchanged.
- **Search-index relationship.** Building a server-side full-text index is
  explicitly out of scope; this spec only deduplicates repeated queries.

## Testing Decisions

- **What makes a good test:** assert observable behaviour — a fresh cached hit
  suppresses the source calls, a stale entry re-resolves, an empty result is
  not written, and the query key normalizes — not the Firestore internals.
- **Result codec (pure JVM).** Encode/decode pinned by fixture tests; prior
  art: the shared-resolution codec tests.
- **Cache seam (pure JVM).** get/put, the ~24-hour freshness window and the
  no-negative-cache rule tested over a fake store; prior art: the universe
  store fixture tests.
- **Search flow (Robolectric).** A fresh cached query does not invoke the
  source adapters; a miss does; a stale entry re-fetches and refreshes.
- **Query normalization (pure JVM).** Case, whitespace and trim equivalence
  tested in isolation.

## Out of Scope

- A server-side full-text search index (Algolia, Typesense, Meilisearch, or a
  Firestore token-array search) — search remains source-fetch or cache-hit.
- Searching the local corpus instead of the sources (a deeper change to search
  semantics, deferred to a later spec).
- Caching negative (empty) result sets.
- Any paid tier or separate search service.

## Further Notes

- Only two sources have real search endpoints (4read and sluhayua); the others
  already filter their cached feed locally, so the cache's main effect is on
  those two endpoints.
- Search is already debounced in the UI; this spec removes the remaining
  duplicate cross-user load, not per-keystroke load.
- The short TTL keeps the cache from hiding a newly added book for more than a
  day.
