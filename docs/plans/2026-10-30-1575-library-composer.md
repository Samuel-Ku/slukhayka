# План: #1575 — композиція Медіатеки виходить із MainViewModel

`MainViewModel` (≈7000 рядків) інлайнив дві чисті проєкції бібліотеки:
unified-список (`allBooks` × `recentProgress` × `allChapters` × readable ids,
`buildLibraryBooks`) і availability-мапу (список × persisted verdicts ×
Source-Audio-Refusal, ADR-0042), плюс прапорець #1066 `isLibraryLoaded`.
Це не навігація й не дії span-модулів, тож за ADR-0008 вони живуть у
власному модулі.

## Рішення

1. **`ui.library.LibraryCompositionModule`** — вузький шов:
   - приймає ЧОТИРИ flows (`allBooks`, `recentProgress`, `allChapters`,
     `onDeviceBookIds`) + два потоки стану availability (verdicts, refused),
     а не Room/ViewModel; `ioDispatcher` — параметр (тести керують);
   - `books`: combine → `buildLibraryBooks` (та сама чиста функція);
   - `isLoaded`: один раз після першої РЕАЛЬНОЇ емісії `books` (не на
     стартовому порожньому значенні StateFlow) — контракт #1066;
   - `availability`: `books` × verdicts × refused → мапа mergeKey → view;
     чисті книги відсутні, локальні/завантажені пропускаються.
2. **`MainViewModel` делегує** getter-ами (`libraryBooks`, `isLibraryLoaded`,
   `libraryAvailability`); публічні типи, `WhileSubscribed(5000)` і порядок
   ініціалізації збережені; жоден екран не змінюється.
3. **Тест** `LibraryCompositionModuleTest` (pure JVM, `MutableStateFlow`):
   combine-виходи списку, `isLoaded` після першої емісії,
   availability (REFUSED / чиста / downloaded).

## Межі

- виділяються рівно два combine + прапорець; `listenBlocks`, `selectedBook*`,
  навігація, span-модулі лишаються у ViewModel (наступні slices #1575);
- `buildLibraryBooks`/`LibraryAvailabilityPolicy`/схема Room не змінюються;
- UI-тести (`LibraryBooksTransitionTest`, `LibraryModelTest`,
  `LibraryProjectionTest`) — регресійний гейт.

## Перевірка

- pure: `LibraryCompositionModuleTest`, `LibraryModelTest`,
  `LibraryProjectionTest` — зелені;
- robo: `LibraryBooksTransitionTest` (MainViewModel інтеграція) — зелений;
- мутації: (а) `hasAvailableAudio` → `true` (availability-тест червоніє);
  (б) прибрати фліп `isLoaded` (тест #1066 червоніє);
- CI — вичерпний гейт.
