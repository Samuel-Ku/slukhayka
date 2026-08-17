# Spec-35: Правдива повнота профілю джерела — усі адаптери до золотого стандарту 4read

> **Status:** ready-for-agent. Синтезовано з сесії `/grill-with-docs`
> 2026-08-17 (Q1–Q8, усі за рекомендаціями). Без нових швів: правки на наявному
> `SourceAdapter` seam і дубльованих pure-помічниках.

## Problem Statement

Шов `SourceAdapter` уже моделює повний профіль книги: `SourceBookDetail`
(11 полів — title, author, narrator, cover, chapters, totalDuration, rating,
genres, series, related, description) і картковий `SourceBook` (seriesTitle,
seriesIndex, genre, narrator, totalDurationSeconds). Контракт шва прямо каже:
«поля, яких сторінка не несе, відсутні — ніколи не фабриковані», і це правило
закріплене в ADR-0014 «Only truthful data».

Повністю й правдиво цей профіль заповнює лише 4read. Решта п'ять адаптерів
пропускають значення, які їхні власні сторінки **реально несуть**:

- `audiobookmp3` і `lihtar` не витягують обкладинку книжкової сторінки;
- `audiobookmp3`, `lihtar`, `soundbooks`, `sluhayua` не витягують повну
  довжину книги (spec-34 уже вміє її зберігати — але не отримує з цих джерел);
- `lihtar` бере author з `og:description`, обрізаного до 80 символів — це
  вада, а не «відсутнє»;
- карткові seriesTitle/seriesIndex/genre/narrator/duration не заповнюються в
  лістингах `soundbooks`/`audiobookmp3`/`lihtar`/`sluhayua` навіть там,
  де розмітка їх дає.

Водночас частина порожніх клітин — законна: пошук заборонений robots.txt,
sluhay сидить за Cloudflare, у sluhay виміряно немає диктора. Тому «золотий
стандарт» означає не «заповнити все», а «заповнити кожне поле, яке джерело
справді віддає, і зафіксувати решту як негативну знахідку».

## Solution

Довести п'ять не-4read адаптерів (`sluhayua`, `soundbooks`,
`audiobookmp3`, `lihtar`, `sluhay`) до того самого бар'єра правдивої
повноти, який задає 4read. Спершу — свіжий по-полевий інвентар кожного джерела
(обидві поверхні: лістинг для карткових полів і книжкова сторінка для
сторінкових), задокументований як research-нотатка. Далі реалізуємо лише
позитивні знахідки; негативні фіксуємо KDoc-коментарем у коді адаптера (як уже
зроблено для sluhay «немає диктора»). Парсинг сторінки кожного адаптера стає
чистою внутрішньою функцією (HTML → detail) — фікстурним швом, — а дубльовані
pure-помічники (`ogMeta`, `decodeEntities`, `slugTitle`,
`parseDurationSeconds`) виносяться в один спільний файл.

## User Stories

1. As a listener, I want a book's cover shown from the book page of every source that has one, so that audiobookmp3 and lihtar cards look like 4read cards.
2. As a listener, I want the book's total duration shown as soon as the source page states it, so that soundbooks/audiobookmp3/lihtar/sluhayua books do not say «невідомо».
3. As a listener, I want the real narrator shown wherever the source names one, so that I can tell renditions apart without opening 4read.
4. As a listener, I want the description to be the book's own blurb, so that lihtar (where og:description is the author) never shows the author as a description.
5. As a listener, I want the real author on lihtar, not a truncated og:description, so that the Work merges correctly and the name is complete.
6. As a listener, I want card-level series/genre/narrator/duration filled wherever a listing provides them, so that discovery surfaces are as rich as 4read's.
7. As a listener, I want a source that genuinely lacks a field to stay empty (never a fake number/cover/name), so that honest data holds everywhere.
8. As the maintainer, I want one shared pure helper file for ogMeta/decodeEntities/slugTitle/duration parsing, so that the duplicated parsers cannot drift.
9. As the maintainer, I want each adapter's page parse to be a pure function with a fixture seam, so that every new field is JVM-testable.
10. As the maintainer, I want a per-field fixture test for every positive finding and a negative test for every negative finding, so that «absent stays absent» is enforced, not assumed.

## Implementation Decisions

- **Бар'єр — правдива повнота, не «заповнити все».** Кожен адаптер витягує
  кожне поле, яке джерело реально віддає; відсутнє лишається відсутнім і
  фіксується як негативна знахідка (ADR-0014, контракт у `SourceAdapter.kt`).
- **Поля в обсязі.** Повний `SourceBookDetail` (11 полів) + карткові
  `SourceBook`-поля (seriesTitle, seriesIndex, genre, narrator,
  totalDurationSeconds).
- **Джерело істини — свіжий інвентар.** Живий fetch кожної не-4read поверхні
  (лістинг + книжкова сторінка) з по-полевим списком «є / немає»; результат —
  одна research-нотатка в `docs/wayfinder/research/`.
- **Реалізуємо лише позитивні знахідки.** Нічого не синтезуємо; негативні
  знахідки документуємо KDoc-коментарем в адаптері.
- **Обсяг адаптерів.** `sluhayua`, `soundbooks`, `audiobookmp3`,
  `lihtar`, `sluhay`. 4read не чіпаємо — він еталон.
- **Архітектура — мінімальна гігієна.** Дубльовані pure-помічники
  (`ogMeta`, `decodeEntities`, `slugTitle`, `parseDurationSeconds`)
  виносяться в один спільний файл у `data/source/`; парсинг сторінки кожного
  адаптера стає чистою внутрішньою функцією (HTML → detail). Без повної
  переробки у стилі `WebViewHtmlParser`.
- **Порядок за щільністю дефектів.** `lihtar` → `audiobookmp3` →
  `soundbooks` → `sluhayua` → `sluhay`.
- **lihtar — виправлення, не лише доповнення.** Обкладинка (уже добута в
  `fetchNew` через `pageMeta`, але ігнорована в `fetchBookPage`) і автор
  (зараз `og:description`, обрізаний до 80 символів) входять у цю спеку як
  виправлення.

## Testing Decisions

- **What makes a good test:** assert the observable output — HTML-фікстура →
  значення поля — не внутрішню структуру regex.
- **По-полеві фікстурні тести (JVM).** Кожне нове витягування закріплене
  фікстурою (HTML → значення); prior art: `FourReadAdapterTest`,
  `SluhayAdapterTest`, `SoundBooksAdapterTest` тощо.
- **Негативні тести.** Фікстура без поля → поле порожнє, закріплює «absent
  stays absent» (напр. sluhay-фікстура без диктора → narrator порожній).
- **Спільні pure-помічники (JVM).** Табличні тести `ogMeta` /
  `decodeEntities` / `slugTitle` / `parseDurationSeconds`; prior art:
  `MetadataAssertionsTest`.
- **Рефакторинг без регресії.** Після виносу помічників і чистого шва наявні
  адаптерні тести лишаються зеленими без зміни поведінки.

## Out of Scope

- Можливості джерел, що не є профілем: `search`, `fetchCatalog`,
  `bookId`, `sessionBound` (крім випадкового дотику, потрібного для
  повноти поля).
- Синтез відсутніх полів (фабриковані обкладинки/довжини/диктори) — заборонено
  правилом чесних даних.
- Повна переробка парсерів у окремі класи у стилі `WebViewHtmlParser` для
  кожного джерела.
- Нові ADR або правки `CONTEXT.md` — правило «відсутнє — відсутнє» уже
  зафіксоване; ця спека лише доводить адаптери до нього.
- Зміни в 4read (він — еталон) і в гідрації/енрічменті тривалостей, які вже
  споживають профіль.

## Further Notes

- Карткові поля (seriesTitle/seriesIndex/genre/narrator/duration) живуть у
  **лістингах** (homepage/категорії), сторінкові — у **книжковій сторінці**;
  тому інвентар знімає обидві поверхні, а не лише сторінку книги.
- Рекомендоване ім'я спільного файлу помічників — `data/source/SourceParsing.kt`
  (чистий JVM, без мережі); ім'я узгоджується під час реалізації.
- Негативні знахідки вже мають прецедент: KDoc у `SluhayAdapter` («measured
  negative finding — no narrator anywhere»). Ця спека поширює той самий прийом
  на всі п'ять адаптерів.
- spec-34 (сторінкова довжина перемагає суму розділів) уже вміє зберігати
  повну довжину; ця спека лише доносить її з джерел, які її віддають.
