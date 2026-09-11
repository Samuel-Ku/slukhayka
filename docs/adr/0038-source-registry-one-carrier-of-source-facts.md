---
status: accepted
---

# Source Registry — один носій статичних фактів джерел

Хвиля spec-47 додала три джерела (chytaylo, ukrainianaudiobooks, audiobook.co.ua)
і зачепила 15+ файлів у двох кодебазах. Ревізія показала: **той самий факт
про джерело живе в багатьох місцях із ручним «parity by comment»**, і дрейф
уже видно — ukrainianaudiobooks відсутній на web, `SOURCE_ORDER`
(`web/src/worker/sourceMetadata.ts`) і `directOrder`
(`SourceAccessPolicy`) — два ручні списки одного порядку, id-схеми
платформ розходяться (web `fourread`/`sound-books`/`audiobook-mp3` проти
доменних `4read`/`soundbooks`/`audiobookmp3`), `sourceIdForUrl` і
`sourceDisplayName` живуть у файлі глобального пошуку, hosts транспорту
(SSRF-гард web проти page/audio-hosts Android) — окремими руками.

## Рішення

1. **Один носій — `sources.json` на рівні репо.** Кожне Source має один
   декларативний рядок: `id` (доменний, персистований), `displayName`,
   `homeUrl`, `contentLanguage`, `accessMode` (Source Access Mode),
   `order` (єдиний список порядку — поглинає `directOrder` /
   `SOURCE_ORDER` / `SOURCE_PRIORITY`), `streamOnly`, `referer`
   (значення + host-скоупінг), `searchUrl` (шаблон із `{q}`),
   `searchHeaders`, `catalogUrl`, `transportHosts` (об'єднання hosts
   транспорту), `browserProfile` (факти ADR-0036: `pageHosts`,
   `audioHosts`, `searchDoor`-шаблон, `homeUrl`, `entryNotice`,
   `manifestProbe`, `manifestPlaceholder` + `manifestPlaceholderReplacement`,
   `releaseBrowserDoor`).
2. **Паритет будується, а не коментується.** Web-воркер імпортує
   `sources.json` напряму (`resolveJsonModule`). Android тримає типовий
   `SourceRegistry`-читач (константи + аксесори); **JVM conformance-тест**
   читає той самий файл (MiniJson, чистий JVM) і припінає кожен факт
   повною рівністю — зайвий, відсутній чи змінений факт падає на
   build-time. Web conformance-тест (vitest) припінає `SOURCE_ORDER`,
   `SOURCE_METADATA`, `REGISTRY.allowedHosts`/`searchUrl`/`catalogUrl`
   проти того самого файлу.
3. **Browser Recovery Profiles їдуть у тому самому носії** — розвиток
   ADR-0036 («не другий реєстр»), не перевідкриття: `BrowserRecoveryProfiles`
   залишається типовим читачем, факти переїжджають у `sources.json`.
   `releaseBrowserDoor` лишається прапорцем профілю: зміна для іншого
   джерела — записане рішення (ADR-0027), не конфіг.
4. **Поза реєстром — знання платформи.** `sessionBound` — здатність
   адаптера конкретної платформи (sluhay: Android `sessionBound = true`,
   воркер фетчить його серверно — прецедент, що забороняє нести цей
   факт у реєстр). Правило порядку tier (LOCAL < DIRECT < UNKNOWN <
   BROWSER) — код у `SourceAccessPolicy`; дані (tier і внутрішній
   порядок) — реєстр. Парсинг адаптерів і plain-string id у `sources`-
   таблиці не чіпаються.
5. **Канонічні id — доменні** (ті, що персистуються в Room):
   web-ключі-аліаси (`fourread`, `sound-books`, `audiobook-mp3`)
   лишаються worker-локальними до міграції споживачів; web
   conformance-тест тримає локальну мапу аліасів.

## Наслідки

- Нове джерело = один рядок у `sources.json` + адаптер + фікстури.
  Жодних правок політик, списків порядку чи другого реєстру.
- Порядок уніфікується за реєстром: librivox стає перед
  audiobookcoua/chytaylo в DIRECT-тіері Android (сьогодні tie-by-name);
  sluhayknigi — останнім серед браузерних. Дрібні нормалізації
  (  trailing-slash `homeUrl` 4read; бейдж sluhayua стає «Sluhay UA»
  на Android замість двозначного «Sluhay»; web-лейбл audiobook-mp3
  стає «audiobook-mp3» — власною назвою сайту) виконуються в міграції
  споживачів. Відомі розбіжності припінаються conformance-тестом web
  (KNOWN_MIGRATION_DIVERGENCES), тож не можуть дрейфувати далі.
- `transportHosts` = об'єднання hosts: SSRF-гард web (`allowedHosts`)
  виводиться з нього в міграції — для 4read це додає `reasd.org` до
  allowlist-у воркера (власна інфраструктура джерела; розширення
  безпечне й навмисне).
- Міграція споживачів — окремі кроки: `sourceIdForUrl`/`sourceDisplayName`
  (з GlobalSearch.kt), `SourceAccessPolicy`, `DownloadPolicy`,
  `SourceBrowserPolicy`, `BrowserRecoveryProfiles`, фільтр direct-адаптерів
  у `App.kt`, web `sourceMetadata.ts`/`registry.ts`/`workFeed.ts`.
  Кожен крок — зелений conformance-тест до і після.