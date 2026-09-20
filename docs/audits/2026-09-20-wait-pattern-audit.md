# Аудит очікувань «поява замість зникнення» — зріз 2026-09-20

Дата: 2026-09-20. Гілка `chore/wait-pattern-audit` від свіжого `main`.
Це **аудит, а не фікс**: жоден тест у цьому зрізі не змінений.

## Клас дефекту

Тест чекає **появу** нового стану, а **зникнення** старого перевіряє
**поза** очікуванням:

```kotlin
waitUntil { …size == 1 }          // чекає лише ПОЯВУ
assertTrue(…isEmpty())            // ЗНИКНЕННЯ — окремим асертом
```

`waitUntil` перевіряє умову **одразу, без синхронізації з idle**, і
повертається, щойно умова істинна. Якщо умова вже істинна в **старому**
поколінні, очікування не чекає нічого, і наступний асерт змагається з
асинхронною зміною. Саме це описано в #915 і #973.

Тому патерн **реальний** (може флейкати) лише за **двох** умов одночасно:

1. асерт зникнення націлений на елемент, який **є** у старому поколінні
   (необхідна умова — саме її доведено в #973);
2. умова `waitUntil` **може бути істинною до** зміни, тобто очікування не
   гейтить перехід (достатня умова).

Без (2) очікування справді блокує до зміни, і асерт безпечний — навіть
коли старий елемент фізично був на екрані до переходу.

## Обсяг і метод

- Пройдено `app/src/test/**` і `app/src/androidTest/**`.
- Знайдено **16** файлів із `waitUntil`-сімейством, **≈100 викликів**:
  76 × `waitUntil(`, 15 × `waitUntilExactlyOneExists(`, 6 × `waitUntilFocused(`
  (ще 1 — оголошення хелпера), 3 × `waitUntilGone(` (ще 1 — оголошення).
  Окремого `waitUntilDoesNotExist` у репозиторії немає.
- Для кожного місця, де за `waitUntil` у межах ~18 рядків іде
  `assertDoesNotExist()` / `assertTrue(…isEmpty())` / `assertFalse(…exists)`
  на **іншому** елементі, прочитано сам тест і **продакшн-код**, який
  керує переходом (композиційний корінь, `selectTab`, семантика обкладинки).
- Збірки не запускалися: це читання коду й документа.

## Основна таблиця

| № | Місце | Що чекає `waitUntil` | Де асерт зникнення | Вердикт | Доказ |
|---|---|---|---|---|---|
| R1 | `AccessibilityComponentsTest.kt:146` | `loaded.get()` — обкладинка завантажилась | `:147-150` `onNodeWithText(book.title)` і `onNodeWithContentDescription(book.title)` → `assertDoesNotExist()` | **БЕЗПЕЧНО** (порожня передумова) | `BookCoverImage.kt:87-90,104-115,142-146`: для `Decorative` `resolvedContentDescription = null`, а fallback-`Box` має `clearAndSetSemantics { }` — жодного тексту/опису в **обох** станах, тобто «старе» не містить елемента від початку. Те саме доводить сусідній `decorativeFallbackCoverIsSilent` (`:91-105`) |
| R2 | `AccessibilityComponentsTest.kt:175` | `loaded.get()` | `:178-179` `onNodeWithText(book.title)` → `assertDoesNotExist()` | **БЕЗПЕЧНО** (порожня передумова) | Те саме: `BookCoverImage` ніколи не рендерить заголовок як семантичний текст |
| R3 | `SettingsGearFromListenRootTest.kt:73` | `awaitTag("listen_screen")` | `:74` `settings_screen` → `assertDoesNotExist()` | **БЕЗПЕЧНО** (атомарний перехід) | `MainActivity.kt:571-573` BACK викликає `selectTab`; `:1213-1321` — один `when (selectedTab)`, гілки LISTEN і SETTINGS взаємовиключні; `TabSaveableHost.kt:20-26` лише `SaveableStateProvider`, без Crossfade. Тригер — `compose.runOnIdle` (`:72`), який доводить рекомпозицію до idle **до** очікування |
| R4 | `SettingsGearFromListenRootTest.kt:111` | `awaitTag(rootTag)` | `:112` `settings_screen` → `assertDoesNotExist()` | **БЕЗПЕЧНО** (атомарний перехід) | Як R3; той самий цикл по чотирьох коренях |
| R5 | `SettingsNavigationTest.kt:87` | `waitFor("settings_screen")` | `:94` `tab_settings` → `assertDoesNotExist()` | **БЕЗПЕЧНО** (порожня передумова) | `grep -rn "tab_settings" app/src --include=*.kt`: у `main/` тега немає, лише `tab_listen/explore/library/friends` (`MainActivity.kt:1469-1511`). Це свідомий регресійний guard #860, а не таймінг |
| R6 | `SettingsNavigationTest.kt:96` | `waitFor(rootTag)` | `:98` `settings_screen` → `assertDoesNotExist()` | **БЕЗПЕЧНО** (атомарний перехід) | Тригер `runOnUiThread { onBackPressedDispatcher.onBackPressed() }` (`:95`). `rootTag` не існує в поколінні Settings (той самий `when`), тож умова **не** може бути істинною до переходу; своп гілок відбувається однією рекомпозицією |
| R7 | `SettingsNavigationTest.kt:140` | `waitFor("library_screen")` | `:141` `library_overflow_button` → `assertDoesNotExist()` | **ІНШЕ** | `grep -rn "library_overflow_button" app/src --include=*.kt` дає єдиний збіг — сам цей тест. Тег не продукує жоден продакшн-файл, тож асерт істинний завжди і не залежить від таймінгу (порожній guard) |
| R8 | `SettingsNavigationTest.kt:143` | `waitFor("settings_screen")` | `:144` `profile_screen_heading` → `assertDoesNotExist()` | **БЕЗПЕЧНО** (порожня передумова) | `MainViewModel.selectTab` (`MainViewModel.kt:3037-3060`) викликає `closeProfileSettings()`; перехід на `tab_library` (`:139`) уже зняв Profile, тому на момент асерта «старе» покоління його не містить |
| R9 | `SettingsNavigationTest.kt:152` | `waitFor("library_screen")` | `:153` `series_index_screen` → `assertDoesNotExist()` | **БЕЗПЕЧНО** (атомарний перехід) | `MainViewModel.kt:3041` `selectTab` викликає `closeSeriesIndex()`; гілка `seriesIndexOpen` (`MainActivity.kt:990`) стоїть **перед** `else -> TabSaveableHost`, тому після зміни таба index-гілка й коренева гілка взаємовиключні |
| R10 | `SettingsNavigationTest.kt:155` | `waitFor("settings_screen")` | `:156` `series_index_screen` → `assertDoesNotExist()` | **БЕЗПЕЧНО** (порожня передумова) | Index уже зник і перевірений рядком `:153`; між ними лише клік шестерні. Асерт тривіальний |
| R11 | `LiveBookDetailNavigationTest.kt:67` | `selectedSeries.value != null` / `selectedPerson.value != null` | `:71` `book_detail_title` → `assertDoesNotExist()` | **БЕЗПЕЧНО** (стан-перехід + явний idle) | `MainViewModel.openPersonBooks` (`:2987-2999`) ставить `_selectedPerson` **синхронно**; гілка `bookDetailChildRouteOpen && … && selectedPerson != null` (`MainActivity.kt:934`) стоїть перед `selectedBookId != null -> bookDetailContent()` (`:1211`). Умова очікування хибна до кліку (напр. попередня ітерація завершується `waitUntil { selectedSeries == null && selectedPerson == null }`), а `rule.waitForIdle()` (`:70`) доводить рекомпозицію до idle перед асертом |
| R12 | `WorkFeedFilterWiringTest.kt:411` | `"Дюна" …size == 1` | `:417` `"Відьмак" …isEmpty()` | **БЕЗПЕЧНО** (порожня передумова) | Попередній `waitUntil` (`:407-409`) доводить `refresh !is Loading && feed.itemCount == 0` **до** `syncSharedFacets` (`:410`); решта секцій `homeFeedContent` — `emptyList()`, а `Відьмак` не має фасета `science-fiction`. Старе покоління порожнє, «Відьмак» не рендерився жодного разу |

Підсумок: **РЕАЛЬНИХ — 0.** БЕЗПЕЧНИХ — 11, з них 6 доведено порожньою
передумовою (R1, R2, R5, R8, R10, R12) і 5 — атомарністю переходу
(R3, R4, R6, R9, R11). ІНШЕ — 1 (R7, порожній guard).

## Чому «реальних» нуль

Необхідна умова (старе покоління містить елемент) виконується лише в
R3/R4/R6/R9/R11. Але в усіх п'яти **достатня умова не виконується**:

- R3/R4/R6/R9 — умова очікування це тег **нового** екрана. У
  композиційному корені це один `when (selectedTab)` і один ланцюг
  `if/else` (`MainActivity.kt:934-1322`), тому новий тег не існує в
  старому поколінні: `waitUntil` справді блокує до свопу, а своп гілок
  прибирає старий вузол тією ж рекомпозицією. `Crossfade`/`AnimatedContent`
  навколо ланцюга немає — анімація є лише в повноекранного плеєра
  (`MainActivity.kt:1329`).
- R11 — умова це **стан маршруту**, який до кліку `null`, а сам клік
  ставить його синхронно; плюс явний `waitForIdle()` перед асертом.

Тобто це не «поява в старому поколінні», а справжнє очікування переходу.
За формулюванням класу вони **не** є екземплярами #973.

## Уже правильною формою (зникнення всередині очікування)

Ці місця — доказ, що клас у репозиторії вже закритий; саме так виглядає
фікс:

- `WorkFeedFilterWiringTest.kt:210-214` — умова містить і появу «Чотири 0»,
  і `"Двічі 0" …isEmpty()` (фікс #973); асерти `:215-216` лишилися.
- `WorkFeedFilterWiringTest.kt:326-330` — те саме під час фонового запису.
- `WorkFeedFilterWiringTest.kt:539-545` і `:590-606` — умова містить
  `pride == 0` / `kobzar == 0` (фікс #915); асерти `:549-551`, `:614`.
- `WorkFeedFilterWiringTest.kt:407-409` — попередній `waitUntil` фіксує
  порожній фід (`itemCount == 0`) як передумову для `landing_delta`.
- `LiveChapterFocusTest.kt:53-55` — `waitUntil { onAllNodesWithTag("full_player_screen").fetchSemanticsNodes().isEmpty() }`.
- `MainActivityAccessibilityTest.kt:264-269` — чекає, доки зникне
  `first_language_choice`; `:510-514` — доки зникне `full_player_screen`;
  хелпер `waitUntilGone` (`:603-608`) використано на `:427`, `:457`, `:492`.
  Коментар на `:262-263` прямо фіксує, що попередня форма «асерт зникнення
  поза очікуванням» флейкала на CI.

## Суміжні випадки (без фіксів)

Це не той самий патерн, але та сама хвороба: «зникнення поза очікуванням»
або «очікування стану замість події».

1. **Зникнення одразу після кліку, без жодного очікування.**
   `BookFeedbackUiTest.kt:77-78` — після кліку `feedback_dismiss` асерт
   `book_feedback_form.assertDoesNotExist()` стоїть без `waitUntil`; рятує
   лише те, що `performTouchInput`/`performClick` синхронізує idle.
   `LiveBookDetailActionsTest.kt:62-64` — `runOnUiThread { selectBook }`,
   далі `mini_player_bar.assertDoesNotExist()`; синхронізує сусідній
   `book_detail_title.assertExists()`.
2. **Умова очікування, яку може задовольнити попереднє покоління.**
   `BookDetailCoverLayoutTest.kt:70-76` — `waitUntil` чекає кольору центру
   обкладинки; автор свідомо зробив центри різними («Distinct centers
   prevent the previous image satisfying the next load»), а не чекав
   зникнення попереднього зображення. Той самий клас думки, інше лікування.
3. **`waitForIdle()` + асерт зникнення** (не гонка, бо рендер статичний і
   елемент відсутній від початку): `SearchResultsContentTest.kt:119-126,
   141-144`, `SourceAudioRefusalScreenTest.kt:99-103, 153-157`,
   `LibraryFilterSheetAccessibilityTest.kt:51-71`, `HomeWebDoorsTest.kt:49-55,
   61-64`, `FeedSearchCanonicalRowTest.kt:194-204, 323-334`,
   `HomeFeedPhoneFoldSnapshotTest.kt:185-193, 209-216`,
   `BookDetailPeopleLayoutTest.kt:67-80`, `UiSurfaceAuditTest.kt:175-186`.
   `waitForIdle` не чекає асинхронної роботи, тож якщо тут колись з'явиться
   фонова операція, це стане тим самим дефектом.
4. **Очікування стану замість завершення операції.** Найяскравіше —
   передфіксові умови `WorkFeedFilterWiringTest` (`refresh !is Loading`
   істинне за мить до перезапуску Pager). Живі тести далі тримаються на
   стані плеєра: `LiveCandidatePlaybackTest.kt:47-49, 51, 58, 64, 68-70`,
   `LiveBookDetailActionsTest.kt:46, 48, 51, 53`,
   `AudioPlaybackEspressoTest.kt:286-288, 311-313`,
   `MainActivityAccessibilityTest.kt:354-358, 369-371, 386-388, 419-421,
   452-456`. Безпечні лише тому, що стан — новий (хибний до дії), а не
   тому, що очікування гейтить подію.

## Що це означає для CI

- **Цей клас зараз не дає флейків: 0 реальних місць.** `#923 → #915 → #973`
  закрили клас, і сусідній `landing_delta` справді безпечний — не «поки
  зелено», а тому що фід стартує порожнім.
- **Ризик латентний, а не нульовий.** R3/R4/R6/R9/R11 безпечні лише тому,
  що перехід екрана — одна рекомпозиція `when`-гілки. Якщо колись з'явиться
  анімація переходу (`Crossfade`/`AnimatedContent`), панель «поверх» кореня
  (як `WideDetailPane`) або проміжний стан між появою нового й зникненням
  старого — вони стануть **РЕАЛЬНИМИ** без жодного нового асерта.
- Тому природний guard на майбутнє — не чіпати 11 асертів, а тримати
  інваріант: «зникнення, яке тест збирається перевірити, мусить бути в ТІЙ
  САМІЙ умові очікування» — рівно як у `waitUntilGone` і як зробили #915/#973.
- R7 і R5 — окремо: `library_overflow_button` та `tab_settings` не існують
  у продакшн-коді. R5 — свідомий регресійний guard (#860), R7 — порожній
  асерт, який не може впасти ніколи. Це не флейк, але й не покриття.

## Що свідомо не зробив

- **Не змінив жодного тесту** — це зріз-аудит; фікси будуть окремими
  зрізами з доказами.
- **Не запускав Gradle і пристрій** — зріз читальний за постановкою.
  Вердикти R11 та R3/R4/R6/R9 спираються на читання продакшн-коду й на
  зафіксовану в #973 поведінку `waitUntil`, а не на прогін.
- **Не розширював обсяг** на тести без `waitUntil` (їхній `waitForIdle` +
  зникнення винесено в «суміжні», без вердиктів «реальний/безпечний»).
- **Поза обсягом, але варте тріажу:** `SearchReturnNavigationTest.kt:48`
  клікає `onNodeWithTag("tab_settings")`, хоча тега немає в `main/` з #860
  (`grep` підтверджує). Це не клас очікувань і не перевірено прогоном
  (збірки не запускалися), але схоже на застарілий тег, а не на гонку.
