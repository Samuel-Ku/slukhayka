# План: #1650 — ADR-0008 remember для Room-флоу плеєра + пін steady-state

Дві не-блокуючі знахідки огляду PR #1649 (#1647), винесені окремо.

## 1. ADR-0008: function-built Flow мусить бути remembered

`PlayerScreen` (після #1647) будував `libraryEntries.observeBook(openedBook.id)`
і `observeChapters(...)` **inline**. Кожна рекомпозиція (плеєр тікає позицію
~1 Гц під час гри) створює новий `Flow`, і `collectAsState` перезапускає
обидві Room-підписки щосекунди. ADR-0008 вимагає `remember` для таких flow
(прецедент — `BookDetailScreen`).

Фікс — `remember(openedBook.id) { … }` навколо обох flow; initial-значення,
семантика «null/empty = сирий знімок» і ключ book.id не змінені.

## 2. Тест-щілина: steady state overlay-у

`PlayerDurationFactsTest` пінив «без змін → той самий інстанс» лише для
ПОРОЖНІХ live-рядків. Реалістичний steady state — live-рядки повторюють
знімок із тими самими >0 тривалостями; мутація «прибрати guard
`live.durationSeconds != chapter.durationSeconds`» **survived** огляд
(`20261030T151819Z-24627`, 6/6 green).

Додано тест: `assertSame` і для стану з новим `total` книги (live book ≠
snapshot), і для сирого стану з повтореними рядами — жодного нового
`PlayerState` не алокується.

## Межі

- Змінюється лише спосіб побудови двох flow + один тест; поведінка UI/Room-
  запитів/пробника не змінюється.
- `remember` привʼязаний до `openedBook.id`: зміна книги перезапускає
  підписки, як і має бути; жодного крос-книжкового забруднення.

## Перевірка

- `PlayerDurationFactsTest` — 7/7 (новий steady-state тест).
- Мутація: прибрати guard рівності → новий тест падає (авторський прогін
  нижче; оглядацький run-id для перевірки: `20261030T151819Z-24627` мав
  survive ДО цього тесту).
- CI: Compose+Roborazzi (регресія рендера), Room Robolectric-only
  (AudioPlayerManager/playback).
