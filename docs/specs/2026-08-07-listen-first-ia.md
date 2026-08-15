# [Spec] Listen-first IA: «Слухати / Огляд / Медіатека» — 2026-08-07

> **Status:** Approved — decisions locked in a grilling session on 2026-08-07 (Q1–Q6). Not yet implemented.
> **Source:** Grilling session (user's product document «ідеальна програма для аудіокниг» + code-fact checks). 6 resolved decisions.
> **Tracker:** GitHub issues to be filed from this spec (one issue per ticket, T1–Tn, spec-9 label) before implementation starts.

## Problem Statement

The app is built like a Netflix storefront. The Explore tab (the landing tab) is a catalog showcase: horizontal rows, with the «Продовжити слухати» card buried among them and the **entire local library dumped at the bottom of the same screen** (HomeScreen). A user opens an audiobook app far more often to *resume one book they have been listening to for days* than to browse for something new.

The user's product thesis (grilling source): the first screen must be a **listening panel, not a storefront**. Horizontal collections can stay, but they are secondary. Three product situations define the app — *resume quickly*, *find a new book*, *tidy the library* — and the navigation should map 1:1 onto them.

## Solution

A single milestone that restructures the information architecture and builds the new first screen:

- **Three bottom tabs:** **Слухати** (new, first tab, always the landing tab) · **Огляд** (renamed Explore) · **Медіатека** (renamed Бібліотека). The persistent MiniPlayer already sits above the bottom bar — this requirement is already met in code, no work needed.
- **The «Слухати» screen** renders only blocks whose data already exists or can be obtained from 4read:
  1. **Продовжити слухати** — big card: cover, title, author, current chapter, % progress, «Залишилося X год Y хв», big play button, small «to book page» action.
  2. **Нещодавно слухали** — 4–6 books with progress.
  3. **Продовжити серію** — next book of the current book's series (only for 4read books that are in a series).
  4. **Завантажено** — downloaded/imported books.
  5. **Нове на 4read** — one or two rows from the parsed homepage (not ten).
  No mood collections in this milestone (see Out of Scope / Milestone #2).
- **Landing & empty state:** the app always lands on «Слухати». On a fresh install the tab shows a **placeholder «Продовжити слухати» card** («Тут з'явиться ваша поточна книга») plus two CTAs — «Переглянути каталог» and «Імпортувати з пристрою» — so the tab's purpose is visible instead of raw emptiness.
- **Огляд and Медіатека are renames with minimal changes.** The catalog rows, search and genre filters stay in Огляд; the local-library section that currently sits at the bottom of HomeScreen is removed from there (it belongs in Медіатека). No new search/filters/delete-rework in this milestone.

### Milestone #2 (separate, not this spec's scope)

**Smart collections via external enrichment:** collections defined by external data (e.g. «Нобелівські лауреати», bestseller lists, Goodreads tags/moods) matched against the 4read catalog, showing which books are available. Approved as the next milestone; its first step is a **data spike** — verify which external source (Goodreads / Google Books / OpenLibrary / Wikipedia) covers Ukrainian audiobooks and measure the real match rate against 4read before committing to a pipeline.

## User Stories

1. As a listener, I want the app to open on a listening panel, not a storefront, so that I can resume my book in one tap.
2. As a listener, I want to see my current book as a prominent card with remaining time, so that I always know where I am.
3. As a listener, I want the books I recently listened to listed with progress, so that I can switch books easily.
4. As a listener, I want the app to suggest the next book of a series I'm reading, so that I can continue a cycle without searching.
5. As a listener, I want a «Завантажено» block on the first screen, so that I can pick an offline book before a trip.
6. As a listener, I want a small «Нове на 4read» row on the listening screen, so that discovery exists without dominating.
7. As a listener, I want to browse the catalog and manage my library in separate tabs, so that each task has its own place.
8. As a listener, on a fresh install I want to see what this screen is for and clear next actions, so that the app never feels empty or broken.
9. As a listener, I want the next-book suggestion to be computed from 4read's own series data, so that the suggestion is correct and doesn't require manual curation.
10. As a maintainer, I want this milestone to end with green build + tests + device check, so that regressions are caught early.

## Implementation Decisions

### Navigation & tabs

- `SelectedTab` grows `LISTEN`; the enum order defines the bar order: **LISTEN · EXPLORE (renamed label «Огляд») · LIBRARY (renamed label «Медіатека»)**. `NavigationTabsTest` (`bottomBarHasExploreAndLibraryOnly`) is updated accordingly — it currently asserts exactly two tabs.
- Default landing tab becomes `LISTEN` (currently `EXPLORE`).
- The HomeScreen section that renders the **full local library** at the bottom is removed — that list already exists as the Library tab body; Explore/Огляд becomes catalog-only (rows + search + genre filter + «Continue listening» moves out to Слухати).

### The «Слухати» screen

- New `ListenScreen` composable fed by existing ViewModel state: `recentProgress`, `allBooks`, `downloadedBooks`, `catalogSections` — **no new repository queries** except the series-next fetch (below).
- **Продовжити слухати:** reuse/extract the existing `ContinueListeningSection` card (HomeScreen) as the hero block. Remaining time = `totalDurationSeconds − currentPositionSeconds` formatted «Залишилося X год Y хв». The big play button resumes from the saved position; the small action opens the book page.
- **Нещодавно слухали:** first 4–6 entries of `recentProgress` (already sorted by `lastListenedAt`), each with a linear progress indicator.
- **Завантажено:** `downloadedBooks` flow, compact list.
- **Нове на 4read:** the «Новинки» `CatalogSection` (one row), optionally plus «Цикли» — at most two rows total.
- **Empty state (fresh install):** placeholder hero card + two CTAs. No data queries are forced at render time.

### Series data («Продовжити серію») — decided from a site analysis

Facts verified on 4read.org: every book poster carries a series chip `<div class="poster__series anim"><a href="/xfsearch/cikl/<slug>/">Назва</a></div>` **and** a volume badge `<div class="poster__label poster__label--blue">N</div>`; the series page `/xfsearch/cikl/<slug>/` lists all volumes in order; the `/xfsearch/cikl/` listing page shows every series book with its number.

- **Schema:** `AudiobookEntity` gains `seriesTitle: String? = null`, `seriesUrl: String? = null`, `seriesIndex: Int? = null` (Room auto-migration via fallback; schema version bump).
- **Parser:** `CatalogParser` gains a regex for `poster__label--blue` (volume number) and persists series name/URL/number onto `CatalogBook`; the repository upserts them with the book. This is a pure-JVM, fixture-testable change.
- **Next-book mechanics (on-demand, no crawler):** when the «Слухати» screen needs the block, it fetches the series page via the existing series-fetch path (`SeriesScreen`/repository), finds the book whose `seriesIndex = current + 1` (fallback: next item in page order), and shows it as «Продовжити серію: <Title>». The series page is cached in memory per series. If the network fails, the block hides; the screen never blocks or crashes.
- **Local imported books have no series** (`seriesTitle == null`) — excluded from the block.
- Bonus: `seriesIndex` enables a «Частина N» badge on library cards later, with no further work.

### Milestone discipline

- Огляд and Медіатека ship with **no new features** (rename + content re-parenting only). Search, filters, sorting, smart-delete semantics are separate milestones.
- No WebView work: the «open on site» fallback stays as-is.

## Testing Decisions

- **Parser:** new JVM tests for the `poster__label--blue` extraction and series-field propagation on a saved HTML fixture (prior art: `CatalogParserTest`).
- **Repository:** in-memory Room tests (prior art: `AudiobookRepositoryRoomTest`) asserting series fields are upserted with books and survive reload.
- **Next-in-series logic:** pure-function tests — given a series book list and a current book, return the next; no network.
- **Compose:** snapshot tests for the Слухати blocks (prior art: `CatalogRowsSnapshotTest`, `LibraryComponentsSnapshotTest`) — hero card, recent list, series-next, empty state with CTAs. `ListenScreen` itself is exercised without a `MainViewModel` where possible.
- **Navigation:** `NavigationTabsTest` updated to assert three tabs (Слухати · Огляд · Медіатека) and the landing default.
- **No network in tests.** Series-fetch tests use fixtures or in-memory fakes.

## Out of Scope

- **Mood collections («За настроєм»)** — 4read has no such data; external enrichment is Milestone #2.
- **External metadata enrichment** (Goodreads / Google Books / OpenLibrary / Wikipedia pipeline + data spike) — Milestone #2.
- Search, filters, sorting, grid/list toggle in Медіатека.
- Delete semantics rework (remove-from-library vs delete-download vs delete-files).
- Smart rewind after pause, position history, sleep-timer upgrades, per-book speed memory.
- Android Auto / `MediaLibraryService`, Adaptive two-pane layouts, accessibility overhaul — separate milestones.

## Further Notes

- 4read.org facts verified on 2026-08-07: homepage is a 199-page poster feed; `/xfsearch/cikl/` («Усі серії аудіокниг») shows every series book **with its volume number** («Дорога до світанку / Роберт Сальваторе / 10»); posters carry `poster__series` (name+URL) and `poster__label--blue` (volume N). The volume number is therefore obtainable at parse time with zero extra requests.
- The existing spec (2026-08-06) ends fully implemented; this document is the next program phase and may reference it for prior art (SAF import, parser architecture, snapshot infra ADR-001).
- Each iteration ends with: build (`./gradlew assembleDebug testDebugUnitTest`), device check on OnePlus 8 Pro (wireless ADB), and a commit.
