# План: #1487 — зріз 2, зонд невідомої сторінки

Зріз 1 (#1512) приймає сторінку книги **відомого** джерела. Лишається друга
половина тікета: посилання на **невідомий** сайт — «спробувати витягти те, що
є на сторінці (назва, автор, обкладинка, аудіо), або чесна відмова з
дверцятами «відкрити у браузері»». Порожнього «нічого не знайдено» без
причини бути не може.

## Рішення

1. **Один запит на вставлене посилання, без каскаду.** Транспорт — наявний
   `HttpFetcher` через спільний `SourceRequestGate` класом
   `LISTENER_ACTION` (тап слухача не голодує за фоном); максимум 2 МБ HTML,
   без cookie, без JS, без повторів. Це і є «бюджет запитів» тікета: один
   явний тап = один запит.
2. **Парсер — чистий і названий.** `BookPageProbe` (data/ingest): `og:title` /
   `<title>`, автор із `book:author`/`author`/`article:author` (далі JSON-LD
   `author.name`), обкладинка з `og:image`/`twitter:image`, опис з
   `og:description`, аудіо — `contentUrl` у JSON-LD, `<audio>`/`<source>`
   теги і абсолютні медіа-URL у розмітці (`.mp3/.m4a/.aac/.ogg/.opus`).
   Відносні URL розв'язуються від сторінки; `data:`/`javascript:`/`blob:`
   відкидаються. Нема чого витягти — `ProbedPage` з порожнім списком аудіо,
   а не вигадана картка.
3. **Картка з невідомої сторінки — локальна, під внутрішнім маркером
   `unknown`.** `sourceIdForUrl` уже повертає цей маркер для хостів, яких не
   знає реєстр, і UI його ховає (без provenance-чіпа). Імпорт іде тим самим
   `importBookFromSource` (MergeKey-дедуп, детермінований Edition id), але
   `writeBackProfile = false`: невідома сторінка **не пише в спільну базу**
   (ADR-0028/0035 — колективний канал несе лише підтверджені джерела) і не
   публікується; `origin = EXPLICIT_SAVE` (слухач свідомо вставив лінк).
4. **Дедуп за канонічним URL.** Канонічна форма та сама, що в зрізі 1
   (`KnownSourceLinkResolver.canonicalUrl`): `https://host/path`, без
   query/fragment/хвоста `/`; повторний імпорт того самого URL не плодить
   другу картку — MergeKey/Edition-дедуп повертає наявну (`ALREADY_ADDED`).
5. **Чесні відмови з браузером.** Дві причини: `PAGE_UNREADABLE` (сторінку не
   вдалося прочитати/розібрати) і `PAGE_NO_AUDIO` (сторінка є, аудіо немає).
   Обидві несуть канонічний URL → у листі кнопка «Відкрити у браузері»
   (`MainViewModel.openWebFallback`, наявний системний ACTION_VIEW). Це не
   тихий WebView і не серверний конвертер.
6. **Повідомлення після імпорту — окреме й чесне:** книга з невідомої
   сторінки (не каталог Слухайки; якість і доступність залежать від сайту),
   без обіцянки публікації.

## Межі (названі)

- публічні сторінки без логіну; cookie/сесії не зберігаються;
- жодного yt-dlp до каталогів (межа тікета), жодного серверного конвертера;
- browser-tier джерела лишаються на своїй браузерній двері — зонд не робить
  спроб обійти Cloudflare;
- сторінка з аудіо-URL, які не грають (403/HTML-замість-аудіо), дасть картку,
  яку плеєр чесно позначить як недоступну — це наявна поведінка джерел;
- зонд не шукає медіа в інших запитах сторінки (JS-плейлисти без URL у
  розмітці лишаться відмовою — названа межа, а не тиха порожнеча).

## Файли

- `data/ingest/BookPageProbe.kt` (новий, чистий) + `ProbedPage.toSourceBookDetail`.
- `data/imports/LibraryImport.kt` — `importProbedPage(detail)` (той самий
  importBookFromSource, `UNKNOWN_SOURCE_ID`, без write-back).
- `data/ingest/ListenerSubmissionFlow.kt` — `Kind.PAGE`, гілка
  `submitProbedPage`, причини `PAGE_UNREADABLE`/`PAGE_NO_AUDIO`,
  `Start.Refused(browserUrl)`, `Start.Imported(probedPage)`, seam-и
  `probePage`/`importProbedPage`.
- `App.kt` — композиція: `HttpFetcher().getText(…LISTENER_ACTION…)` + парсер;
  імпорт через `libraryImport.importProbedPage`.
- `MainViewModel` / `SubmissionSheet` / `LibraryScreen` — стан `Refused` із
  `browserUrl`, кнопка браузера, повідомлення про картку зі сторінки.
- рядки uk/en/pl.

## Перевірка

- `BookPageProbeTest` (чистий JVM): og/JSON-LD/`<audio>`/відносні URL,
  дедуп, entities, відкидання `data:`/`javascript:`, сторінка без аудіо
  (title є, chapters порожні), порожній HTML.
- `ListenerSubmissionFlowTest`: playable→`Imported(publishable=false,
  probedPage=true)`; no audio→`Refused(PAGE_NO_AUDIO, browserUrl)`;
  unreadable→`Refused(PAGE_UNREADABLE, browserUrl)`; провал імпорту→
  `Refused(IMPORT_FAILED)`; офлайн→`Deferred`; YouTube/TG незмінні.
- `SubmissionSheetTest`: відмова з `browserUrl` показує кнопку й віддає URL;
  повідомлення про картку зі сторінки; без `browserUrl` кнопки немає.
- Мутації: прибрати вимогу «аудіо є» → flow-тест червоніє; прибрати
  `browserUrl` з відмови → UI/flow-тест червоніє; зламати нормалізацію URL →
  резолвер-тест червоніє.
- Воркер: цільові `:app:testPureJvm` і `:app:testRoomRobolectric`; повний
  матрикс — CI.
