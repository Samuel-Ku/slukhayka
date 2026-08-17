# Spec-34: Повна довжина книги — сторінкова довжина перемагає суму розділів

> **Status:** ready-for-agent. Root cause підтверджено з коду, фікс і тест уже
> написані. Без нових швів — правка на наявному write-path.

## Problem Statement

У плеєрі для деяких книг не показується повна довжина: джерела, які дають
довжину книги на сторінці («Триває:» / `itemprop="duration"` / «Тривалість»),
але не заповнюють тривалість окремих розділів, втрачали її при імпорті — і
картка/плеєр показували «невідомо», доки фоновий енрічмент не дофітчував
значення.

## Solution

При імпорті повної довжини використовувати **спочатку сторінкову довжину**,
а суму тривалостей розділів — лише як запасний варіант, коли сторінка довжини
не дає.

## User Stories

1. As a listener, I want a book whose page states its total length to show that length in the player immediately, so that I see the real duration without waiting.
2. As a listener, I want the same correct total on the player and on the library card, so that the two surfaces agree.
3. As a listener, I want a book whose page gives no total but whose chapters carry durations to still show their summed length, so that nothing regresses.
4. As a listener, I want a book with no known duration to keep showing "unknown" rather than a fabricated zero, so that honest data holds.
5. As the maintainer, I want the import duration rule to prefer the source's advertised total, so that it agrees with the duration-enrichment and hydration paths that already use it.

## Implementation Decisions

- **Precedence: page total → chapter sum → unknown.** The imported total
  duration is the page-reported total when real; otherwise the chapter-duration
  sum; otherwise unknown (0). The existing unknown-duration normalization still
  drops blank/negative/legacy-sentinel values.
- **Single write-path change.** Only the source-page import computes the stored
  total; no schema change, no new module.
- **Consistency.** The rule now matches the duration-enrichment and hydration
  paths, which already write the page-reported total.

## Testing Decisions

- **What makes a good test:** assert the observable stored value — importing a
  detail whose page total is real but whose chapters have no durations stores
  the page total — not the internal arithmetic.
- **Write path (Robolectric).** A source-page import with a page total and
  empty chapter durations keeps the page total; prior art: the existing
  write-path Robolectric tests.
- **Unknown stays unknown.** A detail with no total and no chapter durations
  stores unknown (0), unchanged.

## Out of Scope

- Per-chapter duration probing or enrichment.
- Changes to how duration is displayed or formatted.
- Backfilling already-imported books with wrong totals (the startup enrichment
  already re-derives them).

## Further Notes

- The fix is already implemented and covered by a failing-then-green test in
  the write-path suite.
