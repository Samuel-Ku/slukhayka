# Spec-39: «Ваші цикли» — персональна полиця циклів на Огляді

> **Status:** ready-for-agent. Синтезовано з сесії `/grilling` 2026-08-22
> (Р1–Р10, усі за рекомендаціями). Жодного нового шва окрім одного чистого
> білдера в `ui.library`; блоковий порядок редагується крізь наявний
> snapshot-шов `homeFeedContent` (spec-28 #203).

## Problem Statement

Рядок «Цикли» на Огляді сьогодні — це редакційна секція з головної сторінки
4read.org, однакова для кожного користувача: те, що сайту зручно порекламувати,
а не те, що слухає людина. Полиці, яка повертає слухача в недочитаний цикл
(«Гаррі Поттер — том 4 із 7 лежить недочитаним»), не існує взагалі; єдиний
механізм продовження серії працює лише для однієї книжки (spec-9 T4). При цьому
всі потрібні для персоналізації дані вже лежать локально: членство в серії на
кожному Work (`seriesTitle` / `seriesUrl` / `seriesIndex`), прогрес у
Listening State і семантичні рекомендації на пристрої (spec-19).

## Solution

Одна полиця «Ваші цикли» на Огляді, яка збирається без мережі з локальної бази:
спочатку цикли слухача — книги з Library Entry або будь-яким прогресом
слухання, незавершені зверху, за свіжістю активності; за ними «схожі» цикли —
підйом топ-піків наявного `RecommendationEngine` до рівня циклу, з чіпом
причини «схоже на X». Поки полиця порожня (новий користувач), екран виглядає
точно як сьогодні — 4read-«Цикли» лишаються фолбеком; щойно поліця наповнюється,
вона стає між жанрами та «Рекомендовано для вас», а редакційний рядок 4read
ховається. Картка циклу — у форматі наявної картки серії каталогу, з обкладинкою
представницької книги та чесним «Прослухано X із Y», коли обидва числа реальні.

## User Stories

1. As a listener, I want a «Ваші цикли» shelf on Огляд with the cycles of books I already added or started, so that I can return to my series without digging through Медіатека.
2. As a listener, I want unfinished cycles ranked above finished ones, so that the shelf's main job is continuing where I stopped.
3. As a listener, I want cycles inside each group ordered by how recently I listened, so that what I touched last is one tap away.
4. As a listener, I want a «Прослухано X із Y» count on my cycle card, so that I can see at a glance how much of the cycle is left.
5. As a listener, I want the progress line simply absent while a number is unknown, so that I never see fabricated «0 із 0» placeholders (ADR-0014).
6. As a listener, I want similar cycles suggested after my own, each with a «схоже на X» chip, so that discovery continues beyond my current shelves.
7. As a listener, I want cycles I already own excluded from the similar suggestions, so that I am never offered what is already mine.
8. As a listener, I want two books of one cycle that carry different series links to collapse into one card, so that one cycle is always one card.
9. As a listener, I want tapping a cycle card to open the same series page as every other series entry, so that behaviour is predictable and familiar.
10. As a listener, I want the cycle card's cover to be a real member book's cover, so that cards look like content, not placeholders.
11. As a listener with an empty library, I want Огляд to look exactly as it does today, so that nothing renders as an empty personal shell.
12. As a listener whose imported book has a cycle but no openable series page, I want that cycle silently omitted, so that I never tap into a dead end.
13. As a listener offline, I want my own cycles to render instantly from the local base, so that the shelf works without network.
14. As a listener, I want no spinners or skeleton states on the shelf, so that it appears immediately like the rest of the curated rows.
15. As a listener, I want a finished cycle to stay reachable on the shelf (below unfinished ones), so that re-listening remains discoverable.
16. As the maintainer, I want ranking, deduplication, capping and count honesty in one pure builder with JVM tests, so that regressions surface in `./gradlew test`, not on a phone.
17. As the maintainer, I want zero new seams — the builder composes existing DAO rows and existing `RecommendationEngine` picks, so that no transport, schema or engine code changes.
18. As the maintainer, I want cycle-name normalization to reuse the MergeKey machinery (diacritics-preserving, parenthetical-trimming) behind the collection matcher, so that there is one definition of "same named series".
19. As the maintainer, I want the canonical series URL chosen deterministically (most frequent 4read URL among members, tie broken stably), so that UI and tests are reproducible.
20. As the maintainer, I want a hard cap on the shelf (top 15), so that a huge library cannot balloon the rail.
21. As the maintainer, I want the block-order change pinned by the existing snapshot seam, so that accidental reorderings are caught.
22. As the maintainer, I want the deviation from the spec-28 order recorded as a decision in this spec, so that a UX-rule violation is documented, not silent (spec-27).
23. As the maintainer, I want no Room schema changes, so that migration risk stays zero.
24. As the maintainer, I want the «Серії» index untouched, so that scope stays tight and the catalogue-wide index keeps its own single job.
25. As the maintainer, I want removed (tombstoned) books to drop out of cycle membership naturally through existing reads, so that ADR-0005 semantics hold without extra code.
26. As the maintainer, I want the similar-cycles tier to be best-effort — no engine picks, no tier — so that a recommendation failure degrades to "own cycles only", never a broken shelf.

## Implementation Decisions

- **Gradual replacement (Р1).** While the shelf is empty the screen is byte-for-byte
  today's Огляд (the 4read «Цикли» section row is the fallback). Once personal
  cycles exist, the shelf renders between the genres row and «Рекомендовано для
  вас», and the 4read «Цикли» section row is skipped — using the same typed
  section-id skip as «Новинки» (spec-28 #192/#197). This deliberately amends the
  spec-28 order line; the amendment is THIS decision (spec-27: violations need a
  recorded decision).
- **Own-cycle signal (Р2).** A cycle qualifies when at least one of its Works has a
  Library Entry or any Listening State progress. Cycles where no member carries a
  4read `seriesUrl` are omitted entirely: the series-page path parses 4read pages
  only, so such a card could never open anything honestly.
- **Cycle identity (Р3).** Members are grouped by normalized series title — the same
  MergeKey normalization plus parenthetical-annotation trimming used by the collection
  matcher (ADR-0012), so «Відьмак» and «Відьмак (цикл)» merge. The card opens the
  canonical 4read URL: the most frequent one among members, ties broken by stable
  member order.
- **Similar cycles (Р4).** The existing on-device `RecommendationEngine` output
  (favourite > completed > recent signals, «схоже на X» reasons) is lifted to cycle
  level: each recommended candidate contributes its Work's series identity; results
  are deduplicated by that identity and filtered against the own-cycle set. No new
  similarity logic anywhere.
- **One shelf (Р5).** Single rail titled «Ваші цикли»: own cycles first, similar
  appended after. Similar-tier cards carry the reason chip instead of a progress line;
  visually they are the same card component with the chip as the distinguishing mark.
- **Ranking and cap (Р6).** Unfinished-first (a cycle with at least one unfinished
  member outranks fully-finished ones); within groups, by the most recent Listening
  State activity, falling back to Library Entry creation time. Hard cap: 15 cards total.
- **Card anatomy (Р8).** The existing landscape catalogue series-card form. Cover =
  representative member's cover: the unfinished member with freshest activity, else the
  first member in stable order. Progress line «Прослушано X із Y» rendered only when
  both numbers are real (Y > 0); per ADR-0014 unknown values render as absent.
- **Counting Y (Р9).** Y = distinct Works in the local base sharing the normalized
  cycle identity — the listener's rows plus the synced catalogue union (the shared
  works table, spec-23). No network calls feed the shelf.
- **Architecture (Р10).** One new seam: a pure builder in `ui.library`
  (input: shaped rows + engine picks; output: ready-to-render shelf model), JVM-tested,
  beside the `computeResumeStart` / `OutcomeMessages` prior art (ADR-0008, ADR-0014).
  Screens read modules' flows directly per the module-reads doctrine; no ViewModel
  forwarders. The `homeFeedContent` LazyListScope emitter gains the new block and the
  conditional skip — its signature extension updates the snapshot seam (spec-28 #203).
- **No persistence, no schema.** Nothing about the shelf is stored; every recomposition
  derives it from existing tables. Tombstoned Works are already excluded upstream of
  these reads (ADR-0005).
- **Loading states: none.** Own cycles come synchronously from Room; the similar tier
  attaches whenever the engine's picks are available (best-effort, failure → tier
  absent). The shelf never renders a spinner or an empty-state shell.

## Testing Decisions

- **What makes a good test here:** external behaviour of pure modules only — given
  concrete input rows and engine picks, assert the resulting shelf model: grouping by
  normalized title, unfinished-first ordering with recency tiebreaks, the 15-cap,
  own-exclusion from the similar tier, canonical-URL choice, and absence of progress
  lines when counts are unreal. No internals, no mocks of Room or Compose.
- **The builder (`ui.library`) gets JVM unit tests**, prior art:
  `computeResumeStart` and `OutcomeMessages` tests (ADR-0008/ADR-0014),
  `siblingNarrations` (ADR-0011).
- **Block order is pinned through the existing snapshot seam:** extend the
  `homeFeedContent` snapshot coverage to assert «Ваші цикли» sits between genres and
  «Рекомендовано для вас» and that the 4read «Цикли» row disappears exactly when the
  shelf is non-empty — the same seam that pins today's order (spec-28 #203).
- **Normalization reuse is asserted indirectly** through the builder's grouping tests
  (alias titles merging), matching how the collection matcher is tested (ADR-0012);
  no separate normalization test suite.

## Out of Scope

- Universe-graph similarity (spec-25/26 Wikidata/Firestore knowledge) as a source of
  «схожих» cycles — revisit once resolution coverage grows; the builder's input shape
  leaves room for it.
- Tracking series/cycle page visits as a first-class signal.
- Personalizing the «Серії» index screen ordering (it stays the catalogue-wide index).
- Any network fetch to count cycle members or enrich cards.
- Persisting shelf state, syncing it across devices, or analytics around it.
- Changes to next-in-series continuation (spec-9 T4), `SeriesScreen`, or the series
  page itself.

## Further Notes

- The hidden 4read «Цикли» row is editorial and identical for everyone; once a
  listener has personal cycles it is noise. It remains reachable content through the
  «Серії» index, which aggregates the same catalogue sections — hiding the row removes
  a duplicate surface, not the capability (one tool, one place).
- The shelf is intentionally derived-only: because every input already lives in Room,
  cold start needs no backfill, and uninstall/reinstall behaves identically to the rest
  of the library.
