# Наступне оновлення Слухайки

plan_id: next-release-2026-10-13
plan_version: 2
status: draft
owner_role: release_planner
created_at: 2026-10-13 Europe/Warsaw
canonical_source: TASK-PLAN.md
issue_tracker: GitHub Issues; цей файл визначає порядок доставки, не замінює issues
inventory: inventory.json
bug_register: BUG-REGISTER.md
verified_main: 37832cb7b8715c231083b0d4345cbcc52bda528f
release_baseline: v1.5, опублікована 2026-10-04
target_version: визначити перед RC; старі milestone v1.6–v1.17 не є номерами випущених версій
implementation_started_by_this_plan: false

## Для чого оновлення

Слухач додає власні книги без другої копії, бачить правильний час і зайняте
місце, продовжує слухання без несподіваного повернення на початок. Пошук дає
реальні книги й звук; рекомендації допомагають обирати наступну книгу.
Оновлення зберігає бібліотеку, закладки, місця слухання й доступ до профілю.

Власник визначив пріоритет «1+2»: локальні книги, пошук і стабільність;
рекомендації та відкриття нових книг. Додаткове рішення власника:
максимізувати паралельну роботу. Уже злиті нагороди й великі екрани
входять як додаткова користь, після перевірки саме релізної збірки.

«Виправити всі відомі баги» тут означає пройти всі дефекти з реєстру,
включно з дефектами без мітки bug та знахідками аудиту безпеки. Новий
підтверджений дефект, знайдений до RC, додається до цього реєстру й отримує
власника. Невиконана фіча, відкрите дослідження й підтверджений баг мають
різні стани. Невідомі баги неможливо оголосити відсутніми.

## Обраний шлях

1. **Один реліз із виправленнями та користями 1+2 — рекомендовано.**
   Спочатку зберігання даних і дефекти, потім завершення локальних книг,
   пошуку та рекомендацій. Уже готові поліпшення доставляються разом.
2. **Спершу випуск лише з виправленнями, потім фічі.** Дає раніше стабільну
   збірку, але розділяє погоджені користі. Це резервний варіант за окремою
   зміною обсягу власником, а не автоматичний перенос складних багів.
3. **Усе з усіх старих milestone.** Включає повний web-паритет, зовнішнього
   бота й Narrative GPS; має нові залежності та відкладає відчутний результат.
   Для цього оновлення не обрано.

## Підготовка перед реалізацією

preimplementation_status: draft
goal: перевірений реліз із закритими відомими дефектами та сценаріями 1+2
scope_in: Android; виправлення чинних Web-сценаріїв; безпечні cloud-контракти; release tooling
scope_out: Narrative GPS; нова модель без окремого протоколу; повний Web-паритет без Safari-приймання; розгортання зовнішнього бота
changed_subsystems: local Sources; import; playback; reviews; catalog; recommendations; identity; adaptive UI; release
constraints: цілісність даних; чинні рішення власника; чужі резервування; один важкий build-слот
assumptions: основний реліз Android; нові фічі беруться з погоджених issues
open_questions: фінальний номер; придатний стенд для двох інсталяцій; актуальний стан cloud recovery
regression_risks: втрата єдиної локальної копії; змішування начиток; відкат нового прогресу; блокування старого профілю
timebox: послідовність етапів без вигаданої дати; календар після перевірки стенда й декомпозиції власниками

- [x] Контекст, ADR-0017, ADR-0049, ADR-0055, внески й координацію прочитано.
- [x] GitHub: усі відкриті issues з коментарями, відкриті PR, недавні закриті bug-issues.
- [x] Спільні claims і build-слот прочитано; записи не змінено.
- [x] Пріоритети власника «1+2» враховано.
- [x] Усі відкриті issues отримали місце в BUG-REGISTER.md.
- [ ] Власник переглянув конкретний обсяг цього релізу.
- [ ] Призначено координатора релізу; підтверджено передачі незайнятих задач.
- [ ] Чинний власник відновив стенд і підтвердив придатний слот збірки.
- [ ] Звірено безпекову гілку, актуальні хмарні правила й план старих профілів.
- [ ] Перед кожним зрізом узгоджено поточний код, точні тестові targets та oracle.

Переліку задач недостатньо для стану ready. Невідомий стенд, невизначена
передача або неперевірений cloud-контракт блокують залежний зріз.

## Що користувач зможе перевірити

| Користь | Приклад приймання | Етап |
| --- | --- | --- |
| Книга не займає місце двічі | Додати теку; нова приватна копія не створюється; офлайн Play працює | R2 |
| Правдивий час і місце слухання | Час визначається без програвання всіх глав; неповний час не скидає книгу | R2 |
| Безпечне очищення й відновлення | Очистити завантаження; локальна книга читається; після втрати доступу повторно вибрати теку | R2 |
| Передбачувані Play та імпорт | Запустити A, потім B; пізня A не витісняє B; подвійне підтвердження імпорту не дублює запис | R3 |
| Пошук, із якого можна слухати | Знайти книгу SluhayUA, відкрити правильні глави, почути звук; друга інсталяція бачить shared результат | R5 |
| Корисні рекомендації | Новий результат проходить чинний quality gate; mode й картки узгоджені, cancel і retry працюють | R6 |
| Менше дублів без втрати даних | Ручне злиття об'єднує Твір, зберігає дві начитки, позиції, файли й закладки; Undo без втрати нових дій | R7 |
| Зручніше керування | Нагороди з реального слухання, читабельні великі екрани, ручка перестановки глав | R8 |

## Execution Governance

orchestration_mode: parallel_independent_lanes; sequential_handoffs_within_slice
max_active_agents: 4 — координатор/інтегратор та до 3 виконавців або рецензентів у цьому чаті
max_heavy_local_runs: 1 на спільному host; слот належить чинному власнику
existing_external_lanes: продовжуються чинними власниками; не дублювати їх у цих 3 слотах
agent_sequence: planner -> implementer -> reviewer -> tester -> docs_sync
max_review_loops: 2 на конкретну версію кандидата
escalation_rule: після двох невдалих циклів назвати дефект, залежність і наступний вузький крок; не створювати новий framework автоматично
mock_policy: synthetic тести дозволені як ізольовані перевірки; реальні audio/cloud/native/device сценарії ними не закриваються
done_policy: код у release candidate; цільові перевірки; потрібне живе приймання; review на тому самому SHA; issue й реєстр синхронізовані
required_approvals: чинне source/standards review; release acceptance; рішення власника щодо публікації та змін production

До будь-якої реалізації: перечитати issue з останніми коментарями, відкриті
PR та `python3 scripts/agent-work-claim.py status <номер>`. Координатор
атомарно резервує лише вільну задачу й фіксує передачу за чинними правилами.
Локальна відсутність claim не доводить вільність задачі на іншій машині.

Спостережений власник #487/#524/#525/#968/#620/#533:
`01a101d5-3f85-73c2-b975-be7591970e00`. #1185 зайнятий чатом `dsh-web-w1185`
за GitHub-коментарем. #1265 і #1271 вже мають відкриті PR. Цей план не
передає їх іншому виконавцю. Для аудиту плану залучено 3 read-only агенти: dependency graph, ownership/resources, bug coverage. Implementation агентів, worktree та нових claims цей план не створив.

Усередині одного зрізу ролі виконують послідовні передачі: planner передає межі й oracle;
implementer — вузький diff і результат; reviewer — конкретні findings;
tester — фактичні результати й межі доказу; docs_sync — актуальний ledger.
Кожен зупиняє передачу на missing input, required failure чи порушенні меж.

Нова технічна задача потрапляє в критичний шлях лише з названим дефектом
або перевіркою, яку вона розблоковує. Уже відомі технічні дефекти з R0
не губляться; широкі дослідницькі вимоги отримують обмежену, чесну відповідь
у своїх issues. Чинні обов'язкові перевірки не вимикаються.

## Етапи доставки

Усі блоки нижче мають status draft: це стан нового релізного доручення,
не оцінка активності інших чатів. Їхня чинна робота описана окремо в inventory.
Таблиця R0–R10 задає **гейти остаточного приймання**, не чергу початку
кодування. Незалежні зрізи стартують одночасно після перевірки свого claim
та потрібного interface-контракту. Одночасного запису спільних файлів немає.

| task_id | Результат | acceptance_dependencies | status |
| --- | --- | --- | --- |
| R0 | Відновлений стенд, замкнений реєстр дефектів і придатні інструменти | none | draft |
| R1 | Безпечний клієнт і перевірений план cloud recovery | R0.ENV для live/build | draft |
| R2 | Локальні книги без дублювання, з правильним часом і сховищем | R0.ENV для device/build | draft |
| R3 | Плеєр та імпорт захищені від повторів і пізніх результатів | R0.ENV для device/build | draft |
| R4 | Відгуки й кеші витримують мережеві збої та перезапуск | R1.LIVE лише для backend-приймання | draft |
| R5 | Пошук SluhayUA, звук і спільні добірки прийняті наскрізно | R1.LIVE, R0.ENV; власні SEARCH/BLOCKS/SERVER | draft |
| R6 | Стабільний runtime і доведена користь рекомендацій | R0.ENV для native/build; чинний quality protocol | draft |
| R7 | Дублі об'єднуються без втрати даних | Інтегровані schema/writer/review contracts і closure | draft |
| R8 | Завершені нагороди й зручне керування книгами | Chapter/ImportPlan contract; переданий search novelty seam | draft |
| R9 | Підписаний RC перевірено при оновленні з v1.5 | R0, R1, R2, R3, R4, R5, R6, R7, R8 | draft |
| R10 | Опубліковане оновлення з перевіреними користями | R9 | draft |

## Паралельне виконання

**Implementation, integration та acceptance — три різні залежності.**
`code_dependencies` нижче блокують початок залежного коду. Передача
спільного файла блокує лише запис у цей файл. Backend/SDK/device гейт
блокує відповідний запуск і живе приймання, але не незалежну підготовку.

### Незалежні зрізи та справжні залежності

Усі нові доручення мають status draft до передачі. Ідентифікатори нижче
уточнюють батьківські R-блоки; критерії й rollback успадковано від них.

| task_id | code_dependencies | Окремий результат | Live / integration gate |
| --- | --- | --- | --- |
| R0.ENV | none | Придатний SDK/стенд і підтверджений heavy слот | Чинний recovery owner; не блокувати source/review |
| R0.SAFETY | none | #1271/#1273/#1280: tmp/memory/cleanup | Уже відкритий #1275 зберігає свого власника |
| R0.EVIDENCE | none | #1265/#1270/#1276–#1279/#1281 та #1227–#1229 | Власні bounded outcomes; у критичний шлях лише конкретний failing required check |
| R1.CLIENT | none | Сумісна client identity/recovery policy | App/UI integrator; old-profile upgrade |
| R1.RULES | none | Авторство, schemas й restrictive Rules | Security writer; live Enforcement/rollout окремо |
| R1.TRANSPORT | none | Headers, MIME, Worker/proxy limits | Worker/proxy writer; shared Cast boundary з R2.CAST |
| R1.LICENSES | none | Фактичні notices #1237 | Звірка фінального APK/Web composition у R9 |
| R1.LIVE | R1.CLIENT, R1.RULES | Дозволений реальний cloud contract та owner recovery | Стенд і окреме production рішення |
| R2.URI | none | Аддитивний document URI seam #1186 | Чинний власник #1185 |
| R2.REFERENCE | R2.URI | T1+T3 разом: reference import і rescan | Import writer; stable Chapter/SourceTrack contract |
| R2.RECOVERY | none після URI/recovery interface freeze | T2/#1230: відновлення доступу без другої картки | Local інтеграція, grants і exact identity |
| R2.DURATION | none | #1187: metadata reader/completeness/completion | Playback writer для підключення; перевірка старого і reference Source |
| R2.STORAGE | none | #1163/T5: фактичні байти й scoped cleanup | Один OfflineDownloads writer; old/new Source acceptance |
| R2.CAST | none після Upstream interface freeze | T6: content URI transport | Спільна передача PlaybackProxy з R1.TRANSPORT |
| R2.MIGRATE | R2.REFERENCE | T4: перевірене звільнення місця | Recovery/readback/hash guarantees до видалення копії |
| R3.PLAY | none | #1240: спільний початок Play/Bookmark/relisten | Один App/VM інтегратор |
| R3.PLAY-RACE | R3.PLAY | #1241: ownership всієї підготовки | Real Player acceptance |
| R3.IMPORT | none | #1242: один confirmed immutable apply | Один Import/VM інтегратор |
| R3.IMPORT-RACE | R3.IMPORT | #1243: ownership scan/apply | Не чекає завершення #1185 або #1188 |
| R4.WEB | none | Source intent/pagination/cache | R1 передає web reviews/security файл за потреби |
| R4.SNAPSHOT | none | Last-good snapshot та один TTL/clock | SourceCatalog integration |
| R4.REVIEW | none | Review lifecycle і #1226 | #620 owner; backend acceptance після R1.LIVE |
| R5.SEARCH | none | #524: один demand search і playable path | Чинний #1162 owner; local/shared contract |
| R5.BLOCKS | none | #525: Source-labeled new/related blocks | Чинний #1162 owner; freshness/identity contract |
| R5.SERVER | none після shared interface freeze | #1238: owner lease, block identity, server order | Чинний координатор передає seam; Rules тільки security writer |
| R5.TWO-INSTALL | R5.SEARCH, R5.BLOCKS, R5.SERVER | Повне #533 real audio/request/backend acceptance | R1.LIVE та R0.ENV; рівно дві незалежні інсталяції |
| R6.RUNTIME | none | #1282: mode/cards/cancel/retry/native close | Один recommendation owner, device/native приймання |
| R6.INSTALL-CANCEL | none після installer contract freeze | #480 story21: cancel stream/partial | Recommendation owner передає installer/UI seam |
| R6.QUALITY | none після чинного protocol/grant | #487: перевірена quality гіпотеза та genuine GO | NO_GO незмінний до доказу; runtime не чекає цього для власної доставки |
| R6.FIELD | придатний recommendation candidate | Реальні приклади користі/недоречності | Почати одразу, паралельно іншим смугам; не затримувати runtime fix |
| R7.CORE | none після current-schema contract freeze | Work resolver, journal, writer gates | Чинний #968 owner; один DB writer |
| R7.APPLY-UNDO | R7.CORE | Actual apply/retry/reopen/conditional Undo | Closure перевіряється на інтегрованих writers, не блокує CORE |
| R7.PERSON | Work resolver у R7.CORE | Atomic person/alias/facet/index/outbox repair | DB writer; окреме приймання |
| R7.UI | none після preview interface freeze | Preview/conflicts/confirm/Undo controls | Підключення тільки після accepted apply contract |
| R8.REORDER | none після Chapter/ImportPlan interface freeze | #1188: drag handle та accessible controls | Сумісність R3.IMPORT; не чекає всього R2/R5 |
| R8.ADAPTIVE | none | #1235: readable lists, focus і Back | App/UI writer; device boundaries |
| R8.AWARDS | search novelty interface переданий | #1198 плюс regression злитих нагород | Чинний search owner #1162; не чекає всього #533 |

### Слоти й черги

Чотири доступні агентські слоти використовуються як **координатор та до
трьох активних зрізів**. Це локальна місткість чату, не привід відбирати
резервування вже запущених зовнішніх потоків. Звільнений слот бере наступний
готовий незалежний зріз або read-only review. Логічних смуг більше за слоти;
не потрібно запускати вісім агентів одночасно.

- **W0, одночасно:** координатор звіряє claims і необхідні interfaces;
  чинний owner відновлює стенд; доступні workers готують локальні баги,
  Play/import тести та security source review. ENV не затримує кодинг.
- **W1, одночасно:** вільним зрізам надаються 3 слоти: local duration/storage,
  Play/import race, security/host preparation. Зайняті recommendation,
  reviews, source/lease і duplicate core рухаються в чинних owners.
- **W2 — rolling, без бар'єра хвилі:** щойно зріз має reviewed patch,
  інтегратор підключає його. Його worker переходить до наступного готового
  subtask або review; UI components і notices готуються незалежно.
- **W3, по готовності:** local/device, review/backend, two-install/search,
  merge/Undo та recommendation native acceptance отримують ресурсний слот.
  Польова перевірка рекомендацій стартує на першому придатному кандидатові
  паралельно із завершенням решти.
- **W4:** один frozen signed RC для спільного upgrade та pre-release
  приймання. Знайдені дефекти повертаються до своїх owners; інші смуги
  продовжуються. Повтор усіх перевірок потрібний лише за новим ризиком.

Розклад не вимагає чекати завершення всіх tasks однієї хвилі. Для вільного
слота координатор обирає ready зріз без конфлікту файлів: спершу збереження
даних і confirmed bugs, далі найдовші cloud/quality/merge залежності,
потім додаткові фічі. Поки heavy run чекає, worker робить code/review або
невеликі host checks; не запускає ще одну велику збірку й не створює
непов'язаний infrastructure rewrite.

### Матриця спільних записів

Шляхи Kotlin — від `app/src/main/java/com/slukhayka/audiobooks/`.

| Shared area | Єдиний writer / інтегратор | Паралельна підготовка |
| --- | --- | --- |
| ui/MainViewModel.kt, App.kt, navigation wiring | App integration owner | Потоки передають вузькі interface patches; domain modules окремо |
| data/imports/LibraryImport.kt, ImportPlan/Planner та rescan контракт | Import integration owner, узгоджений з #1185 | Reference, lifecycle, reorder й duration через frozen interfaces |
| Player manager / Chapter anchors | Playback integration owner | Attempt gates, metadata reader та UI незалежно |
| data/catalog/SourceCatalog.kt, collective contracts, search novelty/gap-fill | Чинний source coordinator; передачі від #620 і до #1198 | Codecs/adapters/tests окремо; DB/Rules через своїх writers |
| DAO, Entities, DB, migrations, app/schemas/**; Web schema/IDB | DB integration owner | Merge/catalog/module source окремо; номер міграції видає тільки writer |
| OfflineDownloads, cleanup state | Storage writer | Files inventory і local availability policy окремо |
| firestore.rules, Worker index/registry, web reviews store, PlaybackProxy | Security writer із явною передачею наступному owner | Codecs/adapters/negative tests незалежно; без паралельного overwrite |
| data/recommend/**, native runtime, frozen eval inputs | Чинний #487 owner | Runtime й quality різні receipts; перекриті #1282/#1139 diff інтегрувати одним owner |
| Home/Library screens, import sheet, спільні strings | UI/resources integration owner | Ізольовані composables й локалізаційні patches; duplicate keys перевіряє writer |
| Gradle/workflows/package manifests | Build/dependency integration owner | Test/source review незалежно; dependencies не оновлювати з кількох смуг |

Це ролі для передач, не автоматичні призначення нового виконавця.
Shared-файл інтегрується короткою послідовною чергою, а не блокує всю смугу.
Зміна frozen API повідомляється всім його споживачам до інтеграції.

### Worktree та перевірки

Нова вільна смуга працює в окремому clean worktree від перевіреного main
`37832cb7b8715c231083b0d4345cbcc52bda528f` або свіжішого повторно звіреного SHA.
Поточний checkout із незакоміченими security-змінами не є спільною базою.
Зайняті смуги зберігають свої worktree й приватні recovery/evidence;
актуальний main інтегрує їхній власник після аудиту.

Перед передачею записати issue/subtask, owner, allowlisted files, worktree,
base SHA, frozen interface, oracle, planned targets та дозвіл resource slot.
Невідомий owner або occupied claim допускає read-only review, не реалізацію.
PR-и лишаються до main за CONTRIBUTING. Один clean release integration
checkout збирає accepted main changes; не cherry-pick чужих private patches
без передачі. Після кожного merge перевірити нову зону сумісності.

Одночасно зі збіркою можуть йти невеликі Python/shell/Node host checks,
source review й легкий typecheck, якщо вистачає ресурсів. Gradle,
Room/Robolectric/Compose, APK, emulator/two-install і native eval на одному
host використовують один heavy слот. Окремий уже наявний CI host може
працювати паралельно за своєю квотою; нового сервера чи платної runner
інфраструктури цей план не замовляє. `test-changed.sh` перед запуском
перевірити на selector fallback, щоб localized diff не став full suite.

Release gate збирає результати по готовності. Runtime рекомендацій може
бути встановлений і прийнятий раніше за quality. Але для обраної обіцянки
«всі відомі баги + корисніші рекомендації» R6.QUALITY лишається незнятим
гейтом фінального оновлення. Заміна цієї обіцянки лише чесним mode потребує
явної зміни обсягу власником; поточний план цього не робить.

### R0 — стенд і відомі технічні дефекти

task_id: R0
status: draft
priority: P0 для блокерів запуску; P2 для решти відомих tooling-дефектів
owner_role: release_coordinator; recovery лише через чинного власника слота
acceptance_dependencies: none
issues: #1265, #1270, #1271, #1273, #1276–#1281, #1227–#1229
blocked_by: спостережений build-slot CLOSED_INTERRUPTED_SDK_INPUTS_MISSING_RECOVERY_REQUIRED
scope_in: підтвердження SDK/стенда; memory/tmp бюджети; відомі неправильні вердикти та cleanup; мінімальна документація
forbidden_areas: видалення чужих evidence; зняття claims; нова загальна система доказів без конкретного дефекту
acceptance_criteria: власний процес має кероване завершення; cleanup не залишає свої дерева; потрібні перевірки дають правдивий результат; усі рядки R0 мають перевірену резолюцію
verification_strategy: наявні host-сценарії та відтворення з відповідного issue; обидві гілки refusal/cleanup; ресурси не виснажувати заради тесту
rollback_plan: повернути власний вузький diff; зберегти evidence й snapshots; не чіпати чужі процеси
prompt: RESUME_FROM inventory і актуальний claim; віднови придатність одного запуску та усунь названі tooling-дефекти. verification_strategy — штатні host-сценарії, конкретний oracle issue, без повного Gradle-прогону.

### R1 — безпечне оновлення й хмарні дані

task_id: R1
status: draft
priority: P0
owner_role: security_recovery_owner, визначити за гілкою codex/security-cloud-recovery
acceptance_dependencies: R0.ENV для live/build
issues: #1237; S1–S6 із BUG-REGISTER.md, canonical recovery issue ще не створено
blocked_by: актуальні production Rules/Enforcement і стан старих профілів не звірено цим планом
scope_in: інтеграція перевірених security-змін; серверне авторство; App Check; recovery старих профілів; redirect/header/proxy межі; notices
forbidden_areas: відкриття старих Rules; масове скидання паролів; ротація без перевірки власників; слабше шифрування заради міграції
acceptance_criteria: старі public bindings недоступні; чужі записи заборонені; справжні клієнти мають дозволений шлях; rollout і recovery не блокують власників непомітно; Worker/proxy межі перевірені
verification_strategy: Rules emulator; підписаний Android і Web із справжньою атестацією; authorized staging/production contract; cast і transport negative paths; dependency та notices audit
rollback_plan: зберегти restrictive containment; відкотити Worker/клієнт сумісним пакетом; cloud/credential зміни мають окремий forward recovery, не звичайний code revert
prompt: RESUME_FROM ADR-0055, security audit і актуальна recovery-гілка. verification_strategy — серверне авторство, реальні токени, збереження доступу старих власників. Підготуй rollout до окремого production-рішення; історичний emulator PASS не є живим acceptance.

### R2 — локальні книги

task_id: R2
status: draft
priority: P1
owner_role: чинний координатор #1185; суміжні вільні issues після передачі
acceptance_dependencies: R0.ENV для device/build
issues: #1185, #1187, #1163, #1230
blocked_by: #1185 зайнятий; PR #1186 поки не перемикає поведінку імпорту
scope_in: T1+T3 разом; T2 recovery; T4 безпечна міграція; T5 сховище; T6 content-URI cast; background duration reader
forbidden_areas: видалення єдиної копії; hash-only за назвою; підміна Chapter/Edition identity; duration з бітрейту чи розміру
acceptance_criteria: імпорт не копіює аудіо; rescan працює; відновлення доступу зберігає книгу й anchors; міграція видаляє копію лише після читабельності оригіналу й точного збігу; фактичні байти показані окремо; очищення не скидає локальні Sources
acceptance_criteria_extra: неповний час позначений невідомим; старі книги отримують метадані у фоні; позиція однієї глави не порівнюється з часом книги; незавершена книга не стартує спочатку; доступний content-URI cast не регресує
verification_strategy: змішана тека, новий і старий імпорт, grant revoke/restore, moved/missing/changed files, offline Play, clear downloads, restart, multi-chapter resume й реальний Cast
rollback_plan: до видалення копії зберегти шлях recovery; незворотні file/migration кроки — лише після readback; code revert не відновить видалене аудіо
prompt: RESUME_FROM #1185 T1–T6 і чинний PR. verification_strategy — Source за посиланням, rescan, sole-copy safety, duration completeness, storage cleanup та Cast. Перший кандидат показує видимий результат, не лише нове поле scanner.

### R3 — надійний плеєр та імпорт

task_id: R3
status: draft
priority: P1
owner_role: один playback/import writer після claim і узгодження shared ViewModel
acceptance_dependencies: R0.ENV для device/build
issues: #1240 -> #1241; #1242 -> #1243
scope_in: єдиний початок Play/Bookmark/relisten; ownership підготовки; immutable confirmed ImportPlan; ownership scan/apply
forbidden_areas: другий Player чи writer; перенесення позиції між начитками; rollback валідного старого apply лише через зміну UI
acceptance_criteria: A повільна/B новіша не повертає A; clear/delete інвалідує підготовку; Bookmark після помилки має правильні anchor й recovery; подвійний tap запускає один apply; старий scan/apply не змінює preview B
verification_strategy: керовані async тести обох порядків завершення; cancel/failure/retry; реальні UI дії Play/Bookmark/import без sleep у причинному тесті
rollback_plan: вузький revert seam до зміни persisted format; зберегти вже committed імпорт і provenance
prompt: RESUME_FROM чотири issues та актуальний ViewModel. verification_strategy — RED на конкретній гонці, GREEN через спільний interface, потім видимий busy state і Play на пристрої.

### R4 — відгуки й останні придатні дані

task_id: R4
status: draft
priority: P1
owner_role: чинний координатор #620/#1226, PR #1181
acceptance_dependencies: R1.LIVE лише для backend; specific security interfaces
issues: #620, #1226; acceptance #533 у частині backend
blocked_by: живе Android/backend приймання не завершено
scope_in: Web A/B/A pagination; Feed Snapshot TTL/empty/failure; review pending/ACK/delete/retry та process restart
forbidden_areas: permanent delete tombstone; змішування Listener Review і Narration Rating; розмивання original user stories #620
acceptance_criteria: стара сторінка не змінює новий Source/cursor/cache; failure/empty не стирає last-good snapshot; видалений review не повертається при refresh-before-ACK; відмова відновлює належний стан; нові дії й identity захищені
verification_strategy: усі чинні stories #620; real Room/IndexedDB; offline/reconnect/restart; delayed response та DELETE ACK; справжній backend після R1
rollback_plan: зберегти confirmed стан та сумісний outbox; code revert не замінює відкат серверних записів
prompt: RESUME_FROM PR #1181, #1226 і актуальні claims. verification_strategy — причинний DELETE-тест, Web race, process death, offline/reconnect та backend ACK; частковий каталоговий probe не закриває весь #620.

### R5 — пошук і відкриття книг

task_id: R5
status: draft
priority: P1
owner_role: чинний координатор #524/#525/#533; #1238 лише після передачі суміжного шва
acceptance_dependencies: R1.LIVE, R0.ENV; SEARCH/BLOCKS/SERVER integration
issues: #524, #525, #1238, #533; umbrella #521
blocked_by: реальний provider audio/друга інсталяція/backend відкриті; server lease ще потребує доставки
scope_in: demand search; related/new blocks; owner-token lease; block identity; server delta order; two-install acceptance
forbidden_areas: crawler; shared приватний запит; публікація до реального playback verdict; закриття parent лише за unit/CI
acceptance_criteria: local/shared перед Source; один miss — один запит; правильні глави й звук; клієнт B бачить shared картку без повторного search; related A/B співіснують; старий власник не переписує новий; offline лишає last-good
verification_strategy: два незалежні клієнти, реальні Source і backend, лічильники запитів, budget/fallback, TTL/expiry/retry, normal startup/Home; усі original AC #533
rollback_plan: припинити нову публікацію без очищення останнього придатного каталогу; зберегти backwards-compatible читання
prompt: RESUME_FROM PR #1162 та останні comments #533. verification_strategy — source search до звуку й delta другого клієнта, server lease з late owner, actual request counters; accepted API35 UI не є realproviderAudio.

### R6 — рекомендації, які допомагають обирати

task_id: R6
status: draft
priority: P1
owner_role: чинний координатор #487/#480, PR #1139 і #1282
acceptance_dependencies: R0.ENV для native/build; чинний quality protocol
blocked_by: quality NO_GO; Android/native acceptance відкриті
scope_in: окрема доставка runtime; завершення cancel installer; чинний E5 inference contract; узгоджений алгоритмічний крок за перевіреною гіпотезою
forbidden_areas: зниження thresholds; підміна frozen inputs; keyword як непозначений E5; повтор експерименту без протоколу й чинного дозволу
acceptance_criteria_runtime: mode/cards атомарні; stale pass не повертає FULL; cancel не стає fallback; retry не завантажує модель; native close чекає active pass; cancel download закриває stream і прибирає тільки власний partial
acceptance_criteria_quality: чинний eval на реальному каталозі проходить original gate; завершені твори ранжуються краще за baseline за його метриками; quality GO не виводиться з runtime PASS
verification_strategy: separate runtime/native/UI proofs; один наступний діагностичний крок на одну зафіксовану гіпотезу за рішенням власника; genuine eval artifact; живий тиждень користування як окрема польова оцінка
field_acceptance: до фінального висновку про користь — тиждень реального користування власником із короткими прикладами доречних/недоречних добірок; без вигаданого відсотка поліпшення
field_version_contract: записати candidate SHA, ranking/model/input versions; зміна ranking потребує оцінки нової версії, старий NO_GO тиждень не є її прийманням; сторонні RC-виправлення потребують короткої регресії, не автоматичного повтору всього тижня
rollback_plan: повернути попередній перевірений ranking; чесний degraded mode; готові assets/cache не видаляти через cancel; нову quality-обіцянку прибрати за регресії
prompt: RESUME_FROM останні #487 comments і claims. verification_strategy — runtime відділений від quality; існуючі freezes/gates незмінні. Принеси перевірену гіпотезу та готовий користувацький результат, без серії довільних replay. NO_GO блокує обіцянку виправленої якості в цьому релізі.

### R7 — дублі та ідентичність

task_id: R7
status: draft
priority: P1 для збереження даних; P2 для UI
owner_role: чинний координатор #968, PR #1161
acceptance_dependencies: integrated schema/writer/review contracts та all-table closure
blocked_by: current public PR — preview; production apply/Undo й UI не прийняті
scope_in: approved Work-only preview; redirects; journal; writer/startup gates; apply/retry/Undo; окремий atomic person repair
forbidden_areas: злиття Edition/таймкодів; display-only ремонт замість closure; cross-device redirects без окремого scope
acceptance_criteria: одна Work-картка зберігає всі Edition/Source/bookId IDs, позиції, bookmarks/files; reopen/retry не повертає дубль; stale preview відмовляється; Undo не стирає нову дію; active writers/tombstones блокують apply
verification_strategy: current-schema all-table closure, crash/reopen/retry, two-edition positions, concurrent changes/Undo; device preview й підтвердження; atomic person derived identities
rollback_plan: durable journal і перевірений conditional Undo; schema/code revert не відкочує перенесені дані автоматично
prompt: RESUME_FROM approved D1 owner decisions, актуальний public HEAD і recovery-status чинного власника. verification_strategy — all-table losslessness та actual apply/reopen/Undo; старий PASS preview не доводить готового злиття.

### R8 — користі вже зробленого й завершення керування

task_id: R8
status: draft
priority: P2
owner_role: один UI writer; award/search шов передає чинний координатор
acceptance_dependencies: Chapter/ImportPlan contract; переданий search novelty seam
issues: #1188, #1198, #698, #1235; за готовністю #831/#753
scope_in: drag handle + доступні up/down; «Археолог» за успішний новий імпорт; readable adaptive lists; регресія злитих нагород/статистики; provenance/rail
forbidden_areas: новий повноекранний editor; зменшення шрифту; повтор already-merged awards/player/grid; Telegram без explicit auth/membership
acceptance_criteria: reorder зберігає Chapter anchors, Save/Cancel і autoscroll; cancel/stale/failed import не дає award; narrow list row читабельний; Back/resize відновлює вибір і focus; нагороди відповідають фактам
verification_strategy: UK/EN та підтримувані локалі для нових рядків; TalkBack/клавіатура/великий шрифт; 599/600, 839/840 dp; нове й існуюче аудіо після reorder; regression вже закритих bugs із реєстру
optional_delivery: нова rail #831 та повне Telegram umbrella-приймання #753 лише за готовності backend й підтверджених даних; це features, не прихований перенос підтвердженого дефекту
rollback_plan: повернути контейнер/керування без зміни IDs і фактів нагород; не видаляти вже створені award facts
prompt: RESUME_FROM merged benefits в inventory й рішення #1188/#1198/#1235. verification_strategy — видима користь у release build, stable anchors, accessible controls; не реалізуй вдруге side player чи вісім уже злитих нагород.

### R9 — приймання кандидата

task_id: R9
status: draft
priority: P0
owner_role: release_tester з reviewer; один власник build-слота
acceptance_dependencies: R0, R1, R2, R3, R4, R5, R6, R7, R8
issues: #1237, #817; BUG-REGISTER.md
scope_in: frozen candidate SHA; actual release APK; upgrade v1.5; new install; platform ledger; full pre-release checks
forbidden_areas: destructive reinstall замість upgrade; stale review/checks; висновок «усі баги виправлено» за кількістю closed issues
acceptance_criteria: усі confirmed дефекти реєстру мають перевірену резолюцію; усі promised користі у signed candidate; upgrade зберігає дані; потрібні CI/gates зелені; signer/version/checksum перевірені; licenses готові
verification_strategy: acceptance table нижче; build і checks прив'язані до одного candidate SHA; Web в Chromium/Safari для змінених сценаріїв; full parity лише за #557/#697 acceptance
rollback_plan: зберегти last-good signed APK і schema/cloud compatibility; не пропонувати downgrade з несумісною Room; tested recovery/forward fix для міграцій
prompt: RESUME_FROM frozen release SHA й реєстр. verification_strategy — signed upgrade from v1.5, all promised journeys, full pre-release gates; на failure поверни конкретну задачу власнику, не повторюй до випадкового GREEN.

### R10 — випуск

task_id: R10
status: draft
priority: P1
owner_role: release_owner
acceptance_dependencies: R9
scope_in: фактичні release notes; остаточні версії Android/Web; публікація verified package; коротке post-release приймання
forbidden_areas: неперевірені обіцянки якості, Web parity чи cloud sync; production-зміни поза погодженим rollout
acceptance_criteria: опубліковано саме перевірений SHA/APK; користувач отримує правильний пакет; download/install/start/Play після публікації перевірені
verification_strategy: artifact checksum/signature та реальне оновлення; smoke основного локального/мережевого сценарію; cloud-write failure/recovery за rollout
rollback_plan: прибрати уражений пакет із нових завантажень, оголосити точний дефект, доставити перевірений forward fix; production — власний containment/rollback R1
prompt: RESUME_FROM R9 receipt. verification_strategy — точний пакет, реальна доставка й smoke. Публікуй після рішення власника; описуй лише те, що прийнято.

## Verification Policy

tests_run_for_this_plan: none
commands_run_for_planning: gh issue list open із comments; gh pr list open/merged; gh issue list closed bug; gh api commits/main; читання docs і shared claims
stop_on_failure: true
evidence_record: issue/PR URL; exact candidate SHA; команда; фактичний результат; межа перевірки; location справжніх artifacts

Під час реалізації — тільки змінені targets: `scripts/test-changed.sh --base
origin/main` після перевірки його актуальної мапи або точні `--tests` класи.
Full-suite потрібен на R9 як pre-release verification, не на кожну правку.
Перед запуском звіряти актуальний build-слот, resources і інструкції поточного main.

commands_planned:
- `scripts/test-changed.sh --base origin/main` для відповідного implementation diff.
- Точні JVM targets із чинної гілки; наприклад LocalFolderScannerTest, ChapterDurationPolicyTest, OfflineDownloadsTest, ListenerReviewLifecycleTest, RecommendationModelPolicyTest. Нові тести гонок визначити в task prompt перед кодуванням.
- `npm --prefix web run typecheck`, `npm --prefix web test`, `npm --prefix web run build` для зміненого web-кандидата.
- Rules matrix та security-rules за чинним recovery runbook; production перевірка окремо від localhost.
- `scripts/run-instrumented-suites.sh` на придатному device/AVD, плюс journey-specific targets після визначення власником.
- Повний штатний CI кандидата; `scripts/test-all.sh` на R9 якщо відповідне покриття не підтверджене CI або release policy цього вимагає.
- `scripts/pre-release.sh` на commit, який буде теговано: live YouTube canary є hard gate.
- Штатний signed release workflow на тому самому SHA; signature/checksum/package/version inspection.

| Сценарій | Oracle для RC |
| --- | --- |
| Upgrade v1.5 → candidate | Work/Edition/Source, прогрес, bookmarks, файли й profile access збережені; міграції повторно не руйнують дані |
| Local folder та storage | Читабельний оригінал; правильний completed/unknown duration; фактичний облік; cleanup зберігає локальне аудіо |
| Local recovery | Revoke/restore/move не створює другу картку; інша начитка не отримує чужу позицію |
| Play/import races | Старий async результат не керує новим наміром; confirmed plan записується один раз |
| Reviews/catalog | Offline/reconnect/restart, save/delete ACK, A/B/A pagination не змішують дані й identity |
| Search/shared catalog | Реальний звук, два клієнти, actual requests, budget і fallback; related A/B не затираються |
| Recommendations | Genuine quality GO; реальні UI/native cancel/retry; польова оцінка за R6 |
| Duplicate merge | All-table invariants, restart/retry/Undo та збереження нових дій |
| Accessibility/adaptive | Вузьке/широке вікно, boundary dp, великий шрифт, TalkBack, focus і Back |
| Platform differences | Web дефекти перевірено в Safari PWA/Chromium; full parity не заявлено без #557/#697 |
| Security/cloud | Чужі читання/записи відхиляються; справжні власники відновлюють доступ; actual attestation/rules/rollout |
| Distribution | Правильний signer, checksum, versionCode, release keys; справжнє оновлення та старт |

## Як бачити поступ

Для кожного зрізу показувати одну коротку картку: **що змінилося для
слухача → де перевірити → кандидат і доказ → що ще блокує**. Посилання на
встановлюваний перевірений пакет або демонстрація на пристрої потрібні для
видимого UI-результату за ADR-0017. План, source review та кількість тестів
не є такою демонстрацією.

Стан delivered означає перевірену користь у кандидатові. Стан released —
саме опублікований і перевірений пакет. «Злито в main» — окремий проміжний
стан. Щоденний звіт потрібен лише під час активного виконання; автоматизацію
чи повідомлення в інші чати цей план не створює.

## Синхронізація документації

wiki_pages_to_read_before: CONTEXT.md; CONTRIBUTING.md; docs/voice.md; релевантні ADR; work-coordination; конкретні issues/comments
wiki_pages_to_update_after: BUG-REGISTER.md; цей план; canonical issues; README/platform ledger за #817; release notes; доменні ADR за зміною контракту
wiki_facts_to_capture: фактична поведінка; verified version/SHA; залишки; сумісність і recovery
wiki_do_not_store: credentials; tokens; cookies; raw listener identities; сирі QA логи/скриншоти/бінарники

Наступна передача після перегляду обсягу — canonical release PRD у GitHub
з посиланнями на наявні issues. Нові delivery issues створюються лише для
справді відсутніх зрізів, зокрема release/cloud recovery; старі задачі не
дублюються. Планувальні файли не закривають implementation issues.
