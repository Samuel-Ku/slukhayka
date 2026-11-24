# План: #1487 — посилання на книгу з відомого джерела (зріз 1)

Слухач просить: «надіслати лінк на книгу, а він відкривши це посилання витягнув
медіадані». Зараз двері «Надіслати посилання» приймають лише YouTube і
Telegram; сторінка книги з будь-якого каталогу повертає `Unsupported`.

Цей зріз закриває **першу половину** запиту: посилання на сторінку книги
**відомого** джерела імпортується так само, як зі звичайного пошуку — без
ручного пошуку книги вдруге. Друга половина (невідомий хост: спільний зонд
`og:*`/аудіо-URL і чесна відмова з «відкрити у браузері») — окремий зріз 2,
бо це новий механізм, а не маршрутизація.

## Рішення

1. **Не нова таблиця хостів, а наявний реєстр.** `sourceIdForUrl` (ADR-0038,
   `SourceRegistry.entries`: home-хост + `transportHosts`) уже відповідає, чий це
   хост. Новий чистий `KnownSourceLinkResolver` лише нормалізує URL і питає
   реєстр; невідомий хост → null (далі — наявний `Unsupported`).
2. **Канонічна форма для дедупу:** `https://<host без www><path>`; query й
   fragment відкидаються (лічильники/utm на сторінці книги нічого не міняють),
   хвіст `/` знімається, `http` → `https`. Корінь хоста (`/`) — не книга і не
   приймається: імпорт без сторінки був би вигадкою.
3. **Імпорт — наявні двері `LibraryImport.importFromSourceUrl`**, той самий, що
   у «Дослідити»: `fetchBookPage` → `importBookFromSource` з MergeKey-дедупом.
   `origin = EXPLICIT_SAVE` (слухач свідомо вставив посилання — ADR-0047/0063),
   `onNewBookImported` розрізняє «нова» і «вже в медіатеці».
4. **Не витрачає денний бюджет і не публікує.** Бюджет — про нові джерела
   (YouTube/TG), а не про імпорт із каталогу; тому `Start.Imported` з
   `publishable = false`, а бюджет читається лише для показу. Спільний каталог
   не отримує нічого: книга вже є в джерелі-каталозі.
5. **Шляхи YouTube/Telegram не змінюються:** нова гілка спрацьовує лише тоді,
   коли `SubmissionUrlCanonicalizer.canonical` повернув null. Офлайн —
   наявна видима черга (`Deferred`), тож повторний прогін `processDeferred`
   обробить посилання тим самим шляхом.

## Файли

- `data/ingest/KnownSourceLinkResolver.kt` — новий чистий резолвер (нормалізація
  + `sourceFor` лямбда, без Android).
- `data/ingest/ListenerSubmissionFlow.kt` — `Kind.SOURCE`, гілка
  `submitKnownSource` (офлайн/бюджет/імпорт/відмови), seam-параметри
  `resolveKnownSource` і `importKnownSource`.
- `App.kt` — композиція: `sourceIdForUrl` + `libraryImport.importFromSourceUrl`.
- рядки дверей (uk/en/pl): підказка, «не підтримується», підпис опції імпорту.

## Межі (названі)

- лише джерела **реєстру**; невідомий хост і далі `Unsupported` — зонд і
  браузерна дверцята лишаються зрізом 2;
- посилання на неф-книжкову сторінку джерела (тег/жанр) відхилиться на імпорті
  (`IMPORT_FAILED`) — чесна відмова, а не порожня картка;
- browser-tier джерела (4read, sluhay) можуть вимагати браузерної сесії; їхній
  `fetchBookPage` поверне null → `IMPORT_FAILED`, без тихого WebView;
- yt-dlp до каталогів не застосовується (межа тікета).

## Перевірка

- `KnownSourceLinkResolverTest` — нормалізація (http/www/query/trailing slash),
  корінь хоста, невідомий хост, не-http схема, YouTube/TG не перехоплюються.
- `ListenerSubmissionFlowTest` — новий лінк → `Imported(publishable=false)`;
  `ALREADY_ADDED` → `alreadyInLibrary`; провал імпорту → `Refused(IMPORT_FAILED)`;
  офлайн → `Deferred` у черзі; невідомий хост → `Unsupported`; наявні
  YouTube/TG-тести зелені.
- Мутація: прибрати перевірку «корінь хоста не книга» → тест червоніє; прибрати
  `publishable = false` → тест «не публікує» червоніє.
- Воркер: `:app:testPureJvm -Ptest.selectedClasses=…KnownSourceLinkResolverTest,…ListenerSubmissionFlowTest`;
  далі повний `test-changed` за зміненими шляхами і CI.
