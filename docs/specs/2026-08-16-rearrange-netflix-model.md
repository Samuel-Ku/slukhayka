# spec-28 — Netflix-модель: перестановка Слухати / Огляд / Медіатека

> Outcome of three `/grill-me` sessions on the content-rearrangement plan (the
> base plan, the IA-restructure doc, and the tab-reordering doc), grounded in the
> actual screen order read from code. Decisions were grilled one at a time to
> shared understanding; overlapping proposals were already consensus across the
> four UX reviews. This spec is the execution contract — the *decision* layer
> lives in ADR-0014 (UX rules), ADR-0015 (eight reorderable blocks), ADR-0017
> (visible deltas over decision docs) and ADR-0018 (component taxonomy).

## Problem Statement

A user opening the app is met with three tabs whose element arrangement feels
unprofessional and illogical, because the screens mix intents and bury their own
content:

- **Слухати** mixes *resume* (eight personalization blocks) with *discovery*
  («Нове на 4read», per-source feeds, «Більше книг на Sluhay»). The eight
  personalization blocks are rendered as vertical full-width cards, turning the
  tab into a near-infinite scroll sheet, and the same `catalogSections` data
  also renders on Огляд — the catalogue lives in three places.
- **Огляд** places an infinite paged feed («Весь каталог») in the *middle*,
  which buries «Рекомендовано для вас», «За тривалістю» and the 4read sections
  below it. An infinite feed has no bottom, so that curated content is dead —
  nobody scrolls past it.
- **Медіатека** stacks six rows of chrome (title + two import buttons, sub-tabs,
  search, seven filter chips, sort + view toggle, a storage row with a
  destructive «Видалити») above the book list. The one thing the user came for —
  the list — starts below the fold, and the destructive delete has no
  confirmation.

## Solution

Reorganize the three tabs into a Netflix-style model where every screen ends
with the infinite feed and puts its real content above it:

- **Слухати** becomes *personal only*: the hero plus eight horizontal shelves
  (one per personalization block), no discovery content. The three discovery
  sections leave: «Нове на 4read» is deleted (its data already lives on Огляд),
  the new-arrival books of every source merge into one cross-source «Новинки»
  rail on Огляд, and «Більше книг на Sluhay» becomes a compact footer CTA.
- **Огляд** becomes *discovery only*: one five-chip navigation row, curated
  shelves first, and «Весь каталог» always last.
- **Медіатека** becomes *manage only*: the book list visible without scrolling,
  a segmented status row + a filter sheet, and every destructive action behind a
  confirmation dialog.

## User Stories

1. As a listener, I want the hero resume card at the top of Слухати to show me
   the current book and remaining time at a glance, so that I can continue in
   one tap without hunting.
2. As a listener, I want each of my eight personalization blocks («Продовжити»,
   «Дослухати до кінця», «Далі у серії», «У дорогу», «Щось коротке», «Ваші
   автори: нове», «Нещодавно додані») as a compact horizontal shelf instead of
   full-width vertical cards, so that I reach the end of the tab in ~2.5 screens
   instead of eight.
3. As a listener, I want a started book in a shelf to show a thin progress bar
   along the cover's bottom edge (and an unstarted book a clean cover), so that
   I can spot unfinished books at a glance like Netflix Continue Watching.
4. As a listener, I want any book to appear in at most ONE shelf on Слухати —
   the highest one on screen — so that I never see the same book styled two or
   three different ways at once, and reordering a shelf re-prioritises where it
   shows.
5. As a listener, I want Слухати to show only my own content (progress, history,
   series, favourites), so that the tab answers one question — «what am I
   listening to?»
6. As a listener, I want the empty-block case handled silently (a personalization
   block with no books renders nothing, not an empty header), so that I don't
   see dead section titles.
7. As a discoverer, I want the catalogue's curated rows (Рекомендовано для вас,
   the cross-source «Новинки» rail, За тривалістю, 4read sections) *above* the
   infinite feed, so that I can actually reach them instead of them being dead
   content below a bottomless list.
8. As a discoverer, I want «Весь каталог» to always be the last element on
   Огляд, so that the endless feed never traps content above it.
9. As a discoverer, I want one navigation row of exactly five chips (ТОП 100,
   Виконавці, Автори, Серії, Колекції), so that I have one obvious place to
   navigate instead of a long "corridor" of stacked nav layers.
10. As a discoverer, I want a dedicated «Серії» screen listing all series as a
    browsable grid, so that I can browse cycles instead of them being a single
    inline row on Огляд.
11. As a discoverer, I want tapping a series to open the existing series page
    (with its books and universe context), so that the new index reuses what
    already works rather than duplicating it.
12. As a discoverer, I want a dedicated «Колекції» screen listing all curated
    collections, so that collections (Нобелівські лауреати, Шевченківська
    премія, Букер) have a real home instead of inline rows.
13. As a discoverer, I want tapping a collection book to resolve and play or
    open it exactly as the inline collection cards do today, so that the move
    changes location, not behaviour.
14. As a discoverer, I want the 4read curated sections to appear exactly once on
    Огляд, so that the same catalogue rows never render twice on one screen.
15. As a discoverer, I want «За тривалістю» presented as human-named thematic
    shelves («Короткі до години», «Епопеї 20+ год») rather than a filter-like
    label, so that it reads as curation, not filtering.
16. As a manager, I want the book list in Медіатека to show at least one book
    without scrolling, so that opening the tab immediately answers «where are my
    books?»
17. As a manager, I want the two import buttons collapsed into one «+ Додати»
    action opening a sheet (files / folder), so that import is one action
    instead of two competing buttons.
18. As a manager, I want a segmented status row (Усі / Нові / Слухаю /
    Завершені / Завантажені) as one-tap filters, so that the statuses I use most
    are one tap away.
19. As a manager, I want a new «Нові» status meaning "not started yet", so that
    I can see books I added but never opened, completing the
    Нові/Слухаю/Завершені trilogy.
20. As a manager, I want search collapsed to a header icon in Медіатека that
    expands to a field on tap, so that the status row isn't crowded and the book
    list appears higher.
21. As a manager, I want the rare filters (Обрані / Локальні / Онлайн) plus
    sorting and the view toggle collapsed into a filter sheet, so that the top
    of the tab is clean while the controls remain reachable.
22. As a manager, I want the storage line and «Видалити завантажені файли»
    moved out of the main screen into a «Завантаження та пам'ять» destination,
    so that a destructive action no longer sits next to neutral storage info.
23. As a manager, I want deleting all downloads to require an explicit
    confirmation that states the count and size being deleted, so that I can't
    accidentally wipe my offline library.
24. As a listener, I want all destructive actions (delete all, clear cache) to
    look destructive (danger colour, confirmation), so that I can tell a
    destructive action from a refresh at a glance.

## Implementation Decisions

- **Слухати keeps all eight blocks.** The personalization blocks are *not* cut
  to six. Each of the eight `ListenComposer` blocks becomes one horizontal shelf
  of `CompactBookCard` posters (one `LazyRow` per block), replacing the
  full-width vertical card. TRAVEL and SHORT stay separate; RECENTLY_ADDED stays.
  This affirms ADR-0015 (reorder/hide is the feature) and only changes the
  *form*, not the count.
- **Discovery leaves Слухати.** «Нове на 4read» is *deleted* (not moved) — the
  `catalogSections` data already renders on Огляд. The new-arrival books of
  every source (4read + others) *merge* into one cross-source «Новинки» rail on
  Огляд, deduplicated by Work, with the source as a `MetadataChip` badge on each
  card. «Більше книг на Sluhay» moves to Огляд as a compact footer/exit CTA, not
  a content shelf.
- **Огляд nav row is five `NavigationChip`s.** [ТОП 100 | Виконавці | Автори |
  Серії | Колекції]. «Серії» and «Колекції» become new pushed screens; the three
  existing targets keep their current `open*` behaviour.
- **«Серії» screen** is a browsable grid/list of `CatalogSeries` aggregated from
  the catalogue sections (currently the «Цикли» section), tapping → the existing
  series page (`openSeries`). No new series data source.
- **«Колекції» screen** lists the matched smart collections (`smartCollections`),
  tapping a book → resolve-and-play, identical to the current inline
  `CollectionBookCard` behaviour.
- **Огляд 4read block is singular.** The existing 4read sections render once, as
  curated shelves above the infinite feed, not twice.
- **Огляд order:** search → 5-chip nav row → genres (one `FilterChip` row, no
  duplicate) → Рекомендовано для вас → «Новинки» (cross-source) → За тривалістю
  (thematic shelves) → 4read sections → Більше книг на Sluhay → «Весь каталог»
  (always last, with its sort/genre controls directly above the grid).
- **Медіатека chrome collapses to three rows.** Title + «+ Додати» + ⋮ (search as
  a header icon that expands to a field on tap); sub-tabs; segmented status row +
  view toggle. The storage line and «Видалити» move into a «Завантаження та
  пам'ять» destination reached from ⋮, with a destructive confirmation dialog
  stating count + size.
- **Медіатека filters split.** Visible segmented row: Усі / Нові / Слухаю /
  Завершені / Завантажені (one tap each). Filter sheet: Обрані / Локальні /
  Онлайн + sort + view toggle.
- **«Нові» is a new status** meaning "never started" (`progress == null`),
  completing the Нові/Слухаю/Завершені trilogy. It is a filter predicate, not a
  schema change.
- **Component discipline (ADR-0018).** Navigation uses `NavigationChip`; status
  filtering uses `FilterChip` (with a selected state); source badges and counts
  use `MetadataChip`. The hero stays `HeroCard`; shelves use `CompactBookCard`
  (started books carry a thin progress hairline on the cover's bottom edge;
  unstarted books keep a clean cover); library/chapter rows use `BookRow`.
  Navigation depth: *push* for Серії and Колекції screens, *sheet* for import
  and filter selection, *dialog* for destructive confirmation.
- **Full cross-shelf dedup (ADR-0014, ADR-0015).** A book renders in at most ONE
  shelf — the highest one on screen — via a `visibleWorkIds` set built in display
  order, so the user's reorder automatically re-prioritises which shelf claims a
  book. The 4read sections render once; the same book never appears in two
  different card styles on one screen.

## Testing Decisions

- **What makes a good test:** assert external behaviour, not implementation.
  For pure data transforms assert inputs → outputs; for the ViewModel assert the
  emitted state after `open*`/`close*`; for screens use snapshot tests at the
  component level rather than brittle markup assertions.
- **Highest seam = the ViewModel state, not the screen.** Each new screen gets
  one read-only `StateFlow` plus an `open*`/`close*` pair, mirroring the existing
  series/top-100/people seam. The screens stay pure composables reading
  `StateFlow`s. This keeps the new-code seam count at two (one per new screen),
  reusing an existing pattern instead of introducing a new navigation library.
- **`«Нові»` filter predicate** is a pure JVM addition to the library filter
  matcher — unit-test it directly, in the style of `MergeKeyTest` /
  `MetadataAssertionsTest`.
- **Series-index aggregation** (sections → flat `CatalogSeries` list) is pure —
  unit-test dedup and empty-section behaviour.
- **Cross-shelf dedup (`visibleWorkIds`)** is a pure transform over the ordered
  block list — unit-test that a Work is claimed by the first (highest) shelf and
  dropped from the rest, and that reorder changes which shelf claims it.
- **ListenComposer already covers the eight blocks** (existing tests). The shelf
  *form* change and the progress hairline are Compose concerns — cover with a
  snapshot test, prior art `LibraryComponentsSnapshotTest`.
- **Snapshot tests** for the two new screens and the segmented filter row, using
  the existing snapshot harness.
- **Existing fakes** (`FakeAudiobookDao` etc.) are reused; no new test doubles
  unless a new seam genuinely needs one.

## Out of Scope

- Time-aware TRAVEL/SHORT merging — moot, since the count stays eight and the two
  blocks remain distinct.
- Any change to the recommendation engine or the on-device semantic row
  (spec-19) — the shelves are repositioned, not re-ranked.
- Changes to the mini-player, full player, or book-detail screens — those are
  covered by the four-review backlog (spec-27), not by the rearrangement.
- The «За тривалістю» thematic re-slicing into new duration buckets beyond the
  existing short/long split — relabelling and repositioning only.
- Series "universe" work (spec-25 / spec-26) — the «Серії» screen only indexes
  what the catalogue already parses.
- Deferred P2 ideas from the grilling: «Популярне зараз», personalized section
  headers («Бо ви слухали…»), time-aware shelf reorder, series collapse into
  `SeriesCard`, per-section minimum visibility thresholds, and the collapsing
  toolbar. Not part of this rearrangement.

## Further Notes

- The *decision* layer for this feature is already recorded: ADR-0015 (eight
  blocks), ADR-0014 (one-tool-one-place, destructive confirmation, honest data),
  ADR-0017 (ship a visible delta, not another doc), ADR-0018 (three cards /
  three chips / navigation depth).
- Per ADR-0017, each ticket under this spec must close with a phone-visible
  delta, not a document.
- Implementation order agreed during grilling: the two new screens (Серії,
  Колекції) first, then the repositioning-only changes, then the Медіатека
  chrome collapse.
- Decisions were gathered across three grilling passes (base plan → IA-restructure
  doc → tab-reordering doc) and folded inline above; no «amended later» footnotes
  remain in the decision section.
