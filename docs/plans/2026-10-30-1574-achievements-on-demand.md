# План: #1574 — знімок досягнень на вимогу замість вежі combine

Сьогодні `RoomAchievementProgressSource` (після механічного перейменування в
`AchievementSnapshotSource`) фанінить ~25 DAO-флоу через ~15 вкладених
`combine`; кожен запис прогресу (приблизно раз на 5 с прослуховування)
інвалідовує Room, вежа перераховується, `AchievementEngine.start(scope)`
безупинно її колектить і на кожну емісію запускає `evaluate` + `award`, а
`MainActivity` перекомпоновує `achievementsBoard`/`listenerTitle` на кожну
емісію. #1589 вже прибрав найдорожчий повторний матчинг бібліотеки, але
емісійна модель лишилася: робота виконується на кожен запис, а не на
осмислені переходи.

## Цільова форма (два зелені кроки)

### Крок 1 — механічний перенос вежі (без зміни поведінки)

- `RoomAchievementProgressSource` → **`AchievementSnapshotSource`** (файл,
  клас, тести, посилання в `App.kt`/`OwnLibraryFacts.kt` та тестах);
  вміст вежі не змінюється, `observe()` лишається тим самим cold-flow.
- Усі наявні тести (`AchievementSnapshotSourceTest`, `AchievementEngineTest`,
  `SilentBackfillRoomTest`, тематичні award-тести) — зелені.

### Крок 2 — знімок на вимогу (єдина емісійна модель)

1. **Модуль володіє знімком.** `AchievementSnapshotSource` отримує:
   - `val snapshot: StateFlow<AchievementProgress>` (стартує з порожнього);
   - `suspend fun refresh()` — ОДИН cold-прогін вежі (`observe().first()`) і
     публікація результату в `snapshot`; жодної довічної підписки.
2. **Двигун більше не має прихованої підписки.** `AchievementEngine`:
   - `suspend fun refresh(): List<EarnedAchievement>` — `source.refresh()`,
     потім наявна логіка backfill/evaluate над `source.snapshot.value`;
   - прибрати `start(scope)`; `backfilled` лишається (ADR-0063 §7);
   - `evaluate()` стає тонкою обгорткою `refresh()`.
3. **Явні тригери refresh (callers pull explicitly):**
   - **start** — `App.onCreate`: один `achievementScope.launch { achievements.refresh() }`;
   - **progress-write checkpoints** — `AchievementRecorder` після КОЖНОГО
     успішного запису (observation/fact/counter) викликає інжектований
     `onWritten: suspend () -> Unit` = `achievements::refresh`; саме ці записи
     й рухають метрики;
   - **book completion** — той самий шлях: `BOOK_COMPLETED` їде через
     `AchievementRecorder` (уже так) і після запису тригерить refresh;
   - **прямі записи фактів** (`AbandonedBooks.finish` → `achievementStore.recordFact`)
     — після запису явний `achievements.refresh()` у тому ж корутині;
   - **відкриття екрана «Досягнення»** (`openAchievements`) — явний pull, щоб
     зміни поза прогресом (закладки, файли, каталог) не чекали наступного
     прослуховування.
4. **UI не перекомпоновується на кожну DAO-емісію.** `MainViewModel`
   `achievementProgress`/`listenerTitle` мапляться з `snapshot` StateFlow
   (без `flowOn(Default)`-трансформу на кожен тік); `achievementBoard`
   лишається на `earned` store-флоу (він змінюється лише нагородою).
   `achievementDays` (окремий F18-стрічковий флоу) не входить у цей тікет.
5. **Ніякого hot/cold split і жодного throttling** — тільки явний `refresh()`;
   один refresh = один цілісний перерахунок + (максимум) одна оцінка.

## Межі

- форма/вміст `AchievementProgress`, `AchievementEvaluator`, каталог нагород,
  `AchievementStore` не змінюються;
- `AchievementRecorder` отримує один новий callback; порядок/атомарність
  записів FIFO зберігаються (refresh — ПІСЛЯ успішного запису);
- без змін у схемі Room (жодних нових міграцій) — використовуються наявні
  Flow-запити через `first()`.

## Перевірка

- Крок 1: `:app:testRoomRobolectric …AchievementSnapshotSourceTest,
  …SilentBackfillRoomTest` + `:app:testPureJvm …AchievementEngineTest` — зелені.
- Крок 2 (новий boundary-тест): після стартового backfill **одна** емісія
  прогресу НЕ оцінюється сама; один явний `refresh()` → рівно одна оцінка;
  N записів → N refresh (не N×25 DAO-емісій).
- Тест: `MainViewModel.listenerTitle`/`achievementProgress` не змінюються без
  `refresh()` (фейковий snapshot-holder).
- Мутації: (а) `refresh()` не публікує знімок → boundary-тест червоний;
  (б) recorder не викликає `onWritten` → тест «запис прогресу тригерить
  рівно одну оцінку» червоний; (в) повернути довічну підписку в engine →
  boundary-тест «без refresh оцінки немає» червоний.
- Вичерпні гейти — CI (Pure JVM, Room Robolectric/native, Lint, Compose,
  a11y, Kover).
