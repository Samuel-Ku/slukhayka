# Реєстр задач і відомих дефектів до оновлення

Знімок: 2026-10-13T11:35:11+02:00. Канонічні описи й критерії — у GitHub Issues.
Команда відкритої вибірки: `gh issue list --state open --limit 500 --json number,title,body,labels,milestone,url,comments,updatedAt`. У знімку 44 відкритих issues; кожне є в таблиці нижче. Дані й hashes — у [inventory.json](inventory.json).

**Мітка bug не є повним реєстром дефектів.** Спеки можуть описувати реальні гонки чи втрату даних. Окремо наведено безпекові знахідки та вже закриті bug-issues для регресії.

Резолюція підтвердженого бага: виправлено й перевірено на candidate SHA; або доведено, що на актуальній базі його вже немає. Невідтворення без точних умов не є доказом виправлення. Дослідницьке питання може мати задокументовану межу; вона не називається виправленим багом.

Для кожної реалізації додати owner, конкретний reproduction, fix SHA, фактичну команду/результат і evidence URL/location. До цього delivery_status лишається open. Чужі резервування звіряти з останніми comments та common claims.

## Повний перелік відкритих issues

| Issue | Тип у плані | Етап | Доставка / умова приймання |
| --- | --- | --- | --- |
| [#1281](https://github.com/Samuel-Ku/slukhayka/issues/1281) — Межа ґарда премиси: main/ резолвиться в теку | дефект / ризик tooling | R0 | Розрізнити файл і теку; old/new oracle #982; не приймати неоднозначність за наявний файл. |
| [#1280](https://github.com/Samuel-Ku/slukhayka/issues/1280) — test-changed.sh тече: 17 порожніх тек | дефект / ризик tooling | R0 | Cleanup власної теки після success/failure/signal; перевірити exec/EXIT, не обіцяти cleanup після SIGKILL. |
| [#1279](https://github.com/Samuel-Ku/slukhayka/issues/1279) — Виведення свідка з AST — закрити або відмовитись | дослідження / правила процесу | R0 | Обмежений висновок AST або явний виконуваний зв’язок; не нескінченний перебір синтаксичних форм. |
| [#1278](https://github.com/Samuel-Ku/slukhayka/issues/1278) — Межа, названа лише у звіті, не існує | дослідження / правила процесу | R0 | Межі зберігаються біля відповідного коду; конкретний live приклад і перевірений scope. |
| [#1277](https://github.com/Samuel-Ku/slukhayka/issues/1277) — Кожне число в доках мусить мати команду, яка його відтворює | дослідження / правила процесу | R0 | Виправити названі хибні числа/команди; не оголошувати загальну гарантію без доказу. |
| [#1276](https://github.com/Samuel-Ku/slukhayka/issues/1276) — Кожне оголошене поле мусить бути виведеним або пінованим | дослідження / правила процесу | R0 | Реєстр фактичних декларативних місць і обмежений перевірений контракт; заборонена фіктивна універсальна гарантія. |
| [#1273](https://github.com/Samuel-Ku/slukhayka/issues/1273) — Обгортка слота мусить не дати одній збірці вичерпати пам'ять | дефект / ризик tooling | R0 | Preflight memory + контроль власного process tree; відрізнити відмову від build failure. |
| [#1271](https://github.com/Samuel-Ku/slukhayka/issues/1271) — Механізми мутацій не сміють вичерпувати tmpfs | дефект / ризик tooling | R0 | Budget tmp до копіювання; мінімальні дерева й cleanup власних артефактів; чинний PR #1275. |
| [#1270](https://github.com/Samuel-Ku/slukhayka/issues/1270) — Кожен сторож мусить мати мутацію, яка доводить, що він червоніє | дослідження / правила процесу | R0 | Мутації конкретних required verdicts; межі дерева/мов explicit; не послаблювати чинні checks. |
| [#1265](https://github.com/Samuel-Ku/slukhayka/issues/1265) — Фікстури: та сама форма й конструктивне оточення | дефект / ризик tooling | R0 | Чинний PR #1267; isolation фікстур і визначений shape; відомі межі не приховані. |
| [#1243](https://github.com/Samuel-Ku/slukhayka/issues/1243) — Старий результат не перезаписує поточне прев’ю ImportPlan | дефект продукту / перевірка гіпотези | R3 | Повільний scan/apply A не замінює preview B; належний старий commit/provenance збережено. |
| [#1242](https://github.com/Samuel-Ku/slukhayka/issues/1242) — Одне підтвердження ImportPlan запускає один запис | дефект продукту / перевірка гіпотези | R3 | Подвійний confirm запускає один immutable apply; busy/error/retry правильні. |
| [#1241](https://github.com/Samuel-Ku/slukhayka/issues/1241) — Пізня підготовка не замінює новішу спробу відтворення | дефект продукту / перевірка гіпотези | R3 | A→B із пізньою A, clear/delete, Bookmark/relisten; активний audio і recovery належать B. |
| [#1240](https://github.com/Samuel-Ku/slukhayka/issues/1240) — Єдиний початок явної спроби відтворення Edition | дефект продукту / перевірка гіпотези | R3 | Play/Bookmark/relisten через один початок; старий error і recovery budget не витікають. |
| [#1238](https://github.com/Samuel-Ku/slukhayka/issues/1238) — Колективний каталог: серверний lease, ідентичність добірок і порядок delta | контракт shared каталогу | R5 | Related A/B, server order і owner-token lease; late owner відхилено; real two-client oracle. |
| [#1237](https://github.com/Samuel-Ku/slukhayka/issues/1237) — Ліцензії: фактичний склад GPL-випуску й потрібні notices | release/documentation | R1 | Notices відповідають фактичному APK/Web складу, GPL/native/assets; не змінювати ліцензію. |
| [#1236](https://github.com/Samuel-Ku/slukhayka/issues/1236) — spec-900: Web — рейл і список із деталями за спільною картою | фіча / завершення доставки | окрема доставка | Нова Web adaptive delivery після базового приймання; не відкладати через неї існуючі Web bug fixes. |
| [#1235](https://github.com/Samuel-Ku/slukhayka/issues/1235) — spec-900: однакові двопанельні книжкові списки без порожньої панелі | фіча / завершення доставки | R8 | Читабельні всі двопанельні lists, no empty pane, Back/focus, boundary dp й великий шрифт. |
| [#1230](https://github.com/Samuel-Ku/slukhayka/issues/1230) — Локальна книга з нечитабельним файлом: двері до файлів пристрою мають бути першими, а не пошук у мережі | дефект продукту / перевірка гіпотези | R2 | Local-first відновлення файла/теки; IDs/progress не губляться; інша начитка потребує явного вибору. |
| [#1229](https://github.com/Samuel-Ku/slukhayka/issues/1229) — docs(agents): однозначно спрямовувати до релевантних ADR | дослідження / правила процесу | R0 | Точна навігація релевантних ADR; не вимагати читання всього дерева перед кожною правкою. |
| [#1228](https://github.com/Samuel-Ku/slukhayka/issues/1228) — chore(agents): компактний read-only знімок PR і резервувань | дослідження / правила процесу | R0 | Read-only snapshot точних SHA/PR/claims; unknown ≠ free; не новий сервіс координації. |
| [#1227](https://github.com/Samuel-Ku/slukhayka/issues/1227) — ci(docs): виявляти повторні номери ADR до злиття | дефект / ризик tooling | R0 | Canonical ADR collision та redirects у merge tree; чинні номери не переписувати довільно. |
| [#1226](https://github.com/Samuel-Ku/slukhayka/issues/1226) — fix(reviews): видалений відгук не повертається після refresh до ACK | дефект продукту / перевірка гіпотези | R4 | DELETE→old refresh→ACK: review і голос не повертаються; rejection/new mutation/identity захищені. |
| [#1198](https://github.com/Samuel-Ku/slukhayka/issues/1198) — spec-52: «Археолог» — факт живого хіта поза локальним індексом | фіча / завершення доставки | R8 | Нагорода лише після успішного створення Work, відсутнього в індексі до пошуку; один факт. |
| [#1188](https://github.com/Samuel-Ku/slukhayka/issues/1188) — Прев'ю імпорту: drag-and-drop для порядку розділів замість стрілок (перегляд рішення #1049) | фіча / завершення доставки | R8 | Drag handle + up/down/TalkBack; scroll/autoscroll; Save/Cancel; stable Chapter anchors. |
| [#1187](https://github.com/Samuel-Ku/slukhayka/issues/1187) — Локальна тека: час книги збирається лише з прослуханих глав — і часткова сума годує правило «завершено» | дефект продукту / перевірка гіпотези | R2 | Фоновий local duration; неповний total unknown; незавершена книга не скидає позицію. |
| [#1185](https://github.com/Samuel-Ku/slukhayka/issues/1185) — Спека: локальна тека — джерело за посиланням (без приватної копії, ADR-0061) | фіча / завершення доставки | R2 | T1+T3 разом, T2 recovery, safe T4 migration, T5 storage, T6 content URI cast; owner dsh-web-w1185. |
| [#1163](https://github.com/Samuel-Ku/slukhayka/issues/1163) — Сховище: «Audio on device» = 0 МБ, хоч приватні копії локальної теки займають 3,23 ГБ | дефект продукту / перевірка гіпотези | R2 | Фактичні local/download байти; «На пристрої»; clear downloads не видаляє чи деактивує локальні книги. |
| [#1005](https://github.com/Samuel-Ku/slukhayka/issues/1005) — [Idea] Narrative GPS: spoiler-safe пам’ять аудіокниги | фіча / завершення доставки | окрема доставка | Дослідницький Semantic Rewind на одній дозволеній книзі; нова фіча, не відомий дефект. |
| [#968](https://github.com/Samuel-Ku/slukhayka/issues/968) — Наявні дублі творів (різні mergeKey) не зливаються — рішення про ідентичність | дефект продукту / перевірка гіпотези | R7 | Work-only apply/retry/Undo і atomic person closure після accepted preview; зберегти всі anchors. |
| [#842](https://github.com/Samuel-Ku/slukhayka/issues/842) — Модерація T9 — Telegram-бот: черга, схвалити/відхилити/прибрати (поза репо) | фіча / завершення доставки | окрема доставка | Зовнішній бот і maintainer; нова feature delivery; без credentials/deploy у цьому репо. |
| [#831](https://github.com/Samuel-Ku/slukhayka/issues/831) — Спільна бібліотека T4: ознаки походження й рейка «Від слухачів» | фіча / завершення доставки | R8 | Persisted provenance/group identity, рейка «Від слухачів» за наявності даних; без нової рейки Слухати. |
| [#817](https://github.com/Samuel-Ku/slukhayka/issues/817) — spec: реліз web-паритету більше не v1.5 — перенести на пізніший реліз | release/documentation | R9 | Фактичний Android/Web platform ledger і release promise; жодного неперевіреного parity. |
| [#753](https://github.com/Samuel-Ku/slukhayka/issues/753) — Спека: Спільна бібліотека — Telegram як джерело з модерацією спільноти | umbrella | R8 | Приймання залишків Telegram у межах джерела/auth/membership; umbrella не дорівнює дефекту. |
| [#698](https://github.com/Samuel-Ku/slukhayka/issues/698) — Спека 52: нагороди слухача — локальні досягнення з реальних даних | umbrella | R8 | Злиті нагороди не повторювати; прийняти залишок #1198 і справжні факти. |
| [#697](https://github.com/Samuel-Ku/slukhayka/issues/697) — spec-51 T9: web — читання, голос, скарга | наскрізне приймання | окрема доставка | Код read/vote/report уже є; backend, shared hashes і Safari/Chromium acceptance лишаються. |
| [#620](https://github.com/Samuel-Ku/slukhayka/issues/620) — Спека: поглибити Source Catalog, Feed Snapshot і Listener Review | дефект продукту / перевірка гіпотези | R4 | Усі stories: Web intent/pagination; FeedSnapshot last-good/TTL; reviews lifecycle/restart/ACK. |
| [#557](https://github.com/Samuel-Ku/slukhayka/issues/557) — Спека: Web Client 1:1 з Android (web parity) | umbrella | окрема доставка | Full parity лише після #697, #817 і M0–M6 Safari PWA/Chromium; чинні баги все одно у R4. |
| [#533](https://github.com/Samuel-Ku/slukhayka/issues/533) — acceptance: дві установки, request budget, звук і fallback | наскрізне приймання | R5 | Два клієнти, real Source/audio/backend, budget/fallback, normal startup; старий PASS лише свого scope. |
| [#525](https://github.com/Samuel-Ku/slukhayka/issues/525) — sluhayua: новинки, рекомендації й добірки на Огляді | фіча / завершення доставки | R5 | Fresh Source-labeled blocks, empty/failure зберігає дані, related provenance; actual shared propagation. |
| [#524](https://github.com/Samuel-Ku/slukhayka/issues/524) — sluhayua: пошук за попитом → звук → shared delta | фіча / завершення доставки | R5 | Local/shared miss→один Source search→реальні глави/звук→verified delta другій інсталяції. |
| [#521](https://github.com/Samuel-Ku/slukhayka/issues/521) — Spec: колективний каталог і живий «Огляд» із прямих джерел | umbrella | R5 | Parent тільки після пошуку, blocks/server contract і full #533; не закривати за checkboxes. |
| [#487](https://github.com/Samuel-Ku/slukhayka/issues/487) — Рекомендації: eval-gate на реальному масштабі + перевірка відсутності fallback-а | дефект продукту / перевірка гіпотези | R6 | Runtime окремо від quality; чинний NO_GO і real native/device acceptance лишаються блокерами. |
| [#480](https://github.com/Samuel-Ku/slukhayka/issues/480) — Рекомендації: подієвість, стабільний кеш, повний пул і чесна модель (spec) | umbrella | R6 | Фази 1–3 уже частково main; cancel story21/runtime/quality завершити без повторного rewrite. |

## Дефекти й залишки з аудиту безпеки

Джерела: [аудит 2026-10-03](../../audits/2026-10-03-security.md), [ADR-0055](../../adr/0055-credential-and-shared-write-boundaries.md), [runbook](../../runbooks/firebase-app-check.md). Описують останній записаний стан, не live перевірку 13 жовтня. Їхня local реалізація не доводить інтеграцію в current main або rollout.

| ID | Дефект або неперевірена межа | Gate R1 |
| --- | --- | --- |
| S1 | Public device-bindings і потенційно розкриті credentials | Старий доступ закритий, клієнт їх не читає; verified-owner recovery й контрольований rollout без блокування власників |
| S2 | Серверне авторство та service App Check | Чужі записи/читання відхилені; реальна атестація Android/Web; actual Rules/Enforcement і сумісність старих профілів |
| S3 | Worker redirect/MIME/body/fan-out та quota межі | Перевірити інтегрований Worker, negative paths і сумарний бюджет; distributed rate-limit має окремий факт або явно погоджену межу |
| S4 | Transport headers і local playback proxy | Cross-origin credentials не витікають; власний proxy обмежує ресурси й працює з content URI та реальним Cast |
| S5 | Local credential storage, backup, native/assets/dependencies | Старий plaintext прибраний без втрати входу; fail-closed cipher; backward recovery за ADR-0055; hashes/notices/security checks. ANDROID_ID не названий секретом; невідомі CVE не оголошені відсутніми |

Повнота таблиці первинного аудиту: усі наведені там знахідки мають групу
вище. До фактичного виправлення/інтеграції й потрібного rollout вони відкриті.

| Знахідка первинного аудиту | Група | Перевірка |
| --- | --- | --- |
| Необмежені socket threads Chromecast | S4 | Межа idle sockets до HTTP, overflow refusal, shutdown/restart token |
| Public device-binding credentials | S1 | Denied reads/list/writes, retired client path, old-owner recovery |
| Redirect веб-проксі за чужий host | S3 | Кожний hop, HTTPS/port/credentials, redirect limit |
| Активний Content-Type на origin застосунку | S3 | Inert MIME, nosniff/CSP, audio ranges збережені |
| Чуже авторство/лічильники/голоси/скарги | S2 | Ownership, atomic counters, приватні reads/list |
| Cookie/Authorization/Referer на чужий origin | S4 | Два origins, кожний hop, відмова downgrade |
| Відкритий пароль при cipher failure/Auto Backup | S5 | Fail-closed, one-time plaintext cleanup, старий sealed blob збережений |
| Random для нових паролів | S5 | Production SecureRandom; test-only injected generator |
| Неканонічні IDs і зайві поля reviews/ratings/progress/submissions | S2 | Canonical keys, allowed fields, owner hashes, blocklist |
| constructor/__proto__ registry lookup | S3 | Own-key lookup; unknown не запускає адаптер |
| Необмежені HTML/JSON body/time | S3 | Actual-byte cap, deadline/cancel, bounded fan-out, streaming audio |
| Рухомі ONNX/tokenizer і неперевірений cached TDLib | S5 | Pinned revisions/checksums; corrupt cached asset відмовляється; atomic HTTPS fetch |

**Окремий S6 — залежності інструментів.** Аудит описує невиправлені advisory
у dev CLI rules-matrix, попри чисте перевірене Web-дерево на дату аудиту.
Перед RC повторно звірити поточні pinned versions/advisories, застосувати
перевірені upstream patches або сумісну заміну. Якщо виправлення недоступне,
потрібна конкретна оцінка exposure та явне рішення щодо залишкового ризику;
не називати це виправленням чи відсутністю вразливостей. Довільне пониження
CLI або major override не є доказом безпеки.

Нові canonical recovery/release issues підготувати після перегляду конкретного обсягу. Номери не вигадані; S1–S5 є локальними reference IDs, не GitHub-тікетами.

## Уже закриті bug-issues — регресія кандидата

Вибірка закритих issues зроблена за датою 2026-10-04 включно; це не твердження, що кожне виправлення відбулося після часу публікації v1.5. Нові реалізації цих задач не запускати.

| Issue | Закрито | Що перевірити |
| --- | --- | --- |
| [#1206](https://github.com/Samuel-Ku/slukhayka/issues/1206) — Великі екрани: на рівно 600 dp сітка Бібліотеки не вміщується в панель (240 проти 284 dp) | 2026-10-10T22:15:57Z | Чинна поведінка у signed candidate та upgrade; повторне відкриття лише за новим відтворенням |
| [#1168](https://github.com/Samuel-Ku/slukhayka/issues/1168) — Картка статистики: «днів підряд» рахуються по рядках, а не по датах | 2026-10-08T19:35:53Z | Чинна поведінка у signed candidate та upgrade; повторне відкриття лише за новим відтворенням |
| [#1165](https://github.com/Samuel-Ku/slukhayka/issues/1165) — Регрес #390: чіп «Завантажені» знову обрізається на v1.5 (темна тема) | 2026-10-10T16:51:20Z | Чинна поведінка у signed candidate та upgrade; повторне відкриття лише за новим відтворенням |
| [#1154](https://github.com/Samuel-Ku/slukhayka/issues/1154) — Добірку слухача неможливо опублікувати: аркуш згоди існує, але його ніхто не відкриває (#691) | 2026-10-06T10:46:08Z | Чинна поведінка у signed candidate та upgrade; повторне відкриття лише за новим відтворенням |
| [#1121](https://github.com/Samuel-Ku/slukhayka/issues/1121) — Манифест: enableOnBackInvokedCallback не задекларовано при targetSdk 36 — попередження в логах, вплив не з'ясовано | 2026-10-04T11:01:46Z | Чинна поведінка у signed candidate та upgrade; повторне відкриття лише за новим відтворенням |
| [#1115](https://github.com/Samuel-Ku/slukhayka/issues/1115) — CI: ніжка Room Robolectric-only гине від таймауту 40 хв — «cancelled» виглядає як провал тестів | 2026-10-04T10:58:12Z | Чинна поведінка у signed candidate та upgrade; повторне відкриття лише за новим відтворенням |

## Незавершені PR і точні heads

| PR | Draft | Head під час знімка | Межа |
| --- | --- | --- | --- |
| [#1282](https://github.com/Samuel-Ku/slukhayka/pull/1282) — Runtime рекомендацій: backend, cancellation і черга векторів (#487) | true | `4080cbe2f712a38d9fdf0dfd70c5f1d684d8bbd3` | Runtime; quality і device/native acceptance ще відкриті. |
| [#1275](https://github.com/Samuel-Ku/slukhayka/pull/1275) — fix(scripts): гейти мутацій не заповнюють tmpfs — відмова з числами замість убитого середовища (#1271) | false | `e1ee2df5e08af5d58e9c6a86a562ba2aa4be8d12` | Tooling/tmp budget; не нова користувацька фіча. |
| [#1267](https://github.com/Samuel-Ku/slukhayka/pull/1267) — test(scripts): усі фікстури мають конструктивне оточення й пін форми (#1265) | false | `a65254ad16bee82ebf184f588c36d522ea4e71c2` | Фікстури; власник працює, дубль не запускати. |
| [#1186](https://github.com/Samuel-Ku/slukhayka/pull/1186) — feat(import): #1185 — локальна тека за посиланням: ADR-0061 + крок 1 T1 | false | `dbd3bd1e151257e5559179fb8c53a1505c811153` | Аддитивний URI scanner; без перемикання на by-reference імпорт. |
| [#1181](https://github.com/Samuel-Ku/slukhayka/pull/1181) — fix(persistence): confirmed кеш відгуків і межі Feed Snapshot (#620) | true | `4d0660c994540f6da52fee5c7362ad3204d4a20f` | Confirmed state/cache; full device/backend acceptance відкрите. |
| [#1162](https://github.com/Samuel-Ku/slukhayka/pull/1162) — feat(sources): пошук і збережені добірки SluhayUA (#524, #525) | true | `0d547abbbd6fbaf6530a51fedb82ccf1b3846d38` | Source search/blocks; live audio/два клієнти ще відкриті. |
| [#1161](https://github.com/Samuel-Ku/slukhayka/pull/1161) — feat(identity): preview ручного злиття творів #968 | true | `0e7dca8067e8fd5b59e7fd9ff1bb3d076e04f435` | Preview; apply/Undo/UI не доведені public PR. |
| [#1139](https://github.com/Samuel-Ku/slukhayka/pull/1139) — Рекомендації: реальний eval і явний результат backend (#487, NO-GO) | true | `7614108f91f2f81eafdcd8da4e4e8955594b558c` | Quality NO_GO; green runtime не закриває quality. |

## Фічі поза критичним шляхом

Повний Web-паритет #557/#697 і нова adaptive Web-доставка #1236 лишаються окремим прийманням за чинним рішенням власника. Відомі дефекти чинного Web каталогу/відгуків включено до R4; відкладений parity не є способом їх відкласти. #817 синхронізує фактичну обіцянку.

Narrative GPS #1005 та зовнішній бот #842 — нові фічі з окремими залежностями. Вони не замінюють стабільність, локальні книги чи якість рекомендацій. #831/#753 можуть увійти як готова додаткова користь; їхні confirmed дефекти чинної поведінки, якщо виявлені під час triage, обов’язково переходять до release bug gate.

Без явної зміни обсягу власником підтверджений баг не переноситься лише через складність. Невиконаний quality gate лишає R6 відкритим. План не обіцяє «всі відомі баги виправлено», доки це не доведено.
