# План: #1606 — живий індекс «Колекції» + дія в порожньому стані

`MainViewModel.openCollectionsIndex()` копіював `smartCollections.value` один
раз (`:2544`), тож оновлення каталогу під час відкритого індексу не доходило
до екрана — при тому що Огляд читає той самий потік живо
(`HomeScreen.kt:204`). Порожній стан (`CollectionsIndexScreen.kt:73-80`)
показував «з'являться після завантаження каталогу» без жодної дії.

## Рішення

1. **Новий `ui.collections.CollectionsIndexModule`** — вузький шов:
   `open()` публікує поточний знімок як перший кадр і підписується на
   `SourceCatalog.smartCollections`; `close()` скасовує підписку й скидає
   список. `isOpen`/`collections` — StateFlow; `scope` інжектується
   (`viewModelScope` у прод, `TestScope` у тестах).
2. **`MainViewModel` делегує** open/close і віддає ті самі публічні flow, тож
   екран не змінює контракт.
3. **Порожній стан отримує одну дію** «Оновити каталог»
   (`collections_index_refresh` uk/en/pl), що запускає той самий
   `refreshUnifiedCatalog()`, що й Огляд; кнопка — реальний `TextButton`
   (48 dp, доступна назва).
4. **Тести:** `CollectionsIndexModuleTest` (4 сценарії: перший кадр, live
   оновлення, close скидає, reopen показує свіже) і snapshot-тест перевіряє
   дію порожнього стану; сам еталон порожнього екрана перезаписується в CI
   (`record-snapshots.yml`), як вимагає #848.

## Межі

- змінюється лише індекс «Колекції» (модуль, екран, рядки, тести); Огляд і
  `SourceCatalog` не чіпаються; жодних лічильників/нових станів.

## Перевірка

- `:app:testPureJvm …CollectionsIndexModuleTest` — 4/4.
- `:app:testComposeRoborazzi …CollectionsIndexSnapshotTest` — еталон
  порожнього стану перезаписано на CI-раннері, тест перевіряє виклик дії.
- Вичерпні гейти — CI (Compose+Roborazzi, Room, Lint, a11y).
