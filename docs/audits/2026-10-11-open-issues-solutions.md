# Відкриті питання Слухайки: рішення та порядок робіт

Дата: 2026-10-11. Огляд охоплює **35 відкритих GitHub issues** зі знімка, повторно отриманого під час дослідження. Початковий список містив 30; додано #1226–#1230. Жоден issue з цього переліку не пропущено.

Джерела: тіла issues, усі коментарі, доступні через `gh issue list`, новіші пов’язані обговорення, п’ять відкритих PR, резервування та код. Основний код — upstream [main `91c837c`](https://github.com/Samuel-Ku/slukhayka/tree/91c837c07e50db3da3443f44cdd62f3db74dc840). Локальне робоче дерево старіше й містить незавершені зміни; вони не є доказом стану main або розгорнутого середовища. Для незлитих рішень явно зазначено PR. Зовнішні технічні й ліцензійні твердження звірено з первинними джерелами.

Це дослідження й рекомендації. Код, існуючі документи, резервування, labels, issues та PR не змінювалися. Нових runtime/device/backend тестів не запускали. Згадки зелених перевірок посилаються на наявні результати й не розширюють їхню межу доказу. Рекомендація, яка потребує вибору власника, не вважається ухваленим рішенням.

## Що потребує уваги першим

1. **Локальні книги: #1187, #1163, #1185, #1230.** Невідомий час не має ставати повним total або доказом завершення. Облік місця й очищення мають працювати з тими самими файлами. Відновлення локального Source починається з файлів пристрою. Читання за посиланням уже ухвалене власником; продовжувати чинний PR #1186, не починати другу реалізацію.
2. **Відгуки: #1226 усередині #620.** Перевірити й виправити refresh-before-ACK DELETE у чинного виконавця. Старий snapshot не має повертати видалений відгук або його голос у headline.
3. **Колективний каталог: #521/#533 та #524/#525.** In-memory lease не доводить одного власника між інсталяціями. Identity блока source+kind не розрізняє різні книжкові рекомендації. Публікація й cursor потребують серверного порядку та захисту від старого запису.
4. **Якість рекомендацій: #487.** На реальному каталозі актуальний протокол дав NO-GO. Перший крок — діагностика метрик і runtime-контрактів із збереженим протоколом; не заміна моделі чи послаблення порога без нового дослідження.
5. **Перевірки: #1219 і #1220.** Відбір тестів пропускає предків пакета та відправляє web-only diff у fullSuite. Merge-гейт має перевіряти точний HEAD та повний набір required checks; сама мітка `review:pass` не забезпечує свіжого огляду.

## Карта всіх відкритих issues

P1 — наступна хвиля через ризик втрати стану, неправдивих даних або нестриманого злиття. P2 — після цих виправлень або незалежним малим зрізом. P3 — дослідження/документація без негайного впровадження. Це запропонований порядок, не зміна tracker labels.

| Issue | Рекомендоване рішення | Передумова / межа | Черга |
|---|---|---|---|
| [#1230](https://github.com/Samuel-Ku/slukhayka/issues/1230) | Відновлення конкретного локального Source через picker і provenance | Один шов з #1185/T2, не нова книга за назвою | P1 |
| [#1229](https://github.com/Samuel-Ku/slukhayka/issues/1229) | Узгодити навігацію до релевантних ADR без дублювання тексту | Документаційний контроль, без нового CLI | P3 |
| [#1228](https://github.com/Samuel-Ku/slukhayka/issues/1228) | Компактний read-only snapshot PR/CI/claims з точними SHA | Звіряти коментарі іншої машини; unknown не є free | P2 |
| [#1227](https://github.com/Samuel-Ku/slukhayka/issues/1227) | Унікальність канонічних ADR у merge tree + validated redirects | #1139 має 0062 і старий 0061 redirect; не рахувати його новим рішенням | P2 |
| [#1226](https://github.com/Samuel-Ku/slukhayka/issues/1226) | DELETE ACK прибирає документ із поточного confirmed стану | Чинний власник #620; причинний RED→GREEN | P1 |
| [#1225](https://github.com/Samuel-Ku/slukhayka/issues/1225) | Доповнити local-environment: density, web і Room cohorts | Узгодити з актуальними скриптами та правилами reservation | P2 |
| [#1224](https://github.com/Samuel-Ku/slukhayka/issues/1224) | Повторюваний браузерний щуп геометрії | Real DOM/viewport; стабільний fixture, не regexp CSS | P2 |
| [#1223](https://github.com/Samuel-Ku/slukhayka/issues/1223) | Пін паритету Android/Web межі 600 | Семантична перевірка boundary, не лише однакові літерали | P2 |
| [#1222](https://github.com/Samuel-Ku/slukhayka/issues/1222) | Multiset diff suppressions lint-baseline проти base | Без сирого порівняння XML і без приховування росту | P2 |
| [#1221](https://github.com/Samuel-Ku/slukhayka/issues/1221) | Ізольовані мутаційні прогони з journal і явним interrupted | Trap не гарантує відкат після SIGKILL; берегти primary tree | P2 |
| [#1220](https://github.com/Samuel-Ku/slukhayka/issues/1220) | Вердикт прив’язаний до HEAD + required checks + match-head merge | Серверний ruleset для захисту від обходу скрипта | P1 |
| [#1219](https://github.com/Samuel-Ku/slukhayka/issues/1219) | Union тестів package ancestors + окремий web lane | Локальні targeted прогони, не full Android suite для web | P1 |
| [#1208](https://github.com/Samuel-Ku/slukhayka/issues/1208) | Закріпити вже ухвалене й залишити лише невирішені питання | Новіші owner comments #1205 змінюють початкове тіло | P2 |
| [#1198](https://github.com/Samuel-Ku/slukhayka/issues/1198) | Event живого хіта, якого не було в індексі перед пошуком | Пошуковий шар зайнятий PR #1162 | P2 |
| [#1188](https://github.com/Samuel-Ku/slukhayka/issues/1188) | Спільний reorder editor з handle, autoscroll і доступними діями | Перегляд чинного рішення про стрілки — явно | P2 |
| [#1187](https://github.com/Samuel-Ku/slukhayka/issues/1187) | Local duration reader + completeness-aware total/completion | Плеєр, картка й resume мають одне правило | P1 |
| [#1185](https://github.com/Samuel-Ku/slukhayka/issues/1185) | Продовжити T1+T3 разом, далі recovery/migration/storage/cast | PR #1186 і резервування зовнішнього чату | P1 |
| [#1163](https://github.com/Samuel-Ku/slukhayka/issues/1163) | Єдиний облік app-owned audio й scoped очищення | Local sole copies не є download cache | P1 |
| [#1005](https://github.com/Samuel-Ku/slukhayka/issues/1005) | Малий transcript MVP з реально прослуханим prefix | Seek-forward не дає права на пропущений текст | P3 |
| [#968](https://github.com/Samuel-Ku/slukhayka/issues/968) | Явне ручне злиття й undo з планом колізій | D1 у #1161; D2 чекає рішень власника | P2 |
| [#842](https://github.com/Samuel-Ku/slukhayka/issues/842) | Окремий адмінський Telegram-бот і backend moderation | Credentials, бот і deploy поза репо; ready-for-human | P2 |
| [#831](https://github.com/Samuel-Ku/slukhayka/issues/831) | Persisted provenance і наскрізний badge/filter/rail | Message URL та «Від слухачів» ще мають прогалини | P2 |
| [#817](https://github.com/Samuel-Ku/slukhayka/issues/817) | Один актуальний release ledger для web parity | Не підбирати новий реліз за старим milestone самостійно | P2 |
| [#784](https://github.com/Samuel-Ku/slukhayka/issues/784) | Аудит прав, внесків і залежностей перед вибором ліцензії | BSL не знімає GPL obligations залежностей | P1 перед зміною ліцензії |
| [#753](https://github.com/Samuel-Ku/slukhayka/issues/753) | Прийняти лишки Telegram source наскрізно | Auth/membership, extraction та provenance; umbrella | P2 |
| [#698](https://github.com/Samuel-Ku/slukhayka/issues/698) | Ledger критеріїв і закриття лише після #1198 | Не повторювати вже зроблені нагороди | P2 |
| [#697](https://github.com/Samuel-Ku/slukhayka/issues/697) | Звірити web read/vote/report з backend та платформою | Старий web-CI blocker уже знято; не переписувати UI | P2 |
| [#620](https://github.com/Samuel-Ku/slukhayka/issues/620) | Завершити confirmed state/snapshot acceptance й #1226 | Чинний PR #1181, ще не full backend/device acceptance | P1 |
| [#557](https://github.com/Samuel-Ku/slukhayka/issues/557) | Матриця паритету і вертикальні сценарії | Web мінімальна адаптивність уже на main; решта окремо | P2 |
| [#533](https://github.com/Samuel-Ku/slukhayka/issues/533) | Дві справжні інсталяції + request/backend/audio evidence | CI/unit не замінює приймання на пристроях | P1 |
| [#525](https://github.com/Samuel-Ku/slukhayka/issues/525) | Feed/block freshness, identity й backend publication | Чинний PR #1162; окремо погодити collective gaps | P1 |
| [#524](https://github.com/Samuel-Ku/slukhayka/issues/524) | Demand search без implicit feed + реальне аудіо й delta | Чинний #1162; Accessibility CI та runtime ще відкриті | P1 |
| [#521](https://github.com/Samuel-Ku/slukhayka/issues/521) | Контракт collective blocks, server lease/cursor й acceptance | Umbrella; не закривати лише за дочірніми checkboxes | P1 |
| [#487](https://github.com/Samuel-Ku/slukhayka/issues/487) | Зберегти NO-GO та діагностувати quality/runtime окремо | Draft #1139; thresholds і frozen inputs не міняти тихо | P1 |
| [#480](https://github.com/Samuel-Ku/slukhayka/issues/480) | Event-driven/cache contracts + quality ledger | Umbrella залежить від #487, не новий rewrite | P2 |

## Запропонована послідовність

**Спочатку локальні дефекти й точність перевірок:** #1187 та #1163, #1226 у чинного власника #620, #1219/#1220. Дослідження не передає чужі резервування; реалізація #1185, #487, #524/#525, #620/#533 і #968 продовжується тільки у чинних координаторів або після явної передачі.

**Далі завершити активні зрізи:** local reference/recovery (#1185/#1230), SluhayUA (#524/#525), confirmed state (#620). До #533 погодити й закрити знайдені collective gaps. Зелений CI кандидата є передумовою, але не доказом міжпристроєвого контракту.

**Малі незалежні поліпшення:** #1222–#1225, #1227–#1229 і release ledger #817. Browser probe #1224 та shared-boundary guard #1223 мають доповнювати одне одного: числовий паритет і реальна геометрія — різні докази.

**Після звільнення відповідних шарів:** #1198/#698, provenance #831, #697/#557, reorder #1188. Власникові лишаються явні продуктові вибори #1208 і #968/D2; ліцензійний вибір #784 робити лише після аудиту прав та реально shipped dependencies. Narrative GPS #1005 — окремий дослідний зріз після стабілізації базового програвання.

Нижче — подробиці, альтернативи та критерії для кожного issue.


## Локальні книги й імпорт

### #1230 — відновлення нечитабельного локального файла

**Пріоритет: P1. Статус: новий issue; спільне рішення з #1185/T2, не друга паралельна реалізація.** [Issue](https://github.com/Samuel-Ku/slukhayka/issues/1230).

**Рекомендація.** На межі відкриття Source розрізняти причини: втрата дозволу/відсутній документ, помилка читання, непідтримуване чи пошкоджене аудіо, мережева недоступність. Для локальної причини перша дія — «Обрати файл або папку заново», далі rescan відомої теки; «Шукати інше джерело в мережі» — окрема явна дія. Наявний `PlaybackReplacementMapping` за порожнього playable і без preferred source викликає мережевий resolver, але не має такого локального recovery-контракту. Це читання коду, не виконане відтворення скарги. [Mapping](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/ui/catalog/PlaybackReplacementMapping.kt).

Picker запускається в режимі **відновлення конкретного Source/Edition**, не звичайного додавання нової книги. За живою provenance та збігом хешу змінюється тільки locator; за новим деревом — preview зіставлення фізичних файлів зі стабільними Chapter ID. Неоднозначність потребує явного підтвердження; збіг назви або кількості файлів не доводить тотожності. Інша начитка стає іншою Edition. Навіть підтвердження «та сама начитка» не дає права переносити секунди між іншою структурою глав без відповідного mapping. Стара книга, Listening State і закладки зберігаються; старе приватне аудіо не видаляється цим recovery.

Використати один сервіс та UI-контракт із «відновити доступ» #1185/T2. SAF permission видає система; перевіряти повторне читання й відрізняти revoke від codec failure. Не сканувати весь пристрій мовчки й не обходити picker. [SAF](https://developer.android.com/training/data-storage/shared/documents-files), [пам’ять походження](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/data/imports/LocalFolderMemory.kt).

**Слабший варіант:** відкрити звичайний імпорт і сподіватися на merge за назвою. Це може створити дубль або прив’язати іншу начитку. **Перевірка:** перенесення між деревами; revoke; незмінені bytes; змінений файл із тією самою назвою; інша начитка; часткове відновлення; скасований picker; stale preview; resume тієї самої глави. Перший тап recovery не робить мережевого пошуку, не створює нової Edition та не скидає закладки.

### #1187 — невідомі тривалості та помилкове завершення

**Пріоритет: P1. Статус: дефект підтверджений читанням коду; конкретне скидання на пристрої ще не відтворено.**

На main `91c837c` локальні книги досі відсіюються з `ChapterDurationProbe.probeBookNow` за порожнім `sourceUrl`. `AudioPlayerManager` порівнює позицію **в главі** із сумою відомих глав. Окремо `bookProgress` використовує часткову суму як загальний час, коли немає заявленого total. Отже виправлення має пройти крізь плеєр, картку й правило завершення, а не лише заповнити тривалості. [Probe](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/data/duration/ChapterDurationProbe.kt#L85), [порівняння](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/player/AudioPlayerManager.kt#L1373), [BookProgress](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/data/listening/BookProgress.kt).

**Рекомендація.** Окремий локальний reader тривалості, який спільний координатор вимірювання викликає для file/content URI. `MediaMetadataRetriever.setDataSource(context, uri)` і `METADATA_KEY_DURATION` дають метадані часу; доступ може завершитися помилкою, а виклик може бути повільним. Тому робота — поза UI-потоком, з обмеженою конкурентністю, звільненням ресурсів у `finally`, скасуванням і per-Edition single-flight. API не гарантує дешевого читання кожного контейнера чи провайдера: це треба виміряти. [Android API](https://developer.android.com/reference/android/media/MediaMetadataRetriever).

Запускати після commit імпорту й при відкритті старої локальної книги з прогалинами. Не програвати глави заради метаданих. Для контейнерів із кількома логічними главами не записувати час цілого файла кожній главі: потрібні реальні межі. Зберігати повний виміряний total лише за повної відомості; частковий показувати як невідомий або явно частковий. Єдине правило завершення має відрізняти chapter offset від cumulative position та знати повноту часу. Справжній кінець останнього треку і явне «Прослухано» — окремі доведені події. Ручний перехід у главу/закладку не повинен ставати повторним слуханням.

**Слабший варіант:** просто прибрати `sourceUrl.isBlank()` і пустити content URI в HTTP `StreamProber`. Це змішує два транспорти й не виправляє хибний total.

**Перевірка:** нуль/частина/всі відомі тривалості; resume третьої глави з відомою лише першою; кінець останнього треку; явна закладка; пошкоджений файл; відкликана SAF-грамота; зміна порядку під час вимірювання; перезапуск. Для читання URI потрібен Android-тест, не лише fake-reader. #1185 не блокує виправлення: reader одразу має підтримувати поточний file URI та майбутній content URI.

### #1185 — локальна тека як посилання

**Пріоритет: P1. Статус: рішення власника вже ухвалене; реалізація зайнята іншою машиною.**

Не повертатися до вибору «копія чи посилання». Власник уже обрав посилання без нових приватних копій. [Issue з резервуванням](https://github.com/Samuel-Ku/slukhayka/issues/1185). Відкритий [PR #1186](https://github.com/Samuel-Ku/slukhayka/pull/1186) на `dbd3bd1` лише додає URI до scanner та ADR-0061/план; поведінку ще не перемикає. Локальний порожній registry не скасовує резервування в issue comment.

**Рекомендація.** Продовжувати існуючий план: аддитивний URI → одним узгодженим зрізом T1+T3 перемкнути імпорт і rescan → відновлення доступу → безпечна міграція та звіт → облік і casting. URI є locator SourceTrack; Chapter, Edition, прогрес та ручний порядок зберігають ідентичність. SAF дає доступ до вибраного дерева, але persisted permission не гарантує доступ після перенесення чи видалення документа. Потрібна справжня спроба відкриття, а не лише наявність grant. [SAF](https://developer.android.com/training/data-storage/shared/documents-files), [ContentDataSource](https://developer.android.com/reference/androidx/media3/datasource/ContentDataSource).

Rescan використовує розмір/дату як ухвалений швидкий фільтр. Відсутні або ненадійні метадані означають потребу перечитати файл, а не «незмінений». Метадані можуть пропустити заміну байтів з тією самою парою; це межа рішення, не криптографічна перевірка. Перед видаленням старої приватної копії потрібен фактичний збіг хешів. Записати новий locator транзакційно до фізичного видалення, повторно перевірити читабельність, зробити кроки відновлюваними після crash. Непевний стан залишає копію. Мертвий grant залишає єдине аудіо.

У `CastPlaybackController.upstreamFor` на main є лише HTTP fetcher — локальний casting має окрему вже наявну прогалину. Content URI не передається ресиверу: телефон читає bytes через resolver та власний PlaybackProxy; Range/seek, невідомий розмір і закриття descriptor перевіряються окремо. [Код casting](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/player/CastPlaybackController.kt#L294).

**Слабший варіант:** переключити лише T1. Він відріже rescan від файлів, які той досі читає. **Перевірка:** імпорт не збільшує private audio на розмір книги; playback після restart; втрачений/відновлений grant; незмінений файл не хешується; міграція з crash між commit/delete; спільний файл кількох references не видаляється завчасно; dead grant лишає копію; реальний casting або чесна відмова.

### #1163 — зайняте місце та «Завантажені»

**Пріоритет: P1. Статус: main досі рахує лише `audiobooks`; очищення скидає download flags глобально.** [Код обліку й очищення](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/data/downloads/OfflineDownloads.kt#L1331).

**Рекомендація.** Єдина проєкція сховища: завантаження з джерел, приватні локальні копії, залишки без посилань. Загальний лічильник підсумовує унікальні фактичні файли app-owned audio; вибрана зовнішня тека показується окремо як джерело й не додається до місця, зайнятого застосунком. До #1185 включити `local_imports`, після нього й далі рахувати unmigrated/retained copies.

«Завантажені» узгодити з manager: це завантаження з мережевих джерел. Якщо потрібен широкий фільтр усіх локально доступних Sources, назвати його «На пристрої» й перевіряти доступність, а не `isDownloaded` alone. Очищення downloaded files змінює лише відповідні SourceTrack/Book rows; не скидає стан локальних імпортів. У private-local-copies — окрема дія з конкретними наслідками; sole copy не є cache.

**Слабший варіант:** додати розмір другої теки до одного числа, лишивши глобальний reset flags. Число стане правдивим, але фільтр і видалення лишаться хибними. **Перевірка:** тільки local imports; тільки downloads; змішані; одна фізична копія в кількох references; втрачений grant; retained sole copy; після download-clear local book грає й її стан не змінений. Не порівнювати audio total з усім Android Data: там ще DB, кеші й інші дані.

### #1188 — порядок розділів через drag-and-drop

**Пріоритет: P2. Статус: потрібен явно записаний перегляд рішення про стрілки.** [Чинна мотивація](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/ui/screens/LibraryScreen.kt#L3174), [ADR-0056](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/adr/0056-manual-chapter-order.md).

**Рекомендація.** Спільний редактор порядку для прев’ю та вже доданої книги: окремий достатньо високий поверх із LazyColumn, видимим drag handle, автопрокруткою, Save/Cancel. Тягнеться тільки handle; рядок лишається доступним для скролу. Стрілки й клавіатура лишаються; TalkBack отримує «перемістити вище/нижче» й повідомлення нової позиції. Android прямо рекомендує custom actions для складних жестів. [Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics).

Переставляється чернетка за стабільними Chapter ID; на Save — наявні двері `LibraryImport` та stale-list check. Pairing Chapter/Track, anchor mapping та порядок всіх Sources залишаються контрактом ADR-0056. Для механіки дослідити `Calvin-LL/Reorderable`: він уже має handle, edge-scroll і LazyColumn, ліцензія Apache-2.0; сумісність із pinned Compose, поведінку вкладеного скролу та доступність треба перевірити, це не готовий verdict для застосунку. [Першоджерело бібліотеки](https://github.com/Calvin-LL/Reorderable).

**Слабші варіанти:** drag по всьому рядку конфліктує зі скролом; заміна стрілок жестом губить доступність; додавання власної реалізації autoscroll збільшує підтримку. Мінімальний handle у поточному діалозі можливий, якщо перевірка реальних довгих списків покаже достатню зручність. **Перевірка:** довгий список; перший/останній елемент; drag через край; Cancel; TalkBack; клавіатура; одночасний rescan/order commit; resume грає той самий Chapter після Save.

### #1005 — Narrative GPS

**Пріоритет: P3, дослідний зріз. Статус: концепція, гарантія spoiler-safe ще не формалізована достатньо.** [Issue](https://github.com/Samuel-Ku/slukhayka/issues/1005), [SmartRewind](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/player/SmartRewind.kt).

**Рекомендація.** Почати рівно з однієї книги з дозволеним transcript і timestamps: детермінована попередня межа репліки/сцени, локально, за окремим ввімкненням. Без генеративного переказу чи повного ASR у MVP. Старий SmartRewind лишається fallback у тих самих двох resume paths ADR-0003; бажаний semantic target проходить спільну політику обмеження відкату.

`segment.end <= currentPosition` недостатньо: після seek вперед слухач не чув пропущений текст. Потрібна множина реально програних інтервалів або консервативний неперервний прослуханий префікс. Доступні лише цілі сегменти в цих інтервалах і до актуальної позиції; майбутні сегменти не потрапляють в embeddings, пошук, контекст чи кеш відповіді. Межа перевіряється до retrieval і перед показом, навіть коли позиція змінилася. Прив’язка — Edition + стабільний Chapter ID + локальний offset; інша начитка потребує іншого alignment. Ці гарантії — рекомендація дослідження, не вже наявний код.

Для наступного етапу `whisper.cpp` має Android sample та timestamp output, але це не доказ точності української, швидкості або батарейних витрат на цільових пристроях. Модель може використовувати майбутній контекст; chunk входу теж має бути обмежений дозволеним інтервалом. [whisper.cpp](https://github.com/ggml-org/whisper.cpp), [Android sample](https://github.com/ggml-org/whisper.cpp/blob/master/examples/whisper.android/README.md).

**Слабший варіант:** одразу LLM-переказ і весь ASR книги. Це додає latency, витрати та ризик знань поза прослуханим. **Перевірка:** seek через непрослухану главу; повернення назад після retrieval; сегмент, що перетинає межу; restart; інша Edition; reorder; stale cache; маркери спойлерів після cutoff. Рандомізоване локальне порівняння вимірює manual seek після resume, зайвий повтор, latency і батарею; журнал не містить назви/тексту й не їде на сервер. Результати одного fixture не узагальнювати на всі книги.


## Каталог, рекомендації, відгуки та ідентичність

### #480 — подієвість, кеш, повний пул і чесна модель

**Що вже є.** Main має подієву policy, Room embedding cache, candidates з персистентного каталогу, runtime downloader і Source popularity. Однак `RecommendationModelPolicy.mode(Installed)` у main повертає FULL за самим станом файлів. Settings показує retry, але не cancel. Це видно в [policy main](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/data/recommend/RecommendationModelPolicy.kt), [installer](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/data/recommend/EmbeddingModelInstaller.kt) і [Settings](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/ui/screens/RecommendationSettingsScreen.kt). Старий коментар «лишився прогін на 10k+» застарів: прогін уже був і завершився NO-GO.

**Рішення.** Завершити чинний #487: статус E5/Keyword/NotLoaded походить від backend, який фактично обчислив прийнятий frame. Окремо додати явне скасування download із керованим закриттям stream, відсутністю публікації Installed після cancel, збереженням уже справних installed файлів та очищенням незавершеного `.part`. Простий `job.cancel()` без перевірки активності всередині блокуючого read-loop цього не доводить. Kotlin описує скасування як кооперативне: [офіційна документація](https://kotlinlang.org/docs/cancellation-and-timeouts.html).

**Слабша альтернатива.** Лише додати кнопку cancel або вважати файли доказом FULL. Це лишить неузгоджений UI й native loader failure. Заміна моделі або ваг не входить до цього залишку.

**Приймання.** Тик progress не породжує rank pass; одна пачка подій дає один перерахунок; cache живе після restart; FULL лише для фактичного E5 frame; cancel під час model/tokenizer transfer не встановлює неповний файл; повтор працює без стороннього background download. Жива тижнева оцінка — окремий результат власника, не заміна строгого offline gate. Залежність: #487, узгоджена межа cancel та живого користування.

### #487 — реальний eval і відсутність мовчазного fallback

**Поточний факт.** [Draft #1139](https://github.com/Samuel-Ku/slukhayka/pull/1139), HEAD `7614108f91f2f81eafdcd8da4e4e8955594b558c`. На живому CI всі ordinary checks успішні, окрім **Recommendation eval FAILURE**; Signed smoke APK SKIPPED. На 18 188 Works, 24 folds E5 і production keyword baseline мають recall@20=0 та NDCG@20=0. Не виконано умову strict semantic recall > baseline. [Актуальний заморожений звіт](https://github.com/Samuel-Ku/slukhayka/blob/7614108f91f2f81eafdcd8da4e4e8955594b558c/docs/recommend/experiments/2026-10-10-personal-interests-v5-main-ec19/EVAL-REPORT.md), [ADR-0062](https://github.com/Samuel-Ku/slukhayka/blob/7614108f91f2f81eafdcd8da4e4e8955594b558c/docs/adr/0062-real-catalog-recommendation-eval.md). Входи — заздалегідь обрана експертна бібліографічна вибірка, не історія завершень реальних слухачів.

Main EVAL-REPORT ще містить старий 140-книжковий GO із застереженням. Його не можна цитувати як поточний quality verdict. Розбіжність одного Mac/Linux raw inference call також не є доказом причини нульових ranks.

**Рішення.** Зберегти NO-GO, модель, thresholds, labels, freeze та архіви. Завершити чинний backend/UI scope: accepted frame об'єднує vectors/context/backend status, stale pass не міняє Home, runtime retry відокремлено від download, handle закривається після активного pass. Це вже кандидат [runtime policy](https://github.com/Samuel-Ku/slukhayka/blob/7614108f91f2f81eafdcd8da4e4e8955594b558c/app/src/main/java/com/slukhayka/audiobooks/data/recommend/RecommendationModelPolicy.kt); temporal/Compose/native acceptance все ще потрібне. Новий алгоритмічний експеримент — тільки окремий погоджений scope та preregistration. Його не запускати під виглядом повтору CI.

**Наступна діагностика — пропозиція, ще не виконана.** До нового quality-експерименту перевірити перетин holdout Work IDs з реально допустимим candidate pool, alias/mergeKey нормалізацію та exclusions. Окремим синтетичним perfect ranking перевірити scorer recall/NDCG, не підміняючи ним офіційний eval. Для E5 звірити tokenizer, truncation, prefix, attention mask, pooling і normalization; між платформами знайти першу розбіжність проміжного tensor на тому самому вході. Нулі обох моделей — привід для цих перевірок, але не доказ помилки метрики або моделі. Діагностика не змінює frozen inputs, звіт, thresholds або labels; новий запуск потребує свого погодженого обсягу.

**Первинне джерело.** [Офіційна E5 model card](https://huggingface.co/intfloat/multilingual-e5-small/raw/main/README.md) описує query prefix для symmetric similarity, masked mean pooling та normalization. Виконання цього контракту потрібне для коректного inference, але саме по собі не гарантує адекватних рекомендацій у Слухайці.

**Слабша альтернатива.** Послабити gate, вибрати labels після оцінок, підмінити semantic keyword результатом або оголосити GO за loader/unit tests. Це приховає невиконану вимогу. Повтор нескінченно одного freeze без нової гіпотези також не вирішує питання.

**Приймання.** Backend failure за installed assets дає явний SIMPLIFIED; Keyword vectors не потрапляють в E5 cache; delayed старий pass не повертає FULL; Home cards і mode публікуються разом; retry без download і без leaked native handle. Quality лишається відкритим до окремого strict GO на прийнятому протоколі. Потрібне рішення власника щодо нового експерименту або окремого розділення delivery чесного runtime-стану та невирішеної якості — без перейменування NO-GO на успіх.

### #521 — колективний каталог: є додаткові контрактні прогалини

**Що лишилося.** Більшість локальних building blocks доставлено. Відкриті діти #524/#525/#533. Але незалежна перевірка знайшла три речі, які старий checklist помилково дозволяє вважати завершеними:

1. **Lease тільки в одному процесі.** Main [App.kt](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/App.kt#L706) і draft #1162 підключають `InMemoryCollectiveRefreshLease`. Це не спільний lease між двома телефонами. Тести з двома callers одного in-memory lease не доводять один refresh у реальних установок.
2. **Ідентичність блока надто вузька.** [collectiveBlockKey](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/data/collective/CollectiveFeedBlock.kt) = Source+kind. [PR related producer](https://github.com/Samuel-Ku/slukhayka/blob/4a543dd8ad99ef4df1596ec006649d2b8e61511f/app/src/main/java/com/slukhayka/audiobooks/data/collective/CollectiveRelatedBlocks.kt) дає блок «До A» із provenance сторінки A, але відкриття B записує «До B» під той самий ключ. Спека вимагає Source+kind+canonical URL/ID блока, щоб змістовні добірки співіснували.
3. **Local freshness не захищає shared writer.** [FirestoreCollectiveBlockStore](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/data/collective/FirestoreCollectiveBlockStore.kt) виконує blind `.set`, має ID source-kind і cursor за клієнтським fetchedAt. [Rules](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/firestore.rules#L333) перевіряють форму, не новизну. Інша установка може пізніше переписати новіший remote block старішим observation; локальний `activateIfNewer` цього не виправляє.

**Рішення.** Завершити чинну вертикаль #1162→#533 та окремо погодити з координатором додаткові контрактні зміни; parent #521 не закривати до перевірки обох частин: стабільний block ID із canonical provenance, реальний shared lease з ownership token/fencing, guarded remote publication і server-ordered delta revision. `fetchedAt` лишити часом observation, а не довіреним clock конфліктів. Публікувати лише public факт, не query/uid/cookie/audio. Offline або Firestore miss лишає last-good Room; не робити TTL-refresh без отриманого shared lease. Важлива деталь: [Firestore transactions](https://firebase.google.com/docs/firestore/manage-data/transactions) атомарні, можуть повторити callback і відмовляють offline; записувати побічні UI/Source дії зсередини callback не можна.

**Слабша альтернатива.** SharedPreferences lease, більший local TTL або випадковий jitter. Вони можуть зменшити трафік одного телефона, але не гарантують одного власника. Один RECOMMENDATIONS-slot придатний лише для явно звуженої продуктової обіцянки «остання добірка джерела»; зараз такого рішення немає.

**Приймання.** Два незалежні AVD одночасно refresh stale same block → один Source request; expiry старого owner не дозволяє йому overwrite нового; slow older observation не відсуває server cursor; A/B related blocks співіснують; restart/failure зберігає last-good. Квотний бюджет рахується за числом Firestore operations, а не за припущенням «безкоштовно»: [офіційні квоти](https://firebase.google.com/docs/firestore/quotas) — 50 000 reads і 20 000 writes на день у free tier; TTL deletion не має безкоштовного використання. Це аргумент за bounded cursors і логічний expiry, не за додатковий crawler.

### #524 — SluhayUA search → звук → shared delta

**Поточний факт.** [#1162](https://github.com/Samuel-Ku/slukhayka/pull/1162), HEAD `4a543dd8ad99ef4df1596ec006649d2b8e61511f`, draft, ordinary CI має Accessibility journey FAILURE. [Search adapter](https://github.com/Samuel-Ku/slukhayka/blob/4a543dd8ad99ef4df1596ec006649d2b8e61511f/app/src/main/java/com/slukhayka/audiobooks/data/source/SluhayuaAdapter.kt) відрізняє реальне `cards:[]` від malformed/unavailable endpoint. Порожній successful search не запускає feed. Автоматичний пошук по всіх дозволених джерелах збережено. Лишається production startup/дві установки/звук, а не повторна реалізація parser.

**Рішення.** Зберегти чинний контракт: Room/shared перед Source, одна явна search action — максимум один SluhayUA JSON search; open/Play резолвить тільки вибрану книгу. Profile лише після clean probe та фактичного playback. Завершити CI-діагностику в тому самому PR, потім перейти до #533 на pinned APK. Не міняти global automatic search scope без окремого рішення.

**Слабша альтернатива.** Feed scan після чесного empty, відкривання всіх search results, публікація профілю після HTML/HTTP200. Усі три дають зайві запити або неправдивий playable verdict.

**Приймання.** A робить explicit query, записує public card без query text; B отримує її shared delta, робить нуль search/feed calls; Play B відкриває exact source book, chapters та правильний звук із title/chapter/request count. Source/Firebase miss має локальний fallback. Endpoint live shape не перевірявся новим запитом у цьому дослідженні; треба перевірити на стенді, не вважати старий spike гарантією.

### #525 — спостережені новинки й related-блоки на Огляді

**Поточний факт.** У #1162 є producers з уже отриманих arrivals/detail, persisted Overview reader, atomic newer admission. [CollectiveBlockSync](https://github.com/Samuel-Ku/slukhayka/blob/4a543dd8ad99ef4df1596ec006649d2b8e61511f/app/src/main/java/com/slukhayka/audiobooks/data/collective/CollectiveBlockSync.kt) тепер rethrows cancellation локального admission до cursor save. Source comments заявляють 291 targeted tests попереднього HEAD і 6/6 останнього cancellation regression. Це не live backend acceptance.

**Рішення.** Прийняти звичайний startup/Home, отримання B через backend і відмови. Окремо узгодити блоки за canonical URL з #521: нинішній слот «До A»→«До B» не зберігає дві різні related-добірки. Не переписувати parser і не fetch-ити detail за всіма cards. Стару delta та expired owner відсіювати атомарно; failed/empty/missing ID зберігає last-good.

**Слабша альтернатива.** Домалювати блок напряму з transient feed у UI або збирати detail crawler для related. Перше не переживає restart/іншу установку, друге порушує demand/budget.

**Приймання.** Source label, name, provenance, order, dedup усередині; повтор у різних змістовних блоках дозволений; одна live response використовується без додаткового Source call; B/Home реагує на Room commit і не робить feed; cancellation не просуває cursor; дві asynchronous remote versions лишають новішу. Поточний accessibility failure не називати flaky до встановлення причини. Diagnostics compiler success не є виправленням падіння.

### #533 — приймання двома установками, fallback і звук

**Поточний факт.** Issue comments містять уже доставлені streaming/local/download/resume/Lihtar checks та counters (#1040–#1047). Повторна реалізація counters/downloads зайва. Поточне резервування фіксує відмову стенда до запуску App: навіть із guest RAM 2GB поточний emulator потребував 7372MB ресурсу. Це стан стенда, не доказ application failure чи success.

**Рішення.** Спочатку один придатний стенд. Перевірити host ресурси, pinned emulator/system image та фактичний boot/install до запуску costly acceptance. Уживати дві окремі AVD із незалежним data dir; не один emulator із shared database. [Android documentation](https://developer.android.com/studio/run/emulator) підтверджує окреме сховище кожної AVD та рекомендує 16GB host RAM для emulator experience. Конкретні 7372MB — фактична вимога поточного стенда, не універсальна Android межа. Якщо місцевий host непридатний, погодити інший host або два тестові фізичні пристрої; це окрема адаптація стенда. Q1 runbook для #620 дозволяє лише disposable emulator, його не переносити мовчки на особистий телефон.

**Слабша альтернатива.** Re-run того самого failed boot із меншим guest RAM; дві тестові ViewModel в одному процесі; прийняття тільки за PLAYING. Жодне не доводить дві установки та правильний звук.

**Приймання.** Pinned source HEAD/APK SHA; A publishes → B receives; B має нуль feed/search requests, Play робить exact selected resolve; керована відмова first Source веде на ту саму Edition зі збереженою chapter/position; інша начитка потребує підтвердження; людський запис «чув назву X, chapter Y»; trace cache/follower/deferred/fetched для Source gate і raw transport. Уже доставлені local/resume/counters доказувати з посиланнями, не автоматично скидати всі checkbox-и. Залежності: #1162 CI+інтеграція, стенд, реальний listener для sound AC; виявлені shared lease gaps мають окремий explicit verdict.

### #620 — lifecycle відгуків і реальна SDK persistence

**Поточний факт.** Catalog session, FeedSnapshotRefresh і ListenerReviewLifecycle вже в main. [#1181](https://github.com/Samuel-Ku/slukhayka/pull/1181), HEAD `4d0660c994540f6da52fee5c7362ad3204d4a20f`, має 16 CI success/1 skipped. [Runbook](https://github.com/Samuel-Ku/slukhayka/blob/4d0660c994540f6da52fee5c7362ad3204d4a20f/docs/runbooks/review-persistence-acceptance.md) чесно каже: 117 JVM і 1 SDK36 cancellation test, compilation та fixture rules перевірки не доводять SDK ACK/restart/reconnect. CLI має creation/edit ACK/rejection; DELETE ще не є його режимом.

**Рішення.** Використати вже підготовлений ізольований `demo-slukhayka-acceptance`, SDK persistent queue та окремий confirmed snapshot. Не створювати другу довільну outbox-чергу замість SDK. Спочатку creation/edit automatic reconciliation; далі exact failed-payload retry та queued DELETE із late ACK ordering. Published відрізняти від accepted local enqueue. [Firestore offline docs](https://firebase.google.com/docs/firestore/manage-data/enable-offline) пояснюють persistent Android cache та reconnect sync. [SnapshotMetadata](https://firebase.google.com/docs/reference/android/com/google/firebase/firestore/SnapshotMetadata) відрізняє pending local writes і cache від server truth. [waitForPendingWrites API](https://firebase.google.com/docs/reference/android/com/google/firebase/firestore/FirebaseFirestore) — межа drain, але payload/metadata серверного read все одно треба звірити.

**Слабша альтернатива.** Mock store або recreated ViewModel без смерті процесу; toast після `.set()` як ACK; cached rating як confirmed average. Це повторить наявний недостатній доказ.

**Приймання.** Seed точного server DTO → offline queue → force-stop з іншим PID → reconnect; complete payload після ACK; rejection автоматично FAILED із exact retry draft, попередній confirmed/average збережений; retry не перетирає newer draft; queued DELETE переживає restart і late old save не воскресає review. Work/uid epoch змінюється без витоку pending. Opt-in test runner не запускає production App: його результат не видавати за startup/Home або production security-rules acceptance. Залежність: слот стенда, не додаткове архітектурне переписування.

### #968 — злиття дублів без втрати місця слухання

**Поточний факт.** [Draft #1161](https://github.com/Samuel-Ku/slukhayka/pull/1161), HEAD `43dc9eea5cd21a00ea8c8b34e02f69df704a3b8c`, only documentation; CI 16 success/1 skipped, production migration/D2 не стартували. [D1 контракт](https://github.com/Samuel-Ku/slukhayka/blob/43dc9eea5cd21a00ea8c8b34e02f69df704a3b8c/docs/specs/2026-10-07-manual-work-identity-merge.md) вже покриває 45 таблиць v54, completion/abandon notes та achievement facts. [Чинний DuplicateWorkMerger](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/data/merge/DuplicateWorkMerger.kt) автоматично зливає лише доведені remote duplicates зі strict proofs. Його не можна послабити до same display name.

**Рішення.** Прийняти підготовлений ручний **Work-only** merge: preview «цей самий твір», бібліографічний canonical Work + typed redirects, усі Edition/Source/bookId/позиції/bookmarks/files зберігають IDs і лишаються окремими. Atomic apply з re-read/fingerprint, refuse за active writers/tombstone, idempotent operation journal, conditional Undo, durable startup exclusions також після Undo. Це об'єднує одну картку твору без злиття двох різних прослуховувань. Display-only person name repair лишається parked.

**Три рішення власника вже підготовлені, не треба відкривати дослідження заново.** (1) Прийняти ручний Work-only rule і exclusions; (2) явний вибір incompatible fields та confirmed-review dedup, active jobs/tombstones блокують apply; (3) окремий atomic person closure/aliases/outbox repair після Work resolver. Перший delivery локальний; cross-device redirects потребують іншого scope.

**Слабша альтернатива.** Автоматично same-title+author → один Edition із найбільшим або найновішим progress. Можна втратити окрему начитку, файли й слухацький намір. Ремонт тільки текстових імен робить гарніші назви, але не перебудовує bookmarks/facets/FTS/person aliases. Сам `foreign_key_check` недостатній: більшість доменних зв'язків не оголошені FK.

**Приймання наступної реалізації.** Room v54 all-table closure до/після apply/reopen/retry/Undo; exact ID sets і raw positions двох Editions; files/download queue недоторкані; post-merge new progress/completion не відкотиться старим Undo; startup merger не зливає захищених учасників; третій canonical Work викликає новий preview, не hidden merge; person repair all derived identities атомарно. [Room migration docs](https://developer.android.com/training/data-storage/room/migrating-db-versions) підтримують перевірку міграцій; destructive fallback видаляє дані й тут неприйнятний. CI документації не є доказом жодного із цих runtime AC.

### #1226 — DELETE: refresh перед backend ACK повертає відгук

**Перевірений факт.** [Issue #1226](https://github.com/Samuel-Ku/slukhayka/issues/1226) доданий до фінального переліку. Static читання [ListenerReviewLifecycle у поточному #1181](https://github.com/Samuel-Ku/slukhayka/blob/4d0660c994540f6da52fee5c7362ad3204d4a20f/app/src/main/java/com/slukhayka/audiobooks/data/reviews/ListenerReviewLifecycle.kt#L693) підтверджує: success DELETE прибирає `deleting`, але лишає `current.confirmed` незмінним. Порядок local accepted DELETE → refresh із старим server snapshot → ACK успіху може повернути стару картку й голос у headline. Тут немає нового runtime-відтворення; це причинний висновок із коду, який треба зафіксувати RED тестом.

**Рекомендоване вузьке рішення.** У чинній success-гілці ordered commit прибрати exact documentId із **поточного** confirmed списку разом із видаленням pending-delete overlay. Не відновлювати captured baseline як увесь список. Зберегти наявні submission-generation і Work/uid epoch guards; failure лишає restore поведінку. Додатковий UI refresh після ACK не має бути умовою коректності.

**Слабша альтернатива.** Викликати refresh у UI або тримати permanent delete tombstone. Перше приховує гонку й залежить від мережі; друге блокує законний новий save після delete та суперечить lifecycle.

**Public-interface тест.** Керований store тримає DELETE ACK незавершеним. Через lifecycle public API отримати initial confirmed; enqueue delete і перевірити QUEUED/невидиму картку; завершити refresh старим confirmed; успішно завершити ACK. Без нового refresh `confirmed`, `visible` і headline projection більше не містять голосу. Test має бути RED на `4d0660c9`. Контролі: rejection restore, refresh-after-ACK, save→delete→newer save, Work switch та uid switch відсіюють late callback. Цільові тести не видавати за SDK process-death/backend acceptance #620.

**Пріоритет і залежність.** P1, середній ризик, локальна зміна в одному lifecycle. Власного claim #1226 немає, але parent #620 зайнятий: fix виконує чинний owner або конкретно призначений ним виконавець; другого автора в цих файлах не запускати. Залежність від нових issues не потрібна.



## Інструменти перевірки й рішення великих екранів

### #1219 — мапа партицій

[Issue](https://github.com/Samuel-Ku/slukhayka/issues/1219).

**Факт.** На актуальній main selector для `ui/components/PlayerPane.kt` повертає чотири класи: CoverPlaceholderContrastTest, PosterCardReasonSlotGuardTest, MetadataChipTest, SpeedSheetTest. Сто класів у точному батьківському пакеті `ui`, зокрема WidePlayerPaneTest і AdaptiveNavigationLayoutTest, не вибрані. `web/src/theme.css` повертає `fullSuite: true` з причиною `unmapped changed path`. Shell runner розуміє лише шість Android-партицій. [Код selector](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/scripts/test_partitions.py#L169), [runner](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/scripts/test-changed.sh).

**Рішення.** Зберегти вибір тестів зміненого пакета/його підпакетів, потім додавати тести **точного** кожного пакета-предка до кореня доменного пакета. Не брати всі підпакети кожного предка: на `com.slukhayka.audiobooks` це перетвориться на повний набір. Відсутніх предків показувати у JSON як `uncoveredAncestors`; мапа покриття — правило добору, не доказ поведінки. Додати незалежну ціль `web` у JSON і runner: Node 22, `npm ci`, `npm run typecheck`, `npm test`, зупинка на першій помилці. Mixed diff має об'єднувати обидва клієнти. Shared infrastructure теж повинна зберігати web-ціль, якщо зачеплено її; Android fullSuite не замінює web. Статус `fullSuite` варто явно зробити Android-специфічним або розкласти на цілі.

**Альтернатива.** Вручну додати два широкі класи. Це прибере один пропуск, але наступний предок знову випаде. Вибирати весь Android набір для кожного web diff — безпечніше для Android, але не перевіряє web і витрачає слот. `npm ci` має відповідати lockfile і не оновлює його; це потрібна властивість відтворюваного runner. [npm](https://docs.npmjs.com/cli/commands/npm-ci/).

**Перевірка.** Host-фікстури з тестами в child, parent і grandparent; порожній предок; змішаний Android/web diff; web lock/config/worker/css; shared script + web; unmapped path. Stub runner доводить, що web-only не запускає Gradle, а помилка typecheck не обходиться. Мутації nearest-only та web→Android-fullSuite мають валити перевірку. Узгодити AGENTS: виконання вибраних класів і виконання цілої партиції сьогодні описані неоднозначно. Не заявляти, що package heuristic доводить повноту всіх залежностей.

**Залежності / пріоритет / ризик.** Без зовнішнього блокера; #1225 залежить від цього входу. **P1**: сьогодні інструмент дає неповне локальне покриття. Ризик середній: надто широкий обхід поверне дорогий fullSuite, надто вузький залишить пропуски.

### #1220 — мердж-гейт

[Issue](https://github.com/Samuel-Ku/slukhayka/issues/1220).

**Факт.** Немає `scripts/merge-pr.sh`. Live API повернув `Branch not protected` для classic protection **і** порожній масив effective `/rules/branches/main`; це підтверджує відсутність серверного гейта, а не тільки старої форми protection. Коментар власника до #1205 прямо фіксує злиття #1215 попри Spec FAIL. [Свідчення](https://github.com/Samuel-Ku/slukhayka/issues/1205#issuecomment-6101508735).

**Рішення.** Скрипт відмовляє за замовчуванням: відкритий non-draft PR, актуальний head/base, усі очікувані перевірки завершені успішно, немає blocking findings; мітка `review:pass` є, але **свідчення огляду прив'язане до того самого head SHA**. Мітка сама не має такої прив'язки: після push старий pass може пережити зміну. Додати структурований receipt оглядача або check-run з head SHA; на synchronize pass анулювати. Pending, cancelled, timed-out, missing checks і невизначений mergeability не вважати дозволом. Перед merge повторно звірити голову і викликати лише `gh pr merge --squash --match-head-commit <SHA>`; без `--admin`, `--auto`, `--delete-branch`. Відмова називає конкретний чек / відсутній або застарілий огляд і наступний крок. [CLI merge](https://cli.github.com/manual/gh_pr_merge), [CLI checks](https://cli.github.com/manual/gh_pr_checks).

**Сильніший захист.** Власнику варто окремо ухвалити ruleset для main: PR-only, required CI + verdict-check, актуальна база і застарівання review після нового push. Локальний скрипт обходиться кнопкою UI або прямим gh. Native approval доступна лише якщо є окремий reviewer account; кілька агентів з одним GitHub-акаунтом не перетворюються на незалежних GitHub-reviewers. Для цього процесу практичний носій — check-run/receipt з SHA та явним протоколом оглядача. [Rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets).

**Альтернатива.** Лише `review:pass` плюс «нема fail». Дешево, але пропускає pending/порожню матрицю і застарілу мітку. Серверний ruleset без script покриває enforcement, але не дає потрібної локальної діагностики і рецепта squash.

**Перевірка.** Recorded API fixtures: історичний #1215 із незакритим FAIL; нема pass; pass на старій голові; після pass з'явився fail; queued/нетиповий статус; пустий список; race зі зміною SHA. Stub gh доводить, що mutation merge запускається рівно один раз лише в дозволеному випадку, з squash і match-head, без видалення гілки.

**Залежності / пріоритет / ризик.** **P1, першим серед процесних питань**: уже був дефект злиття. Власник визначає носій verdict і чи встановлює серверні правила. Ризик високий для наївного рішення: зелене CI не дорівнює закритим findings, а локальний wrapper сам main не захищає.

### #1221 — слід мутацій

[Issue](https://github.com/Samuel-Ku/slukhayka/issues/1221).

**Факт.** Немає mutate runner або машиночитного журналу. Звичайні і мутаційні запуски пишуть у спільні Gradle result dirs. Лише mutation receipt не відповість, чи пізніший прямий Gradle-run був справжнім: треба облік **обох** видів запусків. [Наявний test runner](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/scripts/test-all.sh).

**Рішення.** Лишити CLI `mutate.sh --patch … --class …`, але застосовувати patch в ізольованому detached worktree з того самого HEAD. Оригінальне дерево перевірити на tracked/untracked зміни і не торкатися його. Перед запуском — `git apply --check`, patch-hash, class→partition через чинний selector, дозвіл власника спільного Gradle-слота. Окремі build/results dirs; `--rerun-tasks`. У `.mutations/` зберігати атомарний JSON receipt: runId, kind, HEAD/tree/patch hash, touched files, FQCN, task/command, UTC start/end, exit, test counts, статус `killed|survived|invalid|infra_error|interrupted`, результат cleanup. [Git apply](https://git-scm.com/docs/git-apply), [Git worktree](https://git-scm.com/docs/git-worktree).

**Межа гарантії.** Trap обробляє EXIT/INT/TERM/HUP, але SIGKILL або зникнення живлення не можна перехопити. Ізоляція гарантує, що робоче дерево автора не залишиться мутованим; після аварії може лишитися scratch worktree і незавершений receipt. Наступний запуск показує його як interrupted і дає адресний cleanup. Це чесніше, ніж обіцянка «гарантовано відкотити» in-place. [GNU signals](https://www.gnu.org/s/coreutils/manual/html_node/Signal-specifications.html).

**Облік справжніх прогонів.** Спільний recorder для test-changed/test-all і mutation runner записує `kind=real|mutation`. Latest query фільтрує по класу та ревізії, показує interrupted і чесне `unknown` для запусків повз recorder. Вдалий компілятор без виконаних тестів не є survived; compile error не є killed. Бажано мати зелений baseline класу на тому самому HEAD і фінальний real-run після мутацій.

**Альтернатива.** Накласти patch у робочому дереві, trap→reverse patch. Простий шлях, але небезпечний при SIGKILL і сторонніх правках. PIT/повний mutation engine — інша задача: issue автоматизує облік іменованих експериментів, не генерує мутації.

**Перевірка.** Малі sandbox-repo із fake runner: pass/fail/compile failure/zero tests/TERM/kill; грязне дерево; patch не накладається; interrupted ledger; ідемпотентний cleanup; оригінальні файли побайтно незмінні. Не потрібна повна Gradle-матриця.

**Залежності / пріоритет / ризик.** Зручно після #1219 через class→partition. **P2**. Ризик високий для in-place відкату, середній для isolated runner; потребує єдиного журналу і дотримання build slot.

### #1222 — lint-baseline

[Issue](https://github.com/Samuel-Ku/slukhayka/issues/1222).

**Факт.** XML main містить **1313 issue-записів**, із них **1083 MissingTranslation**. CI перевіряє machine paths і запускає lint, але не звіряє зростання suppression list з базою. [CI](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/.github/workflows/ci.yml#L128), [baseline](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/lint-baseline.xml).

**Рішення.** Host Python XML-checker порівнює PR-head з точним base SHA події, а не з випадковим локальним origin/main. CI має fetch потрібного ref. Друкує total і розклад по rule, MissingTranslation окремо; **FAIL на прирості total або будь-якого rule**. Відсутній ref чи malformed XML — FAIL, не нуль. Окремо показувати нові suppression identities: зменшення одного правила не має ховати додавання іншого. Machine-path guard #1196 лишається.

Генератор бере **конкретний** запис із lint XML, копіює повідомлення/errorLine/location, нормалізує дозволений шлях від кореня app module, відхиляє absolute/cache/worktree/поза-module шляхи, вставляє стабільно й атомарно, без дублів. Зберігає metadata кореня baseline та XML escaping. Не регенерує весь baseline автоматично. Не робить масового перекладу і не скасовує польську локаль. Android описує baseline саме як борг, після якого падають нові проблеми; ручне розширення змінює цей бар'єр. [Android lint](https://developer.android.com/studio/write/lint).

**Альтернатива.** Warning-only залишить попередню можливість злиття; лише total пропустить заміну одного придушення іншим. Повний updateLintBaseline заново захопить усі порушення і може тихо узаконити новий борг.

**Перевірка.** XML-фікстури: unchanged, decrease, +MissingTranslation, +інше правило, total unchanged але один rule зростає, bad XML/ref, дубль генератора, escaping, deterministic order, forbidden path. Реальний lint потрібен для майбутньої перевірки сумісності сформованого запису, але не для host-тесту порівнювача.

**Залежності / пріоритет / ризик.** **P2**; рішення issue FAIL vs warning запропоновано як FAIL, остаточно назвати в PR. Окреме питання польської локалі лишається за власником. Ризик середній: AGP-upgrade може змінити набір lint-правил і вимагати прозорого окремого перегляду baseline.

### #1223 — спільна межа Android/Web

[Issue](https://github.com/Samuel-Ku/slukhayka/issues/1223).

**Факт.** Android `ExpandedMinWidthDp=600`, TS `EXPANDED_MIN_WIDTH_PX=600`, тест пінить `.toBe(600)` і не читає Kotlin. Тому зміна Android лишає web test зеленим. [Kotlin](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/ui/adaptive/WindowLayout.kt#L30), [TS test](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/web/src/ui/adaptiveLayout.test.tsx#L56).

**Рішення.** Невеликий Node-environment conformance test читає Kotlin-файл від шляху відносно `import.meta.url`, прибирає comments, вимагає рівно одну `const val ExpandedMinWidthDp` з literal numeric value; звіряє з імпортованою production TS-константою. У разі file missing або ambiguous declaration — зрозумілий FAIL. Коментар вказує ADR-0034 як продуктову причину єдиного порога; behavioural tests беруть boundary−1/boundary, не третій literal. Буквальні 599/600 можуть залишитись окремою історичною UX-фікстурою, але не є джерелом parity.

**Альтернатива.** JSON-контракт із генерацією Kotlin/TS усуне дубль фізично, але вводить build generation заради одного числа. До нього варто повертатися, якщо спільних констант стане багато. Другий literal test — поточний дефект.

**Перевірка.** Окремі mutation cases для Android і TS; missing/moved file; commented-out const не задовольняє guard; duplicate declaration; узгоджена зміна обох боків проходить parity і boundary behaviour. Node-тест не потребує jsdom CSS layout.

**Залежності / пріоритет / ризик.** **P2**, мала робота без блокерів. Ризик низький; source-parsing треба зробити fail-closed, без нечіткого first-match. Це guard погодженого контракту, не доказ тотожності dp/px за всіх density/zoom.

### #1224 — браузерні виміри web

[Issue](https://github.com/Samuel-Ku/slukhayka/issues/1224).

**Факт.** Main вже має web-ширину/сітку #1218. Package не має браузерної залежності; CI web запускає лише npm ci, typecheck, vitest. Adaptive test чесно визнає, що jsdom не вимірює CSS. Це залишається відкритим після merge #1218. [Package](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/web/package.json), [тест](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/web/src/ui/adaptiveLayout.test.tsx).

**Рішення.** `scripts/web-layout-probe.mjs` із припіненим Playwright + його Chromium, без самописного CDP-клієнта. Якщо контракт буквально вимагає CDP — Chromium CDPSession для Runtime.evaluate; інші виміри простіше через page.evaluate. На each revision: npm ci/build, loopback preview на вільному port, fresh browser context, фіксовані seeded дані та явна готовність UI. Ніяких live catalog запитів: однакова фікстура для base/head. Vite preview підходить для локальної перевірки production bundle. [Vite](https://vite.dev/guide/static-deploy.html), [Playwright browser](https://playwright.dev/docs/api/class-browsertype), [Playwright page](https://playwright.dev/docs/api/class-page).

JSON/table має містити revision, browser version, viewport width/height/deviceScaleFactor, actual innerWidth, data-layout, app/container widths, rendered grid tracks, row/card bounds і horizontal overflow. Один element scrollWidth test недостатній: перевіряти document та relevant scroll containers і clipping. Пройти реальні grid-типи/довгі назви; fixtures мають достатньо елементів, щоб число колонок було видимим. Порівняння `--base <ref> --head <ref>` будує **два** незалежні знімки, з їхніми lockfiles; compact metrics повинні збігатися в tolerance, expanded delta має бути явною.

Початкова матриця: boundary−1 / boundary / boundary+1 для 600, compact phones і широке вікно біля переходів кількості колонок; точні width/height — судження автора сценарію, не універсальна гарантія. `finally`/signal cleanup закриває browser/preview і scratch; force-kill може лишити scratch, але не мутоване дерево. Browser-місце CI потребує install/cache Chromium і окремого opt-in step; один локальний report сам не стає CI gate.

**Альтернатива.** CSS regex у vitest ловить видалений текст, але не cascade/layout/overflow. Ad-hoc Chrome через невідомий executable має дрейф browser version і повторює одноразові /tmp-щупи. Повний screenshot regression корисний додатково, але не замінює числа.

**Перевірка.** Мутації прибрати grid selector, повернути max-width640, зламати min column, створити overflow; report мусить змінитися або guard впасти. Спроба signal interruption і відсутність leaked processes; base=head дає нульову дельту; різні refs не можуть випадково виміряти той самий dist.

**Залежності / пріоритет / ризик.** **P2**, але обов’язкова передумова для подальших CSS claims, без blocker; doc hook узгодити з #1225, selector optional після #1219. Ризик середній: flaky data/fonts і браузерні процеси; deterministic fixtures і browser pin його зменшують. Safari лишається окремим пристрійним reference ADR-0034.

### #1225 — local-environment

[Issue](https://github.com/Samuel-Ku/slukhayka/issues/1225).

**Факт.** Немає wide-window.sh, web-local recipe і native Room cohort guidance. Додатково local-environment каже «видалення claim звільняє тікет», тоді як work-coordination дозволяє передачу/зняття лише координатору після перевірки. AGENTS пише `testRoomRoBolectricOnly`, реальна task — `testRoomRobolectricOnly`. Офлайновий slot wrapper `.worktrees/gt` описаний як машинний факт, але в цьому доступному checkout його немає; не варто обіцяти runnable command без перевірки. [Документ](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/agents/local-environment.md), [coordination](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/agents/work-coordination.md), [AGENTS](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/AGENTS.md).

**Рішення.** Формула `density = 160 * active_width_px / target_dp`, integer rounding із запасом для target нижньої межі. Скрипт читає serial/display/rotation та effective wm-size override, друкує current/previous density, applied density, computed dp; після застосування перевіряє **Activity window configuration**, а не видає display arithmetic за screenWidthDp. Insets/multi-window можуть змінити фактичну ширину вікна. На 1440 px density280 дає 822.86dp; 270 — 853.33dp. Це арифметика, не вимір пристрою. Додати dry-run; зрозумілі помилки multiple/no devices; нагадування `wm density reset` і точну команду відновлення попереднього override. [Density](https://developer.android.com/training/multiscreen/screendensities), [AOSP wm shell](https://android.googlesource.com/platform/frameworks/base/+/1e078de/services/core/java/com/android/server/wm/WindowManagerShellCommand.java).

Web-розділ посилається на runner #1219; CSS — на probe #1224. Опис native Room вказує локально перевірену причину недоступності та CI replacement, без заяви що всі Room-тести принципово неможливі на macOS. Причину локальної недоступності, наприклад відсутній artifact або непідтриманий binary, треба підтвердити окремо; Gradle тут не запускався. Коли вибрана native cohort локально unavailable, runner друкує її як **неперевірену**, і чекають відповідний зелений CI. Не замінювати native Room тест звичайним JVM як «еквівалентом».

**Альтернатива.** Нове magic density270 працюватиме лише на конкретній геометрії; ручний web npm рецепт стане другим authority поряд з map; blanket «Room не працює локально» не пояснює межі. Виправити документаційні суперечності одним носієм: coordination owns claims/build ownership, scripts own commands.

**Перевірка.** Mock adb outputs: physical/override sizes, rotation, density override, no/multiple devices, exact boundary rounding; applied-vs-reported configuration mismatch. Локальна пристрійна дельта робиться лише коли є пристрій, потім restore; сьогодні вона не виконана. Перевірити runnable doc commands і всі task names без важкої збірки.

**Залежності / пріоритет / ризик.** #1219 для web, #1224 для CSS recipe; **P2**. Ризик середній: невірний фактичний dp може дати хибне «панель перевірена». Продуктових рішень не треба; документація не дозволяє ігнорувати slot/claims.

### #1208 — рішення власника великих екранів

[Issue](https://github.com/Samuel-Ku/slukhayka/issues/1208).

**Актуалізація.** Це вже **не шість невирішених питань**. Власник у #1205 ухвалив: міні-плеєр у провідній колонці; плеєр панеллю від 840 праворуч **замість** book detail, нижче 840 fullscreen; height-гейта для rail/panes нема. Другий коментар уточнив, що detail не малюється при відкритому player і панель застосовується до будь-яких pushed routes. Implementation #1215/#1217 завершена, device delta лишалась за власником. [Рішення](https://github.com/Samuel-Ku/slukhayka/issues/1205#issuecomment-6100145137), [уточнення](https://github.com/Samuel-Ku/slukhayka/issues/1205#issuecomment-6101129948), [закриття](https://github.com/Samuel-Ku/slukhayka/issues/1205#issuecomment-6102756448).

**Що лишилось вирішити.** (1) рейтинг і author/narrator lists в Огляді — чи додаються до двопанельної поверхні; (2) series/author route з книги — третя панель чи поточне заміщення; (3) empty-right у спокої — чи малювати заглушку, з якого порога. Першу письмову розбіжність Material3Adaptive→власний ui/adaptive не закрито: стара wayfinder-документація досі каже Adopt. Другу розбіжність Android/Web **частково пояснено** в актуальному §11: #1207/#1218 дали ширину/сітку, rail/two-pane лишено наступній хвилі; потрібні конкретний carrier issue і межа завершення. [Стара рекомендація](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/wayfinder/research/adaptive-two-pane.md), [оновлений §11](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/specs/2026-09-16-app-information-architecture.md#L399).

**Рекомендація для рішення, не прийнята за власника.** Зафіксувати питання 3/5/6 як вирішені посиланнями; для решти підтримати поточне обмеження рейтингу/people до single-pane, заміщення без третьої панелі, без порожньої правої колонки. Порожня колонка без дії забирає корисну ширину; третя панель ускладнює Back і на 600–839 вже не має бюджету. Якщо власник хоче розширити список+detail на rating/people — це окрема мала delivery-задача із restore list scroll/Back/resizing, а не автоматичне поширення.

Custom ui/adaptive лишити для поточних доведених контрактів; старий GO позначити superseded з причинним посиланням, міграцію на Material3Adaptive планувати лише при конкретній потребі fold/hinge posture або adaptive navigation, не для одного порога. Android офіційно радить window classes і враховувати compact height для двох панелей; поточне no-height рішення власника — свідома продуктова відмінність, його не можна тихо замінити загальною рекомендацією. [Android adaptive](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes).

**Альтернативи.** Поширити two-pane на всі listings — більший паритет, але потребує слова власника і сценаріїв повернення. Третя pane/empty placeholder може бути корисна на дуже широкому desktop, але це новий scope. Повна міграція scaffolds зараз переробляє працюючий код і Back заради документаційного боргу.

**Перевірка завершення.** Письмове owner decision для всіх трьох залишків; три вже відповіли — cross-reference, не повторне голосування. Superseded-mark старого рішення і конкретний web carrier. Якщо змінюється UI — boundary snapshots 600/840, short landscape 800×360 та 905×411, resize, Back/focus і visible device delta ADR-0017. Decision-only #1208 може завершитись записом відповідей, а видимі зміни — окремими delivery issues.

**Залежності / пріоритет / ризик.** **P2**, **потрібні рішення власника**, реалізацію не починати на припущенні. Ризик середній: застарілий audit може змусити повторно вирішувати вже ухвалене або видавати наступну web-хвилю за завершену.


### #1227 — унікальні номери ADR

[Issue](https://github.com/Samuel-Ku/slukhayka/issues/1227).

**Факт.** На pinned main немає окремого duplicate-ADR гейта. Історична колізія 0061 між #1139/#1186 пояснює потребу, але вже не є поточною: live HEAD #1139 `7614108f` має канонічне recommendation ADR 0062 **і** файл 0061 як історичний redirect («не окреме архітектурне рішення»), що зберігає frozen links. Обидва файли added у diff, тому filename-only guard помилково рахує redirect другим рішенням. [Historical redirect](https://github.com/Samuel-Ku/slukhayka/blob/7614108f91f2f81eafdcd8da4e4e8955594b558c/docs/adr/0061-real-catalog-recommendation-eval.md). [Новий ADR #1139](https://github.com/Samuel-Ku/slukhayka/blob/7614108f91f2f81eafdcd8da4e4e8955594b558c/docs/adr/0062-real-catalog-recommendation-eval.md), [PR #1186](https://github.com/Samuel-Ku/slukhayka/pull/1186).

**Рішення.** Малий host-checker читає ADR **зі Git tree**, розрізняє канонічне рішення й historical redirect. Для redirects додати явні machine-readable `kind: historical-reference` і `target`, перевіряти існування канонічної цілі й відсутність циклу; звичайні accepted/proposed/superseded рішення рахувати за numeric prefix. Два канонічні рішення одного номера дають FAIL і всі paths. Redirect не має права містити власне нормативне рішення. Gaps дозволені. Без такого явного контракту нинішній redirect треба показати як потребу уточнення, не автоматично пропускати довільний текст «історичний». CI бере live base SHA та exact PR head, робить `git merge-tree --write-tree` у scratch object store/clone, потім читає resultant tree через ls-tree/show. Merge-tree не змінює working tree/index, але створює Git objects: не називати його буквально zero-write. Якщо live base змінилася після snapshot — candidate stale, не PASS. Existing base-sync guard має іншу роль: він вимагає fresh branch; ADR-check звіряє фінальне дерево. [Git merge-tree](https://git-scm.com/docs/git-merge-tree), [наявний base guard](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/scripts/check-base-sync.sh).

Локальний read-only режим через paginated API порівнює **нові рішення в head trees** кожного PR із його base, друкує PR numbers + exact SHA + conflict paths. Перенесення рішення 0061→0062 зі збереженим historical redirect не є новою колізією рішень 0061. Fetch/errors/merge conflicts дають `incomplete`, а не «0 дублів». Номери чужих ADR скрипт не змінює. Поточний список чужих PR не робити обов'язковою мережею для pure CI tree-check: це advisory попередження, фактичний merge tree — blocker. Для Actions врахувати, що default pull_request checkout є synthetic merge події і може застаріти щодо актуальної main. [Actions event](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows).

**Альтернатива.** `find|sort` по локальному head не бачить колізії з новою main; filename-only правило рахує historical redirect як рішення; changed-files перелік додатково може рахувати removed paths. Central ADR reservation registry попереджає конфлікти раніше, але потребує окремого coordination протоколу; автоматичне перенумерування чужої гілки неприпустиме.

**Перевірка.** Temp-repo fixtures: unique/gaps; two distinct same prefix; duplicate only after merge; rename/delete/historical redirect, broken target і cycles; conflict; stale base; unavailable GitHub; два PR з одним новим номером; зміна head між list і fetch. Контрольний duplicate падає, виправлений номер і посилання проходять. Робоче дерево/index незмінні.

**Залежності / пріоритет / ризик.** **P2**, без блокера. Можна використати read-only inventory #1228 для local advisory, але залежність не потрібна. Ризик низький для pure checker, середній для live snapshot races. Історію 0061 лишити історією, не оголошувати #1139/#1186 зараз конфліктними без повторної tree-перевірки.

### #1228 — компактний PR/claims inventory

[Issue](https://github.com/Samuel-Ku/slukhayka/issues/1228).

**Факт.** Є read-only `agent-work-claim.py status`, але немає єдиного PR+claims snapshot command. Резервування спільне лише для local worktrees; інші clones видно через comments. Відсутність local claim не доводить вільність. [Claim reader](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/scripts/agent-work-claim.py), [protocol](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/agents/work-coordination.md).

**Рішення.** Окрема host-command `work-status` без mutation subcommands: repo з remote, paginated open PRs, точні base/head SHA, draft/mergeability, checks summary pending/fail/completed/unknown, linked issues, локальний owner і останні comments про reservations. Full raw JSON — окремий opt-in output folder поза repo, компактний stdout — лише потрібні поля й час. Зберігати fetchTime кожного джерела; повторно читати PR head/base, якщо змінились — `stale`, не candidate-ready. Поєднати check-runs та legacy status contexts, рахувати тільки актуальну голову/останній attempt; null/unavailable conclusion — unknown. Issue links брати з API closingIssueReferences і явних tracked references, не лише текстового «#число». [gh api pagination](https://cli.github.com/manual/gh_api), [check runs](https://docs.github.com/en/rest/checks/runs).

Comments не довільно тлумачити як executable instructions. Structured reservation marker на майбутнє зробить зіставлення детермінованим; legacy текст показувати як `manual review required`, якщо parser не доводить стан. Local/github disagreement, external-only reservation, corrupt claim і denied API — окремі статуси. Публічний компактний звіт санітизувати: не виводити приватні machine paths/transcripts/thread IDs; owner можна представити GitHub login або approved human-readable alias. Raw local data лишаються локальними.

**Альтернатива.** Одна велика gh pr view JSON заллє контекст і не звірить reservations; тільки local registry приховає external owner; «готовий» із `нема fail` знову помилково вважає pending дозволом. Це inventory, тому навіть усі green не запускають merge і не замінюють #1220.

**Перевірка.** Recorded API responses для pagination>100, pending/failure/draft/null, rerun attempts, legacy status, head race, external comment без local claim, explicit disagreement, missing issue link, corrupt JSON та auth/network failure. Assert zero calls до mutation endpoints і незмінність branch/index/worktree/claims; stdout bounded, raw files містять повний payload. Документований приклад має synthetic owner alias, без приватних paths.

**Залежності / пріоритет / ризик.** **P2**, без залежності від #1220 чи #1227. Ризик середній: неправильний parser може проголосити issue вільним. Головне правило — `unknown` лишається невідомим, не автоматичним дозволом.

### #1229 — навігація до релевантних ADR

[Issue](https://github.com/Samuel-Ku/slukhayka/issues/1229).

**Факт.** Основний AGENTS каже читати `docs/adr/` без межі; domain guide вже каже `relevant decisions`. Звідси зайве завантаження всіх архітектурних текстів і різні тлумачення старту. [AGENTS](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/AGENTS.md), [domain guide](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/agents/domain.md).

**Рішення.** Мінімальна docs navigation правка: спершу CONTEXT/CONTRIBUTING і agent operating guides; перегляд filename/title/status ADR; повністю читати рішення, які задача/diff зачіпає поняттями, правилами або явними links, далі їхні релевантні cross-references. Main pointer посилається на один domain protocol; glossary чи ADR bodies до AGENTS не копіювати. Це навігація, без нового CLI/CI або semantic search engine.

**Контрольні маршрути вже перевірені читанням.** Для local-folder import: ADR0057 (grouping/provenance) повністю, ADR0056 (chapter order) за explicit reference; ADR0007 для Edition/Chapter/Source tracks при зміні їх структури, ADR0047 якщо змінюється explicit personal intent. Для нової reference-only моделі також повний ADR-0061 з незлитого #1186: ухвалене рішення власника ще не інтегроване в main. Для rewards: ADR0060 повністю, ADR0047 за explicit save/import rule; playback/Cast/notification рішення додавати лише якщо diff зачіпає ці механізми. [ADR0057](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/adr/0057-local-folder-grouping-and-provenance.md), [ADR0060](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/adr/0060-local-achievements-from-verified-progress.md). У цій розвідці повністю прочитано 0057 і 0060; переліки вище — маршрут, а не заявлений execution всіх можливих tasks.

**Альтернатива.** Read-all на кожен старт витрачає контекст; копія glossary/ADR у AGENTS швидко застаріє; автоматичний relevance CLI ускладнить малу docs-зміну. Статична коротка instruction + поняття з CONTEXT достатня для scope issue.

**Перевірка.** Link existence і два контрольні переходи import/rewards, повні тексти обраних рішень, жодних нерелевантних body копій. Diff зберігає reservation/targeted-tests/voice rules; нема blanket вимоги вантажити кожний ADR. `superseded/proposed` не подаються як чинний accepted contract. Це людська read-through перевірка, не причина запускати Gradle.

**Залежності / пріоритет / ризик.** **P3 у загальному порядку**, мала docs-задача без blocker або owner product decision. Ризик низький: надто нечітке «релевантні» краще уточнити трьома concrete triggers, не дублюванням доменної документації.



## Web, спільнота, ліцензія й нагороди

### #697 — web: читання, голос, скарга

- **Що лишилось.** Старий blocker AC5 вже знято: `SourceId` тепер включає `ukrainianaudiobooks`, CI має окремий web job, поточний main отримав success і для typecheck, і для vitest. Читання, голос із заміною попереднього, скарга та mirror hash tests уже є. App Check bootstrap також існує. Коментар 2026-09-19 про червоний typecheck більше не описує main. Я не підтвердив живий Safari/backend сценарій і deployed Firebase enforcement.
- **Рекомендоване рішення.** Взяти наявний CI як свіжий доказ AC5, зробити коротке приймання на iOS Safari PWA + Android на тестовому backend: одна добірка, той самий collectionId, голос 1→5 без нового count, повторна скарга без дубля, відсутність поверхонь без Firebase. Після цього issue можна закрити без повторної реалізації. Перевірити write-сценарії разом із поточною програмою безпеки: зелений UI fake test не доводить server authorization.
- **Альтернатива.** Знову правити два тест-файли або прибирати AC5. Слабша: помилки вже виправлено в типах, а чинний CI дає потрібний доказ.
- **Перевірка.** Точний main CI; крос-платформні pinned UTF-8 SHA-256 vectors; backend transaction re-vote/report; App Check без token має відмовляти; offline transaction не повідомляє success. Firestore офіційно повторює transaction callback при конкуренції й не підтримує offline transactions, тому UI не має виконувати побічні дії всередині callback.
- **Залежності/власник.** Тестовий Firebase deployment і тестова прив'язана identity. Не змінювати hash contract на свій розсуд. У #557 включити результат як завершений зріз після приймання.
- **Пріоритет/ризик.** P2, малий залишок; середній ризик на backend boundary.

Джерела: [issue](https://github.com/Samuel-Ku/slukhayka/issues/697), [чинний SourceId](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/web/src/worker/types.ts#L23), [store transactions](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/web/src/collections/store.ts#L144), [hash contract](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/web/src/collections/collectionIdentity.ts), [UI](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/web/src/ui/collectionReading.tsx), [App Check](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/web/src/firebase/appCheck.ts), [фактичний Web CI success](https://github.com/Samuel-Ku/slukhayka/actions/runs/38118504144/job/114408207325), [Firestore transactions](https://firebase.google.com/docs/firestore/manage-data/transactions), [офіційний App Check setup](https://firebase.google.com/docs/app-check/web/recaptcha-enterprise-provider).

### #817 — релізна обіцянка web-паритету

- **Що лишилось.** Основна спека вже каже **v1.4.3**, а не v1.5. Але історичний grill-документ досі каже v1.5, milestone/CI comments — v1.15, README — старий каркас spec-43 без повного platform ledger. Це розбіжність між документами; дослідження не довело, що parity як релізна подія вже відбулося у v1.4.3.
- **Рекомендоване рішення.** Власник фіксує одне чинне формулювання: релізний target або «після приймання web, номер ще не призначено». Спеці додати коротке датоване superseding рішення; grill залишити історичним із приміткою про нове рішення. README привести до фактичних можливостей і platform ledger. Не призначати навмання нову версію. Закриті W-тікети лишити закритими, а виявлені регресії оформити окремо.
- **Альтернатива.** Перевідкрити всі W0–W6 та заново будувати web. Слабша: змішує реалізований код із випуском і дублює роботу.
- **Перевірка.** Таблиця M0–M6 «код / device evidence / release evidence»; п'ять platform deltas з первинної спеки плюс Telegram account-bound delta ADR-0050. Для offline audio перевірити Range/seek, холодний restart у літаку й eviction, а не тільки факт наявності service worker. Офіційний Workbox описує окрему обробку Range requests.
- **Залежності/власник.** Рішення про release target; приймання #697; актуальний Android IA як еталон, а не застиглий список табів із вересня.
- **Пріоритет/ризик.** P2, мала docs-робота після одного рішення; середній ризик хибної публічної обіцянки.

Джерела: [issue](https://github.com/Samuel-Ku/slukhayka/issues/817), [чинний release paragraph](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/specs/2026-09-06-web-parity.md#L178), [історична v1.5](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/audits/2026-09-06-web-parity-grill.md#L122), [README](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/web/README.md), [Workbox Range requests](https://developer.chrome.com/docs/workbox/modules/workbox-range-requests).

### #557 — батьківська спека web 1:1

- **Що лишилось.** Це parent, а не завдання реалізувати 36 історій заново. Історичний коментар тримає його відкритим за #697/#817. Код цих поверхонь вже існує, але docs/релізне приймання ще не узгоджені. Початкова IA з SETTINGS табом потребує звірки з новішими Android рішеннями.
- **Рекомендоване рішення.** Підготувати коротку матрицю чинних acceptance proofs, завершити #697 і #817, тоді закрити parent із посиланнями на exact-head CI та Safari приймання. Гарантії, яких браузер не дає, залишити в ledger. Спеку зберегти як історію baseline із посиланням на пізніші рішення.
- **Альтернатива.** Оголосити 1:1 лише за списком CLOSED дочірніх issues. Слабша: ADR-0034 вимагає device-visible evidence, і current Android target рухався після початкової спеки.
- **Перевірка.** iOS Safari installed PWA — основний gate, Chromium — другий: linking, Library Entry/tombstone між пристроями, playback/lock screen, review/community write, чесна absence без конфігурації, offline/eviction. Перевіряти ризикові межі й зміни, а не запускати Android full suite.
- **Залежності/власник.** #697, #817, release target і чинний ledger; unresolved shared-write/identity приймання з програми безпеки.
- **Пріоритет/ризик.** P2, координаційний parent; середній ризик назвати код випущеним паритетом.

Джерела: [issue і історичний parent status](https://github.com/Samuel-Ku/slukhayka/issues/557), [ADR-0034](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/adr/0034-web-client-parity-baseline.md), [delivery spec](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/specs/2026-09-06-web-parity.md), [primary Workbox runtime caching](https://developer.chrome.com/docs/workbox/caching-resources-during-runtime/).

### #831 — походження й рейка «Від слухачів»

- **Що лишилось — важлива поправка.** Коментарі «шість із семи AC зроблено» завищують наскрізний стан. `CommunityOriginClassifier` є, але в production UI він не викликається. `HomeFeedContent` показує лише `fromSharedLibrary` і прямо каже, що «Від слухачів» потребує persisted playback provenance. SQL та classifier визнають тільки адресу самої групи `t.me/slukhayka`, а **не повідомлення** `t.me/slukhayka/123`. Проєкція надходження зберігає normalized publication URL. Тому типовий message URL не задовольняє ці перевірки. І окремої першої рейки зі спеки немає.
- **Рекомендоване рішення.** Спершу довести facts: зберігати/проєктувати verified submission provenance зі справжнього документа й розпізнавати **group identity** canonical message link, а не рівність homepage URL. Брати зареєстровану групу з Source Registry. Перевірити точну host/path форму, не сирий `startsWith`, який пропустить `slukhayka2` чи сторонній host. Потім bounded rail «Від слухачів» + перемикач shared-only на Огляді, якщо власник лишає чинний AC. «Другої рейки не заводимо» означає одну спільну рейку з фільтром, а не заборону першої. ADR-0015 захищає тільки вісім блоків «Слухати».
- **Альтернатива.** Власник свідомо прибирає rail AC і лишає existing feed filter. Це допустимий scope choice після запису в #753/spec/ADR-0050, але слабше для discoverability і не лікує message-link/persisted-provenance прогалини. Просто закрити issue за existing filter — недостатньо.
- **Перевірка.** Реальна verified YouTube publication → restart → «Від слухачів»; verified `t.me/slukhayka/123` → shared badge й фільтр; group homepage теж працює; `t.me/slukhayka2/123`, `evil.test/slukhayka/123`, unknown/private peer без підтвердженої identity не отримують ознаки. Обидва facts одночасно мають чинний owner-defined display priority. Одна bounded page query, без N+1; refusal застосовується до будь-якого automatic path; Compose semantics/targets і TalkBack.
- **Залежності/власник.** #830 вже завершений, технічний blocker знято. Owner scope про rail і узгодження з чинним Оглядом потрібні; UI/history reservation перевірити знов перед реалізацією.
- **Пріоритет/ризик.** P2; середній ризик, бо наявний фільтр може приховувати саме intended message Sources.

Джерела: [issue](https://github.com/Samuel-Ku/slukhayka/issues/831), [actual UI limitation](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/ui/screens/HomeFeedContent.kt#L558), [classifier exact comparison](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/data/ingest/CommunityOriginClassifier.kt#L69), [SQL exact group link](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/data/db/AudiobookDao.kt#L830), [publication projection keeps URL](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/data/facets/SubmissionDeltaSync.kt#L269), [ADR-0050 surface contract](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/adr/0050-telegram-community-library-account-bound-source.md), [Telegram official message-link forms](https://core.telegram.org/api/links#message-links), [TDLib link identity resolution](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1get_message_link_info.html).

### #753 — parent Спільної бібліотеки

- **Що лишилось.** Старий parent comment про незавершений #830 застарів. TELEGRAM access mode, TDLib session/fetch seams і UI були доставлені; реальний unresolved child — #831 з деталями вище. Parent також досі вказує старий ADR-0042; чинний номер — ADR-0050. Код seam/tests не є доказом actual native login/download/privacy-route на пристрої.
- **Рекомендоване рішення.** Завершити #831 і виконати один account-bound end-to-end gate: explicit connect → membership → реальний file/audio → persisted metadata → повторне/offline відтворення → sign out. На другій інсталяції metadata visible, сесія не перенесена. Для web чесно показати недоступність Telegram audio й включити delta в #817 ledger. Узгодити старий immediate-publication текст із модераційною чергою від 2026-09-15.
- **Альтернатива.** Server relay чи bot-token playback. Слабша: суперечить accepted ADR-0028/0050, змінює хостинг/identity/privacy boundary й не потрібна для v1.
- **Перевірка.** Немає сесії, не член, restricted-but-not-member, видалений/недоступний post; download cache і приватний маршрут; кілька частин → один Edition без вигаданих глав; offline local copy; Source Audio Refusal; локальний progress іншої Edition не переписується. TDLib `chatMemberStatusRestricted` має власне `is_member_`: не можна прирівнювати будь-який restricted status до member без урахування цього факту; поточний adapter згортає тип restricted у boolean membership без цього поля — варто включити як регресію до gate.
- **Залежності/власник.** #831; тестовий Telegram account і група; #842 — зовнішня publication moderation, якщо потрібно приймати shared-publication path, а не лише локальний import.
- **Пріоритет/ризик.** P2; підвищений ризик account-bound/native integration.

Джерела: [issue](https://github.com/Samuel-Ku/slukhayka/issues/753), [ADR-0050](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/adr/0050-telegram-community-library-account-bound-source.md), [TDLib membership adapter](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/data/ingest/TdLibTelegramSession.kt#L95), [TDLib restricted status owns is_member](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1chat_member_status_restricted.html), [пізніша moderation spec](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/specs/2026-09-15-submission-moderation.md).

### #842 — зовнішній Telegram-бот модерації

- **Що лишилось.** Це справжня external dependency: repo має контракт і fixtures, але bot repository/live Firestore acceptance не надано. Не можна довести AC зміною Android файлів.
- **Рекомендоване рішення.** Передати external owner точний contract і зробити три idempotent transactional commands. Approve читає latest candidate + rejection/tombstone state і атомарно ставить catalog card + approved state; Reject ставить blocklist + rejected state і потрібний tombstone; Remove атомарно блокує URL, tombstone, видаляє card. Authorize кожен Telegram callback за allowlisted actor, а `decidedBy/At` беруться сервером. Callback не приймає довільний URL/shape як джерело правди. Retry/duplicate callback повертає попередній verdict.
- **Альтернатива.** Кілька послідовних незалежних writes або дозвіл модерації з APK. Слабша: crash/гонка можуть залишити card published після reject; клієнтська роль збільшує surface і суперечить контракту.
- **Перевірка.** Тестовий проект, дві установки: verified → pending лише → approve → друга установка отримує; reject → та сама canonical URL більше не чергується; remove → tombstone приходить обом без втрати особистого progress. Duplicate callbacks, approve/reject race, forged actor, після blocklist approve, частковий збій. Не запускати ці дії на production для дослідження.
- **Залежності/власник.** Доступ до окремого bot repo, його maintainer, test Firestore project. Admin SDK обходить Security Rules, тому authz/shape/blocklist checks мусить виконувати сам бот. Для service account — вузькі IAM права й server-side secret storage; цей task не дає дозволу змінювати infrastructure.
- **Пріоритет/ризик.** P2 у загальній черзі; P1 перед ввімкненням публічних надходжень; високий ризик зовнішнього privileged writer.

Джерела: [issue](https://github.com/Samuel-Ku/slukhayka/issues/842), [canonical moderation contract](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/specs/2026-09-15-submission-moderation.md#L56), [fixtures](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/test/java/com/slukhayka/audiobooks/data/metadata/SubmissionModerationContractTest.kt), [Firestore transactions](https://firebase.google.com/docs/firestore/manage-data/transactions), [server libraries bypass rules](https://firebase.google.com/docs/firestore/security/rules-conditions), [Telegram Bot callback API](https://core.telegram.org/bots/api#callbackquery).

### #784 — GPL → BSL

- **Що лишилось.** Main — GPL-3.0-or-later. NewPipeExtractor v0.26.5 залишається explicit runtime implementation і живим YouTube рушієм. `docs/licensing.md` у main немає. Власник 2026-09-15 свідомо відклав BSL і залишив NewPipe; пізніший comment не є новим рішенням його прибрати. `AUTHORS.md` заявляє єдине авторство й пояснює другу git identity, але це заява власника, не незалежна юридична експертиза всього provenance.
- **Рекомендоване рішення.** Лишити поточну GPL, переписати майбутнє завдання як «BSL відкладено до нового рішення про рушій» та окремо створити factual licensing inventory під чинну GPL. AC «жодного copyleft» замінити на «відомі ліцензії всього реально поставленого артефакта й виконані їхні obligations», якщо власник погоджується. Сумісність слабкого copyleft не тотожна його відсутності. Не міняти LICENSE за результатом технічного сканера.
- **Альтернатива.** Якщо власник знову обирає BSL: окремий engine replacement spike для metadata/channel/stream paths, вимірювання reliability/startup/memory/native device, потім audit release artifact і прав. Слабша зараз: додаткова велика робота всупереч чинному рішенню. Просте видалення NewPipe рядка або злиття старої `legal/bsl-1.1` не доводить ані працюючий YouTube, ані legal clearance.
- **Юридично важливі факти.** NewPipe v0.26.5 LICENSE — GPLv3. Розповсюдження combined derivative під додатковими BSL restrictions не можна оголосити дозволеним без потрібних прав/виключень правовласників. BSL 1.1 до Change Date — source-available, не open-source; її Change License має бути GPL-compatible і conversion відбувається не пізніше четвертої річниці відповідної версії. Правовласник може запропонувати іншу ліцензію **на власний код**, але не автоматично на чужі залежності, внески чи раніше роздані GPL copies. DCO засвідчує право вносити код, він сам собою не передає copyright і не заміняє CLA/permission для перелiцензування.
- **Аудит.** `scripts/app-dependency-licenses.sh` кращий за whole-cache сканер, але default — debugRuntimeClasspath, regex бачить тільки GPL-family, unknown не блокує exit, TREE може бути старим, Maven report не охоплює local TDLib AAR і весь native/Python payload. Для release потрібен fresh releaseRuntimeClasspath + APK/AAB packaged inventory + direct license texts/notices/unknown resolution. JNA dual Apache/LGPL не автоматично GPL blocker; MPL-2.0 file copyleft допускає Larger Work з виконанням source/notices obligations. yt-dlp core — Unlicense, **але PyInstaller release binaries містять GPLv3+**, тож «перейти на yt-dlp = автоматично позбутися GPL» неправда; треба audit конкретної distribution і залежностей.
- **Перевірка/залежності.** Власник підтверджує engine/licensing target і права на всі contributions; exact release SBOM та license review; notices/README/LICENSE consistent. Цього дослідження недостатньо для юридичного висновку про можливість BSL.
- **Пріоритет/ризик.** P3 для migration, P2 для truthful inventory; високий юридичний ризик поспішного перелiцензування.

Джерела: [owner defer decision](https://github.com/Samuel-Ku/slukhayka/issues/784#issuecomment-5681213611), [actual dependency](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/build.gradle.kts#L322), [actual inventory script](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/scripts/app-dependency-licenses.sh), [AUTHORS owner statement](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/AUTHORS.md), [pinned NewPipe license](https://raw.githubusercontent.com/TeamNewPipe/NewPipeExtractor/v0.26.5/LICENSE), [GPLv3 §§5–6,10](https://www.gnu.org/licenses/gpl-3.0.html), [FSF copyright-holder multiple licenses](https://www.gnu.org/licenses/gpl-faq.en.html#ReleaseUnderGPLAndNF), [BSL authoritative text](https://mariadb.com/bsl11/), [MPL authoritative text](https://www.mozilla.org/en-US/MPL/2.0/), [MPL FAQ](https://www.mozilla.org/en-US/MPL/2.0/FAQ/), [JNA dual licensing](https://github.com/java-native-access/jna/blob/master/LICENSE), [yt-dlp distribution licensing](https://github.com/yt-dlp/yt-dlp#licensing), [DCO text](https://developercertificate.org/).

### #1198 — «Археолог»

- **Що лишилось.** Це одна нова boolean fact і відповідна hidden award, а не дубль deep_search. Поточний main не має Archaeologist metric/fact/name. Draft PR #1162 OPEN, MERGEABLE, HEAD `4a543dd8ad99ef4df1596ec006649d2b8e61511f` займає пошуковий шар. Existing gap-fill уже читає `existing = findWorkByMergeKey` перед записом, але novelty не повертає на card.
- **Рекомендоване рішення.** Дочекатися звільнення #1162 й дати owning group вузьку зміну: зафіксувати факт absent-before-search-write з реального index/Work lookup до gap-fill, пронести per-Work provenance через merge/duration/cover/filter/card action, записати new AchievementFact через існуючий recorder/store й metric/catalog mapping. Не виводити «не було в індексі» з відсутності у локальному top-50 FTS response: це може бути truncation або language/session filter, а не відсутній Work.
- **Уточнення ідентичності.** Використати саме факт пошукового індексу, обчислений до gap-fill для цієї search session. Самої перевірки наявності особистого Work/Library Entry недостатньо: запис каталогу та особистий вибір — різні речі. Якщо кілька Sources одночасно дають той самий Work, перший запис не має змінювати семантику факту інших результатів тієї самої сесії.
- **Альтернатива.** Нагородити будь-який global search або порівняти тільки returned local result ids. Слабша: дублює deep_search чи створює false positive для наявного index row. Нова Room column/table зайва: existing string-key fact store покриває boolean.
- **Перевірка.** Local hit → no fact; live hit з реально наявним index row поза local limit → no fact; absent row live hit → one fact; повтор після gap-fill → no новий факт; одна Work із кількома Sources → одна нагорода; cancelled/stale query і tombstoned/scam invisible result → no award; merge/cover resolution не губить provenance; UA/EN і notice lifecycle; no schema change.
- **Залежності/власник.** Чинне рішення owner від 2026-10-07: це окремий факт. Потрібно уточнити лише **момент прийняття**: автоматично на search result чи на listener card/import action — не підміняти це нашою здогадкою. Issue прямо просить пронести на card, ADR-0060 deep_search фіксує імпорт. Блокер — #1162 і конкретне доручення групи spec52-decisions; не брати чужу пошукову ділянку через порожні local claims.
- **Пріоритет/ризик.** P2 після unblock; середній ризик semantic false positive, мала реалізація.

Джерела: [issue](https://github.com/Samuel-Ku/slukhayka/issues/1198), [active blocker PR](https://github.com/Samuel-Ku/slukhayka/pull/1162), [search/merge pipeline](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/data/catalog/SourceCatalog.kt#L990), [existing pre-write lookup](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/data/catalog/SourceCatalog.kt#L1172), [card model](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/data/source/GlobalSearch.kt#L52), [fact seam](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/data/achievements/AchievementProgressSource.kt#L27), [ADR-0060 accepted facts](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/adr/0060-local-achievements-from-verified-progress.md).

### #698 — parent нагороди

- **Що лишилось.** Коментар 2026-09-18 «майбутній зріз, код не робимо» більше не відбиває main: фундамент, каталог, recorder, UI, showcase і уточнення 2026-10-07 вже є; серед відкритих spec-52 дочірніх issues у знімку лишився #1198. Натомість верх спеки й milestone description досі кажуть, що доля програми відкрита. Це stale tracking, не причина переписувати систему.
- **Рекомендоване рішення.** Відновити parent acceptance matrix за фінальними owner decisions; зафіксувати реалізований стан і єдиний unresolved #1198. Після unblock/реалізації archaeologist і видимого device gate закрити parent. Зняті «Пасхалка», «Ювілей години», «Фінальний акорд» не повертати як приховані задачі — 2026-10-07 вони свідомо відкинуті.
- **Альтернатива.** Закрити parent зараз як «майбутню програму скасовано» або заново реалізувати всі 66 user stories дослівно. Слабша: суперечить уже delivered кодові й пізнішим owner decisions, які мають precedence над старими історіями.
- **Перевірка.** One award/one notice, no spoiler hidden titles до earning, restart persistence, pinned showcase максимум 3, no private fact cloud sync. Агрегати рахують verified monotonic playback, не duration position чи старі legacy hours. Особливо no duplicate Cast completion/new-load callbacks, offline actual local file evidence й deliberate personal-book ownership. Existing tests і green CI — база; actual device acceptance не замінюється кодом.
- **Залежності/власник.** #1198; spec52 owning group coordination; docs/milestone cleanup без нового рішення про скасування. Default не втручатися в active implementation lanes.
- **Пріоритет/ризик.** P2, parent acceptance/admin cleanup; середній ризик false completion або resurrection dropped awards.

Джерела: [issue](https://github.com/Samuel-Ku/slukhayka/issues/698), [чинні owner decisions](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/specs/2026-09-10-achievements.md#L196), [already delivered catalog](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/app/src/main/java/com/slukhayka/audiobooks/data/achievements/AchievementEvaluator.kt), [ADR-0060](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/adr/0060-local-achievements-from-verified-progress.md), [external owning group plan](https://github.com/Samuel-Ku/slukhayka/blob/91c837c07e50db3da3443f44cdd62f3db74dc840/docs/plans/2026-10-07-spec52-tail.md).
