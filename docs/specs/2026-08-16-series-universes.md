# [Spec-25] Всесвіти серій: зв'язки між циклами та місце книги в них

> **Status:** Draft — synthesized from the 2026-08-16 grilling; decisions locked with the user.
> **Tracker:** filed as issue (labels `spec-25`, `ready-for-agent`).

## Problem Statement

The app knows that a book belongs to a series (source-provided series title + volume index) but nothing about the series' context: a listener browsing «Трохи ненависті» cannot tell that it opens «Епоху божевілля», that «Епоха божевілля» continues «Перший закон», and that both belong to the same universe. The source (4read) provides no inter-series relations at all.

## Solution

1. **A curated JSON asset defines universes** — each universe groups its series in order (with the source's series-URL keys and normalized title aliases); the order yields «Передує/Продовжує». Seeded with 3–5 well-known universes (Перший закон/Аберкромбі, Відьмак, Дріззт, Гіперіон). Adding a universe is a data change.
2. **Lazy resolution on book open**: when an opened book carries a series, the app resolves its universe in the background, best-effort, and caches the result in the (currently empty) `series` / `series_members` tables plus a new `universes` table — no re-resolution afterwards.
3. **A Wikidata provider behind the same seam** (later ticket): for series the JSON does not know, the app resolves through Wikidata — search in uk → ru → en with author verification, then `P179` (series) and `P155`/`P156` (follows/followed-by) chains; failures degrade silently.
4. **Clear surfacing**: the series screen header shows the universe, the series' position in it and tappable «Передує: …» / «Продовжує: …» chips; the book page shows a «Всесвіт: …» line under the series pill.

## User Stories

1. As a listener opening a book, I want to see which universe its series belongs to, so that I understand the bigger picture without research.
2. As a listener on the series page, I want the universe name and the series' place in it, so that I know whether it is the start, the middle or the end of a world.
3. As a listener, I want tappable «Передує/Продовжує» chips, so that I can jump between related series in reading order.
4. As a listener, I want all of this offline-capable for the seeded universes, so that resolution never depends on the network.
5. As a listener with a book from an unseeded series, I want the Wikidata provider to resolve it when possible, so that coverage grows beyond the curated set.
6. As a listener, I want a failed resolution to stay silent, so that a missing universe never degrades the book page.
7. As a maintainer, I want the matching pinned by pure JVM tests and the persistence by Room tests, so that the mechanism stays honest.

## Implementation Decisions

- **Domain**: a new `Universe` term joins the ubiquitous language (CONTEXT.md): a named world/cycle containing ordered Series; the order yields the precedes/follows relations.
- **Schema**: a `universes` table plus a `universeId` column on the existing (empty) `series` table; `series_members` is populated at resolution (book → series + position from the source's volume index). Migration bump.
- **Curated asset**: one JSON file per universe (or an index + files, mirroring the collections layout); each series entry keys on the source's series URL (primary, e.g. the `xfsearch/cikl/…` link) with normalized title aliases as the fallback key.
- **Matching**: reuse the collections normalizer (case-fold, punctuation strip, diacritics NFKD Cyrillic-preserving, trailing annotation trim); URL match wins over title match.
- **Resolution trigger**: book page open, background, best-effort; the cache in `series`/`series_members`/`universes` makes re-resolution unnecessary.
- **Wikidata provider (second ticket)**: same seam; `wbsearchentities` in uk → ru → en with author verification via `P50`; then `P179` → series item, `P155`/`P156` → chain; one resolution per book, cached; any failure contributes nothing.
- **UI**: series screen header gains the universe block; the book page gains a universe line under the series pill.

## Testing Decisions

- **What makes a good test:** external behaviour — a book with a known series URL resolves its universe and position; an unknown series falls back to title matching; a missing universe is silent; the series screen renders the universe header and chips; the Wikidata mapping (given canned API fixtures) extracts the chain.
- **Modules tested:** the pure universe matcher (JVM, prior art CollectionMatcherTest), resolution persistence (Room, prior art DAO/module tests), the Wikidata provider against fixture JSON (prior art adapter-fixture tests), snapshot tests for the series header and the book-page universe line.
- **Prior art:** collections assets + matcher (Spec-16), `LiveCollectionSource` (the provider seam), Robolectric snapshot seams, the CI gate (assembleDebug + testDebugUnitTest + Kover).

## Out of Scope

- Editing universes in-app (data changes ship as assets).
- Re-ordering or merging series automatically from source data.
- Author pages or author-level universe listings beyond the series surfaces.
- Android Auto / widgets surfacing universe data.

## Further Notes

- The empty `series`/`series_members` tables were created by an earlier migration and are the intended cache.
- The Abercrombie case is the acceptance anchor: «Трохи ненависті» → «Епоха божевілля» (книга 1) → universe «Перший закон», «Епоха божевілля» follows «Перший закон».
