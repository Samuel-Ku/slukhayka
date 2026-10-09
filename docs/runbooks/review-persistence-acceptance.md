# Приймання збереження відгуків і двох установок

## Межі та поточний стан

Цей посібник описує заплановані перевірки #620 і #533. Команди нижче не є
результатами виконання. Актуальний кандидат ще не має завершеного
backend/device приймання.

Код сценарію EDIT rejection уже застосовано: CLI, instrumentation test
і фікстурні rules. Локальний Firestore emulator прийняв parser і всі
23 REST-перевірки цих rules, включно з відхиленням неповного seed через
403/PERMISSION_DENIED. Це перевірка лише фікстурних правил. Пристроєвий
прогін EDIT rejection ще не виконувався; Android SDK drain, process restart
і автоматичний FAILED цим локальним REST-прогоном не підтверджені.

Для Q1 прийнято окремі вузькі перевірки: 74 цільові тести відгуків
(45 lifecycle, 26 confirmed snapshots, 3 write-task), 7 перевірок
SDK36 AtomicFile/noBackup у Robolectric, один native Room-тест скасування
після fetch до persistence та 18 pure JVM-регресій FeedSnapshotRefresh.
Останні два прогони перевіряють cache publication. Вони не доводять
Firestore ACK, відновлення SDK-черги після смерті процесу чи DELETE.
Серверне й пристроєве приймання ще потрібне для
[#620](https://github.com/Samuel-Ku/slukhayka/issues/620) і
[#533](https://github.com/Samuel-Ku/slukhayka/issues/533).

## Ізольований Q1 стенд

Потрібні погоджений слот збірки, Android SDK/JDK для цього checkout,
`adb`, Firebase CLI і окремий disposable Android emulator.
`review-persistence.py` приймає лише serial `emulator-*` і перевіряє
`ro.kernel.qemu = 1`. Не використовуй особистий пристрій.

Стенд працює лише з `demo-slukhayka-acceptance`. Host Firestore слухає
`127.0.0.1:8089`; Android SDK у тесті підключається до `10.0.2.2:8089`.
Фікстурні правила дозволяють read і delete.
Create/update з `uid = qa-rejected` відхиляються. Для `qa-rejected-edit`
дозволено лише CREATE точного початкового відгуку: rating 3, body
`Початковий відгук`, editionTag `Тестове видання`, createdAt 100,
authorName `Тестовий читач`, погоджені Work/document IDs і рівно сім
обов’язкових полів. Подальший UPDATE цього документа відхиляється,
зокрема при спробі замінити uid. `qa-accepted` і `qa-accepted-edit`
зберігають дозволені фікстурні CREATE/UPDATE.
Це не перевірка production security rules, акаунтів чи документів.

Opt-in instrumentation runner запускає звичайний `Application`,
а не production `App`. Перевіряються чинні lifecycle/Firestore adapter
та SDK persistence. Production composition, background writers,
identity recovery і звук цим тестом не приймаються.

Після отримання слота, з кореня checkout:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
  -PacceptancePersistence=true --max-workers=2 --console=plain
```

Цей opt-in вибирає `PersistenceAcceptanceRunner`. Без прапорця збірка
зберігає `IsolatedDatabaseTestRunner`. Не запускай UI suites під
acceptance runner. Перевір фактичні APK outputs, їх SHA-256 і source HEAD.
Не використовуй старі APK як доказ нового кандидата.

В окремому терміналі з того самого checkout:

```bash
firebase emulators:start --only firestore \
  --project demo-slukhayka-acceptance --config scripts/acceptance/firebase.json
```

Тест не очищає app data. Для кожного запуску driver створює новий run ID,
Work IDs і назву FirebaseApp. Raw logs та manifests лишаються поза checkout.
Output directory має бути новим: заміни `RUN_ID` у прикладах власною міткою.

## Чинний CLI та чотири фази

Для creation ACK:

```bash
python3 scripts/acceptance/review-persistence.py \
  --serial emulator-5554 \
  --app-apk app/build/outputs/apk/debug/app-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --output /tmp/review-persistence-creation-ack-RUN_ID \
  --case ack --mutation creation --reconcile automatic
```

Для creation rejection повтори з новим output directory і
`--case rejection --mutation creation --reconcile automatic`.
Для EDIT ACK — з новим output directory і
`--case ack --mutation edit --reconcile automatic`.

Для EDIT rejection:

```bash
python3 scripts/acceptance/review-persistence.py \
  --serial emulator-5554 \
  --app-apk app/build/outputs/apk/debug/app-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --output /tmp/review-persistence-edit-rejection-RUN_ID \
  --case rejection --mutation edit --reconcile automatic
```

Driver допускає `--case ack|rejection`, `--mutation creation|edit` та
`--reconcile manual|automatic`. EDIT rejection потребує `automatic`;
`--case rejection --mutation edit --reconcile manual` відхиляється до запуску.
Default reconcile — `manual`; його успіх не приймає automatic reconnect.
DELETE не є режимом цього CLI. Ці обмеження не звужують #620:
бракуючі вертикальні сценарії треба окремо підготувати й прийняти.

Кожен запуск має `seed`, `queue`, `restart-offline`, `reconnect`.
Driver виконує `am force-stop` перед кожною фазою і зупиняється на першій
невдалій. Instrumentation має timeout 150 секунд на фазу. Збережи
`result.json`, phase logs, APK/source pins та фактичне завершення процесів.
Завершення driver саме по собі не доводить прибирання стенда: emulator
і Firestore запущені окремо, їх завершує власник після збору доказів.

Перевірки фаз:

- Seed справді підтверджений `Source.SERVER`: точний document ID і повний
  DTO, `fromCache = false`, `hasPendingWrites = false`.
- Queue вимикає SDK network. Rating 5 лишається pending; confirmed rating 3
  і average 3.0 не змінюються. SDK cache містить точний queued payload
  з pending metadata. Для EDIT зберігаються createdAt, body, editionTag
  та незалежно зафіксований editedAt.
- Restart-offline має інший PID, ніж queue. SDK pending переживає справжній
  force-stop. Він не стає confirmed, попередній confirmed DTO і average
  зберігаються. Recreated ViewModel не заміняє цю перевірку.
- Reconnect вмикає network, чекає `waitForPendingWrites` і перевіряє повний
  SERVER payload. Automatic mode має отримати exact scoped PUBLISHED або
  FAILED без повторного UI refresh, прибрати pending та зберегти confirmed
  чи exact failed retry draft. Поточний тест перевіряє один terminal event
  лише в записаному обмеженому вікні; це не доказ довічної унікальності.

Для EDIT rejection seed має підтверджений повний початковий DTO. Queue
вимикає мережу й зберігає повний EDIT: rating 5, body `Переживає restart`,
createdAt 100, editedAt 200 та незмінний editionTag. Після force-stop
restart-offline має інший PID і той самий SDK pending payload, а confirmed
лишається початковим. Перед reconnect встановлюються scoped event collector
і waiter на pending-empty; мережа вмикається після них.

Після фактичного `waitForPendingWrites` SERVER має містити лише повний
початковий документ без pending metadata. Automatic lifecycle має видати
FAILED для exact Work/document і спостереженої generation, прибрати pending,
зберегти exact EDIT у failedSave та відновити початковий visible/confirmed
відгук і average 3.0. Перевіряється один terminal event у записаному вікні
1000 ms. Самої розбіжності SERVER і draft недостатньо для FAILED. Збережи
seed/queue/restart/reconnect PID witnesses, повні payloads і event evidence.

Серверну істину звір окремо з demo backend для тих самих run/Work/document
IDs. Cache metadata, toast, PLAYING чи один phase log не є backend ACK.
Після першої невдачі збережи початковий прогін і виконані контрольні перевірки;
пізніші фази не зараховуються. Повтор потребує нового слота й дозволу.

## Незавершені Q1 gates

Для актуального кандидата потрібне повне backend/device приймання
creation ACK/rejection та queued EDIT після process death, offline restart
і automatic reconnect. Rejection має залишити exact retry payload;
фактичний retry і його backend verdict теж потребують доказу.

EDIT rejection ще потребує фактичного backend/device
приймання всіх чотирьох фаз; локальний rules parser його не заміняє.

Окремо потрібен queued DELETE: локальне прийняття,
смерть процесу, відновлення без вигаданого підтвердженого стану, reconnect,
exact terminal verdict та retry при відмові. Для DELETE треба зберегти
чесний deleting intent і відрізнити pending absence від підтвердженої
відсутності після SDK drain та авторитетного SERVER read. Driver не
виконує DELETE; не передавай йому вигаданий `--mutation delete`.
Фікстурні rules дозволяють delete, тому вони не дають DELETE rejection
без окремо погодженої фікстури.

Production composition, uid/Work isolation, lifecycle recovery та решта
AC #620 залишаються окремими gates. Вузькі 74/7/1/18 результати їх не
заміняють.

## Дві установки (#533)

`scripts/acceptance/two-installations.py` — preflight і trace helper,
а не автоматичний verdict shared delta чи відтворення. Потрібні два різні
QA emulator serials, різні Android instance identities та конкретний
integrated APK після #524/#525, погоджений координатором. Source HEAD
має дорівнювати `--head`. SHA-256 APK на обох установках має збігатися
з переданим APK. Поточний Q1 checkpoint сам по собі не є таким integrated
прийманням.

Заміни `INTEGRATED_COMMIT` перевіреним повним SHA, `RUN_ID` — новою міткою:

```bash
python3 scripts/acceptance/two-installations.py \
  --a emulator-5554 --b emulator-5556 --head INTEGRATED_COMMIT \
  --app-apk app/build/outputs/apk/debug/app-debug.apk \
  --output /tmp/two-installations-RUN_ID --action preflight
```

Preflight зберігає `installations.json` із непідтвердженими shared delta,
selected resolve, fallback і звуком. Для кожного обмеженого вікна дій
виконай `before`, дію в UI, `after`, `delta` з тими самими аргументами
та output directory. Для наступного вікна створи новий preflight/output.
Helper викликає чинний `source-budget-snapshot.sh` з `ANDROID_SERIAL`
для кожної установки. Відсутній чи порожній counter snapshot не означає
нуль feed requests; нуль треба підтвердити придатними даними того самого
вікна та фактичним trace.

Окремі вікна потрібні для таких дій:

- A читає джерело й публікує card/block; B отримує shared delta без нового
  feed/search request до SluhayUA.
- B резолвить лише вибрану книгу; інші посилання не відкриваються автоматично.
- Керована відмова першого Source приводить до іншого Source тієї самої
  Edition, без перенесення позиції в іншу начитку.

Збережи перед/після Work, Edition, chapter, position, source counters та
події UI/logs для того самого вікна. Тестові документи й сирі logs не
публікуй. Preflight або PLAYING не є прийманням цих дій.

HEARD — окреме підтвердження слухача: назва твору, начитка, chapter і
почута репліка мають відповідати очікуванню. Без людського HEARD відтворення не вважається підтвердженим за
критерієм #533 про фактично почутий звук.
