# План: #1627 — таб не еліпситься на 411 dp (F30b)

QA на OnePlus 8 Pro (411 dp, fontScale 1.15): у inline-смузі F30b
(три рівні таби + 140 dp пігулка пошуку в одному рядку) третій підпис
рендерився як «Збереж…». Причина арифметична: рівні таби ділять
`411 − 32 (inset) − 4 (спейсер) − 140 (пошук) = 235 dp` → ~78.3 dp на таб,
а «Збережене» на fontScale 1.15 потребує ~90 dp у відвантаженій
PaperInk-типографіці.

## Рішення

1. **Adaptive fit-check у `LibraryTabsWithSearch`:** `TextMeasurer` міряє
   **найширший із трьох** підписів (`lib_section_books/shelves/saved`) у тому
   самому стилі, що рендерять таби (`PaperInkType.Tab` / `titleSmall`), і при
   поточному fontScale. Inline обирається лише якщо
   `3 × widest ≤ maxWidth − 32 − 4 − 140` і `maxWidth ≥ 400 dp`.
   Міряти лише «Збережене» недостатньо: в en найширший — «Shelves».
2. **Коли не вміщається — наявний дво-смуговий fallback:** пошук отримує
   власний рядок, таби — повну ширину; на дуже великих fontScale підпис може
   переноситися на другий рядок (`tabLabelMaxLines = 2` у fallback), але не
   ріжеться. Жодна дія не ховається (ADR-0033).
3. **Дизайн F30b на стандартному fontScale не змінений:** при 1.0 на 411 dp
   вимір дає inline, падінги/стилі/лейбли/пігулка — як було; еталони
   байт-у-байт (перевірено Roborazzi-record: `git status` порожній).
   QA-варіанти «зменшити падінги/скоротити підпис» відкинуто — вони міняли б
   вигляд і 3 еталони заради <1 dp.

## Межі

- змінюється лише вибір layout у `LibraryTabsWithSearch` (+ `tabLabelMaxLines`
  у fallback); самі таби/пошук/падінги/ресурси — як у F30b;
- fallback вже існував для <400 dp; нове — коли він обирається і що підписи
  можуть переноситися замість обрізання на екстремальних масштабах.

## Перевірка

- `LibraryTabsFitTest` (uk) + `LibraryTabsFitEnTest` (en): у
  **відвантаженій** `PaperInkThemedContent`-темі на 411 dp, fontScale
  1.0/1.15/1.5/2.0 — жоден підпис не `isLineEllipsized(0)`; на 1.0 смуга
  лишається inline (таб ≈78 dp), uk/1.15 — fallback (таби повної ширини).
- `LibraryChromeHeightTest` (411, fontScale 1.0) — inline ≤240 dp;
  `LibraryAccessibilityTest` — зелені; Roborazzi-record трьох
  Медіатека-наборів — еталони без змін.
- Мутація: `inline = maxWidth >= 400.dp` (ігнорувати вимір) → тести 1.15+
  червоніють (killed `20261030T041703Z-353857`, 5 tests / 4 failures).
