# План: #1741 — App Check з main на IO, без втрати порядку «App Check до Firestore»

Profiler холодного старту: main Running 87% (1175/1354 мс), два виклики
Firebase/App Check на main у перші ~110 мс. Джерело — синхронний
`installAppCheckIfConfigured()` у `App.onCreate`: `FirebaseApp.getApps` /
`initializeApp` тягне ініціалізацію Firebase на головному потоці ще до першого
кадру.

## Що міняю

1. **`startup/StartupAppCheck.kt`** (новий, чистий Kotlin без Android):
   - `start(scope, prerequisite)` запускає інсталятор на `Dispatchers.IO`
     після `prerequisite` і завершує `installed` у `finally` — навіть коли
     інсталятор кинув, щоб Firestore-блоки не зависли назавжди;
   - `launchAfter(scope, prerequisite, block)` виконує блок лише після
     privacy-гейта ТА інсталяції — це і є збережений контракт «App Check
     встановлено до першого Firestore-запиту»;
   - `awaitInstalled()` для стартового батчу, який усередині вже виконується.
   Інсталятор — параметр конструктора: тест підставляє фейк, а не Firebase.

2. **`App.kt`**:
   - `startupAppCheck` біля `privacyInstalled`; інсталятор — наявний
     `installAppCheckIfConfigured()` (його тіло й деградація без конфігу не
     змінюються, лише потік виклику);
   - інсталяція стартує одразу після встановлення privacy-маршруту, до решти
     стартових запусків, і більше не викликається на main;
   - `launchAfterAppCheck { }` = `startupAppCheck.launchAfter(startupScope,
     privacyInstalled, block)`;
   - на нього переходять усі стартові блоки, що можуть відкрити Firestore:
     `PeopleNewArrivalWorker.schedule` (worker тягне `sourceCatalog`),
     `personBookmarksSync.sync`, `sourceCatalog.fetchCatalogSections`,
     `CuratedSeed`, `CuratedCoverSeed` + `libraryCoverResolver`,
     `universeRefreshPass`, `listenerIdentity.ensure`, відкладена
     `hydrateFourReadCatalog`;
   - стартовий батч перед `sourceCatalog.refreshCuratedCollections()` (перша
     побудова `sourceCatalog` тягне `sharedMetaStore`/`searchCache` — Firestore)
     чекає `awaitInstalled()`; опублікування значень і `startupApplied` від
     цього не залежать, тож перший кадр не чекає Firebase.

3. **`StartupAppCheckTest.kt`**: свідок на фейковому інсталяторі —
   - інсталяція виконується не на потоці виклику (thread assertion, як у
     `StartupReadsTest`);
   - Firestore-подібний блок із `launchAfter` не стартує, поки інсталятор
     заблокований, і стартує після його завершення (порядок зафіксований);
   - інсталятор, що кинув, усе одно відпускає гейт (немає вічного очікування).

## Чого не чіпаю

- `installAppCheckIfConfigured()`: ті самі перевірки `getApps` →
  `initializeApp` → `recaptchaSiteKey` → інсталяція фабрики; без конфігу —
  такий самий silent no-op.
- UI, privacy-маршрут, семантику `StartupReads`, решту стартових блоків
  (локальні/мережеві, без Firestore) лишаю на `launchAfterPrivacy`.
- Повний набір тестів не ганяю: цільовий прогін через
  `scripts/test-changed.sh` від бази `origin/main`; важкі збірки — на
  dragonborn, якщо локальний слот відмовить.

## Перевірка

- `scripts/test-changed.sh` для змінених шляхів (startup-пакет + `App.kt`).
- `scripts/mutate.sh` для нового гейта: знешкодити `installed.await()` у
  `launchAfter` → тест порядку червоний.
- CI на PR; мердж не роблю.
