# Спека: прибрати 4read як джерело і наповнити каталог з інших

> Status: Proposed. **Ф2 — Implemented.**
> Рішення: **B** — 4read видаляється як джерело; контент добирається з
> решти джерел. Карта зачеплень і фаз — нижче.

## Problem Statement

Кожен екран («Огляд» union, «Новинки», «Серії», «Колекції», ТОП-100,
«Люди», всесвіти, browser-door, офлайн-рекавері) побудований на контенті
4read — єдиного release-браузерного джерела. Бренд 4read не має з'являтися
в жодному UI. Зачеплено ~65 файлів; найбільші вузли: `SourceCatalog` (58
згадок), `MainViewModel` (34), `OfflineDownloads` (30),
`WebViewHtmlParser` (22), `LibraryImport` (20), `FourReadAdapter` (20).

Рішення: 4read прибирається як джерело повністю; глибину каталогу несуть
інші джерела.

## Solution

**Порядок: спершу бекфіл, потім видалення** — щоб у момент вимкнення 4read
union уже був повний (інакше каталог просяде між фазами).

1. **Бекфіл (Ф2 першою).** Поглибити каталог джерел, які віддають лише
   першу сторінку; підняти корпус union.
2. **Вирізати джерело.** `4read` з `sources.json`/`SourceRegistry`/
   `SourceIds`/`App.sourceAdapters`; видалити `FourReadAdapter` і
   -partи `CatalogParser`/`WebViewHtmlParser`.
3. **Екрани.** Прибрати 4read-хроми: browser-door row, «Відкрити рейтинг у
   4read», «Відкрити цикл у 4read», browser-only snackbar. ТОП-100 →
   власний рейтинг (наявне середнє `Listener Review`) або прибрати;
   «Серії»/«Колекції»/автори вже агрегуються з union.
4. **Плей/офлайн.** Вирізати 4read-рекавері, cookie/referer/heal-політики
   (`OfflineDownloads`, `DownloadPolicy`, `SmartRetryPolicy`); лишити
   direct-джерела + наявний cross-resolve.
5. **Дані й тести.** Наявні 4read-книги лишити; `source`-рядки 4read →
   недоступні, при плей — cross-resolve на direct. Прибрати prefs-ключі й
   cookies; переписати 4read-тести.
6. **Верифікація.** Union непорожній після refresh, пошук працює,
   відтворення вибірки творів, у UI жодної згадки.

## Implementation Decisions

- **Імпортовані 4read-книги:** лишити; `Work`/`Edition` не чіпати, 4read
  `Source` позначається недоступним, на плей — cross-resolve на direct
  (наявний `SourceReplacementMapping`).
- **Двигун Browser Recovery:** лишити дарма (dormant) для майбутніх
  browser-джерел, але без release-дверей 4read. Видалення двигуна — не
  ця спека.
- **Бекфіл:** поглибити каталог там, де є верифікований ендпоінт; де немає
  — зафіксувати виміряну відсутність, не фабрикувати (ADR-0014).

## Risks

- **Глибина каталогу.** 4read був найглибшим (sitemap-краул). Мітигація:
  наявні `fetchCatalog` у `audiobookcoua` (~2192 locs), `sluhay`,
  `knigionline`, `soundbooks`, `audiobookmp3`, `ukrainianaudiobooks`,
  `chytaylo`, `librivox`, `sluhayua` (Ф2) + дефолтний каталог `lihtar`.
- **Плей імпортованих книг** залежить від cross-resolve — покриття треба
  перевірити на вибірці.
- **WebView домен** більше не світиться, бо release-браузер зникає разом
  із 4read (окремий плюс рішення B).

## Ф2 — результат (Implemented)

- **sluhayua:** додано `fetchCatalog`, який пагінує T1-перевірений
  `/find/allcards?sort=time&order=desc&page=N`, обмежений `limit` і власним
  `pageCount` з відповіді; порожня сторінка або остання оголошена кінчає
  прохід (жодного безмежного краулу). Стало: глибина з page 1 → весь
  каталог. Тест: `SluhayuaCatalogTest` (пагінація, ліміт, `pageCount`,
  порожня сторінка, XHR-гейт).
- **lihtar:** уже має каталог через дефолтний
  `SourceAdapter.fetchCatalog = fetchNew` — `fetchNew` перелічує всі
  категорії `/biblioteka`. Змін не потребує.
- **chitaka:** серверного «next» немає — жива сторінка рендерить порожній
  `paginate-links` (фікстура spec-50), пошук robots-заборонений
  (`Disallow: *?*`). Виміряна відсутність; глибина лишається page 1, без
  фабрикації ендпоінта.
- **tg-preview:** submissions-only (ADR-0035), каталогу не має — без змін.
- **T1-спайк не знадобився:** sluhayua використовує вже верифікований
  ендпоінт (spec-11 T2 + #462 ID4).

## Tracks

Тікети (послідовні, з блокуючими ребрами): Ф2 ✅ → Ф1 → Ф3 → Ф4 → Ф5 → Ф6.

## G — результат (Implemented)

- **Обмежено sitemap-обходи** (`KnigiOnlineAdapter.fetchCatalog`,
  `AudiobookCoUaAdapter.fetchCatalog`): прохід спиняється після 10 послідовних
  порожніх/битих сторінок, а не за один gated-запит на кожен `<loc>`.
  Раніше — 401 запит на sitemap із 400 loc-ів (діагноз на пристрої).
  Тест: `CatalogWalkBoundsTest`.
- **Обмежено пошуковий запис** (`SourceCatalog.persistSearchGenreAssertions`):
  не більше 50 жанрових документів на один пошуковий прохід — раніше рядок на
  кожен результат. Наявні тести `SearchGenreAssertionTest` лишаються зеленими.
- **G1 — реалізовано теж:** jitter застосовується лише до допущених запитів
  (`SourceRequestGate.fetchUnderBudget`, після admission); Deferred-запит
  більше не спить і не тримає спільний throat. Тест:
  `SourceRequestGateTest` («a budget-deferred request sleeps no jitter»).
