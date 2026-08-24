# Приватні персоналізовані рекомендації

> Дата: 2026-08-24
>
> Статус: дизайн схвалено користувачем
>
> Обсяг: локальне навчання, явний feedback UX, керування виключеннями,
> згода та майбутній агрегований спільний ранжувальник

## Контекст

«Слухайка» вже має локальний ряд «Рекомендовано для вас». Заморожена
`multilingual-e5-small` у форматі int8 ONNX перетворює книжковий текст на
384-вимірний embedding. `RecommendationEngine` порівнює кандидатів із
улюбленими, завершеними та нещодавно прослуханими книгами, застосовує ваги
1.0 / 0.8 / 0.6 і повертає десять найближчих результатів.

Поточний механізм не навчається. Він не пам'ятає реакцію на рекомендацію, не
має негативного профілю, не пояснює користувачу керування персоналізацією й
фактично передає кандидату переважно назву та автора, хоча коментарі обіцяють
багатші метадані. Кеш embedding-векторів є лише кешем, не навчанням.

Вбудована модель залишається в APK: офлайн-рекомендації мають працювати одразу
після встановлення. Зменшення APK через ABI splits/App Bundle та R8 є окремим
build-треком і не змінює цей продуктовий дизайн.

## Цілі

1. Рекомендації поліпшуються від досвіду конкретного слухача без мережі.
2. Користувач може явно сказати, що саме не рекомендувати, і скасувати дію.
3. Усі recommendation-specific сигнали прозорі, керовані та придатні до
   скидання без видалення медіатеки чи Listening State.
4. Спільне поліпшення, коли воно з'явиться, не збирає книжкову історію.
5. Мережева участь вимкнена за замовчуванням і потребує окремої згоди.
6. Заморожена E5 лишається енкодером; навчаються лише малі ваги ранжування.
7. Відсутність Firebase, моделі або глобальної конфігурації ніколи не ламає
   локальний ряд.

## Нецілі

- централізоване сховище історії прослуховування;
- fine-tuning або federated fine-tuning E5 на телефоні;
- рекламний профіль, рекомендації між застосунками чи продаж даних;
- трактування відсутності кліку як негативної реакції;
- трактування незавершеної довгої книги як автоматичного негативу;
- запуск спільного навчання до privacy/security review і щонайменше 20
  чинних opt-in receipts;
- narration-specific рекомендації: цей дизайн ранжує Works, не Editions.

## Розглянуті підходи

### A. Лише локальний профіль

Найменша складність і найсильніша приватність. Не дає застосунку колективно
поліпшувати базові ваги. Це обов'язковий перший етап, але не кінцевий напрям.

### B. Локальний профіль плюс агреговані глобальні ваги — обрано

Телефон володіє повною персоналізацією. Опційний сервер отримує один обмежений
градієнт малих ваг на тиждень, а не події чи книги. Цей підхід дає практичний
шлях до спільного поліпшення без data lake поведінки.

### C. Спільне донавчання E5 — відхилено

118-МБ модель, складний secure aggregation, висока вартість, poisoning-ризик і
непропорційний privacy surface. Якість спершу треба вичерпати через багатші
метадані, локальний профіль і малий reranker.

## Архітектурні межі

Система має чотири незалежні блоки:

1. `BookRecommendationText` формує Work-level текст для embedding.
2. `LocalRecommendationProfile` перетворює локальні факти на позитивний і
   негативний профіль та hard exclusions.
3. `PersonalizedRanker` детерміновано оцінює й урізноманітнює кандидатів.
4. `SharedRecommendationLearning` є опційним мережевим адаптером; локальні
   три блоки не залежать від нього.

UI читає готові рекомендації й передає явні команди через модуль, а не пише
Room або Firebase напряму. `MainViewModel` оркеструє навігацію та одноразовий
Snackbar, але не володіє правилами scoring.

## Текст книги та embedding

Embedding належить Work. Текст має стабільний порядок:

```text
Назва: <title>.
Автор: <author>.
Жанри: <genres>.
Серія: <series>.
Опис: <clean effective description>.
```

- Відсутні поля пропускаються без placeholder-тексту.
- HTML, source suffixes і повтори очищаються до embedding.
- Effective description — той самий resolved display value, який UI отримує
  через чинний precedence Metadata Override / Metadata Assertions. Нової
  «канонічної правди» recommendation module не створює й не зберігає.
- Опис обрізається так, щоб весь вхід разом із `passage: ` вміщався в 512
  токенів E5.
- Narrator не входить у Work-вектор: це властивість Edition.
- Однакова нормалізація застосовується до каталогу та бібліотечних сигналів.
- Зміна сформованого тексту змінює версію embedding cache й викликає
  фоновий перерахунок.

## Локальні сигнали

Профіль використовує вже наявні Library Entry, Listening State, playback
events і Listener Review. Новий запис створюється лише для явних
recommendation preferences, яких у цих джерелах немає.

### Позитивні

| Факт | Вага |
|---|---:|
| повторне прослуховування | +1.3 |
| оцінка 5 | +1.2 |
| улюблене | +1.0 |
| завершення | +0.9 |
| оцінка 4 | +0.8 |
| досягнуто 70% | +0.5 |
| досягнуто 30% | +0.25 |

Для одного Work береться найсильніший досягнутий progress tier; 30% і 70% не
сумуються. Решта незалежних фактів сумується, а підсумкова вага Work
обмежується діапазоном `[-1.5, +1.5]`, щоб одна книга не поглинула профіль.
Якщо Work має кілька Editions, progress/completion/relisten агрегуються до
Work за найсильнішим сигналом, а не сумуються між начитками.

### Негативні

| Факт | Вага/наслідок |
|---|---:|
| оцінка 1 | -1.2 |
| оцінка 2 | -0.8 |
| «Менше схожих» | -1.0 до негативного centroid |
| «Цю книгу» | hard exclude Work |
| «Цього автора» | hard exclude author |

Оцінка 3 нейтральна. Відкриття картки, короткий старт, відсутність кліку,
пауза та незавершення не змінюють смак у першій версії. Вони надто
неоднозначні; явність важливіша за кількість сигналів.

Слабкі progress-сигнали лінійно згасають від повної сили на 30-й день до нуля
на 180-й. Улюблене, оцінки, завершення, relisten і явні виключення не
згасають.

## Явні preferences

Room зберігає мінімальну таблицю `recommendation_preferences`:

```text
id             stable local primary key
kind           HIDE_WORK | REDUCE_SIMILAR | HIDE_AUTHOR
targetKey      Work mergeKey або normalized author key
sourceWorkKey  Work, з картки якого створено preference
createdAt      local epoch millis
```

Embedding у таблицю не копіюється: `REDUCE_SIMILAR` читає або перераховує
вектор `sourceWorkKey`. Автор блокується за тією самою нормалізацією імені,
яку використовує Work identity. Опція автора не показується для порожнього
або ненормалізовуваного author value. Майбутній stable Person id може
замінити ключ через окрему міграцію, але не входить у цей дизайн.

Undo видаляє щойно створений preference або відновлює попередній snapshot,
якщо команда замінила наявне правило.

## Профіль і scoring

Нормалізований позитивний centroid є зваженим середнім позитивних Work
векторів. Негативний centroid так само формується з негативних. Кандидат,
який потрапив у hard exclusion або вже є в бібліотеці, відсіюється до scoring.

Початковий score:

```text
semantic = cosine(candidate, positive)
           - 0.70 * cosine(candidate, negative)

score = 0.60 * semantic
      + 0.15 * authorAffinity
      + 0.10 * genreAffinity
      + 0.05 * seriesAffinity
      + 0.10 * freshness
```

Affinity-компоненти нормалізовані в `[0, 1]`; `semantic` затискається в
`[-1, 1]`. Ваги є версіонованою конфігурацією. Локальна конфігурація завжди
існує; глобальна може лише замінити її після перевірки.
Невідомий arrival/published time дає freshness `0`, а не вигадану дату.

Після scoring застосовується diversity pass:

- максимум дві книги одного автора;
- максимум одна книга однієї серії;
- вісім найкращих персональних результатів;
- два exploration results із кандидатів поза найближчим кластером, але з
  додатним semantic score;
- якщо exploration-кандидатів немає, місця займають персональні;
- без позитивного профілю ряд «Рекомендовано для вас» не показується; окремі
  editorial/collection rails залишаються cold-start поверхнею.

Reason chip бере найближчий позитивний Work і показує «Схоже на <title>».
Негативні сигнали ніколи не потрапляють у пояснення.

## Feedback UX

На рекомендаційній картці є overflow `⋮`. Пункт «Не рекомендувати…» закриває
primary menu і в тій самій anchor-позиції відкриває компактне reason menu:

1. «Цю книгу»;
2. «Менше схожих»;
3. «Цього автора» — лише за наявності надійного author key.

Окремого confirmation dialog немає. Вибір застосовується одразу, картка
плавно виходить зі списку, а Snackbar `Long` показує:

```text
Налаштування рекомендацій змінено    Скасувати
```

Snackbar undo відновлює точний попередній стан. Після завершення Snackbar
дія лишається доступною для відкату через settings.

## Налаштування

`Налаштування → Рекомендації` містить:

- «Персональні рекомендації на цьому пристрої» — default ON;
- пояснення локальних сигналів і напис «Історія не залишає пристрій»;
- «Не рекомендувати»: окремі списки книг і авторів із «Відновити»;
- «Скинути навчання рекомендацій» з confirmation dialog, який називає точний
  scope;
- «Допомагати спільно покращувати рекомендації» — default OFF;
- поточний статус згоди та посилання на повне privacy disclosure.

Вимкнення локальної персоналізації приховує персональний ряд, але не видаляє
профіль. «Скинути навчання» видаляє explicit preferences і локально адаптовані
ваги. Library Entry, Listening State, reviews та playback history не
видаляються й після скидання знову формують базовий deterministic profile.
Confirmation прямо називає цю межу; кнопка не обіцяє «забути історію», якої
recommendation module не володіє.

## Момент і форма згоди

Застосунок має право запропонувати opt-in після двох одночасних умов:

1. щонайменше три взаємодії з рекомендаційними картками;
2. хоча б одну рекомендовану книгу реально запущено.

Взаємодією тут є відкриття detail page з рекомендаційної картки, явний
feedback або старт прослуховування з цього шляху. Impression/поява картки на
екрані не рахується.

З'являється неблокувальна картка під recommendation rail. Вона веде на
окремий disclosure screen. Жодного pre-checked control немає.

Disclosure називає:

- controller і контакт;
- конкретну мету — навчання малих глобальних ranking weights;
- які поля відправляються й які ніколи не відправляються;
- Firebase як processor та фактичні строки його технічних логів;
- weekly pseudonym і межі його захисту;
- cohort threshold 10;
- видалення raw contribution наприкінці тижня;
- право відмовитися й відкликати згоду без втрати локальної функціональності;
- неможливість вилучити один внесок із уже створеного незворотного агрегату.

«Не зараз» ставить 90-денний cooldown. Допускається одне повторне
контекстне нагадування; після другої відмови тільки settings може відкрити
opt-in. Нова мета, новий payload або послаблення privacy safeguards потребує
нової версії згоди. Посилення safeguards без розширення мети не потребує
повторного запиту.

## Consent receipt

Мінімальний `recommendation_consents/{uid}` відділений від contribution data:

```text
consentVersion
grantedAt
revokedAt?
```

Це псевдонімізований персональний запис, потрібний для перевірки opt-in і
демонстрації згоди. Він не містить поведінки. Після відкликання або видалення
профілю receipt зберігається не довше 12 місяців у мінімальній формі, а потім
видаляється. До production цей строк і controller disclosure проходять
юридичну перевірку; shared phase фізично лишається вимкненою до її завершення.

## Щотижневий contribution

Телефон не надсилає події. Раз на ISO-тиждень він локально обчислює один
bounded update для п'яти глобальних коефіцієнтів:

```text
semantic, authorAffinity, genreAffinity,
seriesAffinity, freshness
```

Вхід береться лише з локальних outcome labels за цей тиждень. Payload не має
Work id, назв, авторів, жанрів, оцінок, progress, timestamps, locale, device
model, Android ID, nickname або recovery material.

```text
schemaVersion
baseModelVersion
gradient[5]       L2-clipped to 1.0
consentVersion
epoch             ISO week, не точний час
weeklyId
```

Достатнім локальним сигналом є хоча б один новий durable outcome за тиждень:
30%/70%, завершення, relisten, favorite, rating або explicit feedback.
Відсутність такого outcome означає відсутність contribution, а не нульовий
fabricated update.

П'ять коефіцієнтів є scoring weights і завжди проєктуються на simplex із
сумою `1.0`; кожен має bound `[0.02, 0.80]`. Exploration лишається
непорушним продуктовим guardrail `0.20` (два слоти з десяти), як і author /
series diversity caps; shared learning не має права прибрати їх. Candidate
update використовує learning rate `0.02`; зміна одного тижня не може
пересунути жоден коефіцієнт більш ніж на `0.02` після projection.

## Weekly pseudonym і admission

Callable backend вимагає Firebase Auth та App Check. Він перевіряє чинну
згоду й обчислює:

```text
weeklyId = HMAC(serverSecret, uid | ISO-week)
```

UID не записується в contribution document і не потрапляє в application
logs. Один weeklyId приймається один раз. Наступний тиждень створює інше
значення.

Це псевдонімізація, не абсолютна анонімність. Оператор, який контролює secret
і consent UID, технічно може повторно обчислити weeklyId. Схема захищає від
звичайного cross-week analysis і витоку contribution database без secret,
але не від зловмисного controller. Disclosure не має права називати її
«повністю анонімною».

Backend не зберігає IP або request body в application logs. Неминучі edge /
processor logs документуються з найкоротшим доступним retention.

## Cohort aggregation

- Epoch закривається наприкінці ISO-тижня.
- Лише 10 або більше distinct weeklyId дозволяють агрегування.
- Внески L2-clipped до `1.0` повторно на сервері.
- Агрегатор усереднює gradients, додає noise до агрегату, а не до
  індивідуальної історії, та формує candidate weight version.
- Noise mechanism і privacy budget проходять окремий privacy/security review;
  shared phase не може бути ввімкнена без зафіксованих `epsilon`, `delta` і
  composition policy. Ad-hoc «трохи шуму» заборонено.
- Якщо внесків менше 10, candidate version не створюється.
- У будь-якому випадку raw contributions і weeklyId видаляються після
  закриття epoch.
- Aggregated update, privacy accounting record і підписані model versions
  можна зберігати безстроково: вони не містять індивідуальних рядків.

Нова weight version проходить deterministic offline fixture gate: quality
metrics не гірші за поточні, diversity caps збережено, значення weights у
дозволених bounds. Невдалий candidate відкидається. Застосунок приймає тільки
підписану конфігурацію з монотонною версією; інакше використовує last-known
good або bundled defaults.

## Відкликання

Відкликання згоди:

1. негайно забороняє нові contributions;
2. видаляє локальну unsent weekly queue;
3. позначає receipt як revoked;
4. best-effort видаляє ще не агрегований contribution поточного weeklyId;
5. не вимикає й не скидає local personalization;
6. не намагається вилучити внесок із незворотного агрегату, де окремого
   запису вже немає.

Надати згоду має бути так само легко, як відкликати її: один settings toggle
плюс disclosure, без деградації сервісу після відмови.

## Загрози та протидії

| Загроза | Протидія |
|---|---|
| відтворення історії слухача | payload без книг/подій; один gradient на тиждень |
| cross-week linkage | weekly pseudonym; UID відсутній у contribution store |
| повторні внески | Auth + App Check + один accepted weeklyId |
| poisoning | client/server clipping, один внесок, weight bounds, quality gate |
| витік pending store | псевдоніми, encryption at rest, weekly deletion |
| приховане розширення мети | versioned consent і schema allowlist |
| випадкове блокування | Snackbar undo та settings restore |
| filter bubble | два exploration slots і diversity caps |
| невдала глобальна версія | signed monotonic config та last-known-good fallback |

Threat model не обіцяє захисту від зловмисного controller, який має одночасно
доступ до Auth, HMAC secret і backend. Для цього потрібні blind tokens або
перевірений secure aggregation protocol — окрема майбутня архітектура.

## Відмовостійкість

- Немає ONNX asset/session → `KeywordEmbedder` fallback.
- Corrupt embedding cache → miss і background recompute.
- Немає позитивного профілю → персональний ряд відсутній, без fabricated
  рекомендацій.
- Мережа недоступна → один локальний contribution може повторюватися до кінця
  epoch; idempotency weeklyId запобігає дублюванню.
- Epoch завершився до доставки → contribution видаляється локально, у
  наступний epoch старий payload не переноситься.
- Згоду відкликано → queue очищена до наступної мережевої спроби.
- Backend/schema/config validation fail → локальний ranking продовжується.
- Глобальна конфігурація прострочена → last-known-good; її відсутність →
  bundled defaults.

## Перевірка

### Pure JVM

- формування Work text, відсутні поля, очистка та cache version;
- signal weights, progress tier без подвійного рахунку, decay і clamp;
- positive/negative centroid;
- кожен hard exclusion до scoring;
- deterministic score, author/series caps, 8+2 composition;
- no-signal cold start і honest reason chip;
- undo відновлює точний preference snapshot;
- weekly gradient із п'яти коефіцієнтів не містить Work data й має правильний
  L2 bound.

### Room/integration

- migration додає лише explicit preference table;
- preference переживає restart і видаляється через settings/undo;
- reset не видаляє Library Entry, Listening State, reviews або playback events;
- embedding cache invalidates тільки при зміні книжкового тексту.

### Compose

- primary menu → compact reason menu;
- author option hidden без author key;
- вибір анімує картку та показує Snackbar;
- Snackbar undo і settings restore;
- consent prompt gating, 90-day cooldown і максимум одне нагадування;
- local OFF, reset confirmation і shared opt-in залишаються незалежними.

### Backend/security

- write без Auth, App Check або active consent відхиляється;
- один UID не створює два внески в epoch;
- stored payload schema allowlist не приймає зайві поля;
- cohort 9 не агрегується, cohort 10 агрегується;
- raw documents видаляються в обох випадках;
- revoked consent прибирає pending contribution best-effort;
- logs не містять UID, gradient body або weeklyId;
- invalid/unsigned/regressive weight version не публікується.

### Якість

Наявний `RecommendationEval` лишається regression gate. Додаються diversity,
negative-feedback і cold-start fixtures. Перед shared rollout потрібен
зафіксований baseline Recall@10, NDCG@10, author diversity та pairwise
preference accuracy на saved fixtures. Candidate global version не може
погіршити ці offline metrics. Центральний dismissal rate не збирається й не
використовується як прихована telemetry metric.

## Фази доставки

### Фаза 1 — локальне ядро

Багатший Work text, локальний профіль, deterministic ranker, exclusions,
diversity та eval gates. Жодної нової мережі.

### Фаза 2 — feedback UX

Компактне reason menu, Snackbar undo, settings lists і reset. Жодної нової
мережі.

### Фаза 3 — прозорість і dormant consent

Disclosure, prompt gating, consent state та UI. Shared toggle позначений як
недоступний до готовності backend або прихований у production.

### Фаза 4 — backend prototype

Окреме staging-середовище: consent receipt, App Check admission, weekly HMAC,
schema allowlist, cohort lifecycle та deletion tests. Production upload OFF.

### Фаза 5 — privacy/security gate

Юридична перевірка disclosure/retention, формальна noise mechanism, threat
model review, processor logging audit і documented incident path.

### Фаза 6 — обмежений opt-in rollout

Увімкнення upload тільки після щонайменше 20 чинних opt-in receipts, щоб
тижневий cohort 10 був реалістичним попри неактивність частини аудиторії.
Локальна система лишається авторитетною; shared weights є змінним prior, не
джерелом user history.

## Критерії завершення дизайну

- Локальна фаза не має мережевої залежності.
- Жоден shared payload не містить книжкових або user-history полів.
- «Анонімний» не використовується там, де коректний термін —
  «псевдонімізований».
- Відмова та відкликання не погіршують local recommendations.
- Raw contributions не переживають weekly epoch.
- Shared phase фізично не активується до privacy/security gate.
- UI дає як миттєвий undo, так і довготривале керування виключеннями.

## Нормативні джерела для privacy review

- European Commission, «Application of the GDPR»: псевдонімізовані дані
  лишаються personal data; повна анонімізація має бути незворотною —
  <https://commission.europa.eu/law/law-topic/data-protection/information-business-and-organisations/application-gdpr_en>
- EDPB, «Process personal data lawfully»: згода має бути freely given,
  informed, specific, unambiguous і легко відкликатися —
  <https://www.edpb.europa.eu/sme/be-compliant/process-personal-data-lawfully_en>
- European Commission, data minimisation and purpose limitation —
  <https://commission.europa.eu/law/law-topic/data-protection/rules-business-and-organisations/principles-gdpr/overview-principles/what-data-can-we-process-and-under-which-conditions_en>
