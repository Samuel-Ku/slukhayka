# Контрольований App → Room → Огляд

Запуск із кореня репозиторію:

```sh
python3 scripts/test-controlled-app.py run
```

Потрібні macOS, JDK 21, Python 3.10+, Python 3.14 для звичайної Chaquopy-збірки
й налаштований Android SDK. Збірка використовує compileSdk 36.1;
для manifest fixture потрібні саме перевірені build-tools 35.0.0 та platform 35.
Robolectric SDK 36 береться через звичайне Gradle dependency resolution.
Якщо Java не знаходиться, задай `SLUKHAYKA_JAVA_HOME` на свій JDK 21.
Залежності мають бути доступні з Maven або вже бути в кеші.
Linux поки завершується помилкою: його tool та transformed-artifact pins
потребують окремої перевірки. Успішного пропуску тесту немає.

Це окремий ручний target. Шість звичайних CI-частин і їхнє покриття його
не включають. `scripts/test-all.sh --include-controlled-app` запускає три
controlled cases після цих шести частин трьома окремими supervised запусками. `scripts/test-changed.sh` обирає його для змін у
production-коді застосунку, import/collective/Room тестах та спільній test
інфраструктурі. Fallback на весь набір зберігає explicit target, зокрема
коли базовий Git ref недоступний або список змін порожній.

Helper створює новий каталог `build/controlled-app/<id>` і запускає рівно
один Gradle invocation з `validateTestPartitions` та `testControlledAttachedApp`.
Прямий виклик alias, `--tests`, `test.selectedClasses`, init scripts та
додаткові JVM/Gradle overrides для цього target не підтримуються: вони
можуть обходити підготовку fixture. У разі помилки користуйся наведеною
командою, а не окремим викликом `testDebugUnitTest` для цього класу.

Fixture має бути без налаштованого Firebase: helper перевіряє source config,
згенеровані ресурси й фактичний runtime до запуску worker. Якщо
`google-services.json` чи його згенеровані ресурси присутні, запуск
завершується помилкою. Helper їх не видаляє. Для цього тесту потрібен
окремий checkout без конфігурації Firebase.

Тест використовує одну plain `Application`, а справжній `App` підключає
через public `Instrumentation` і викликає його public lifecycle. Справжній
import обробляє зафіксований HTML, пише в стандартний Room, а public reader
Огляду читає сім точно очікуваних related-карток. Перевіряються також
Chapter, Source, Work, carrier і Library Entry, кількість запитів та фінальна
відсутність Firebase. У XML це один case із сімома очікуваннями карток.
Після першої невдалої assertion решта case не вважається перевіреною.

AGP створює звичайний unsigned local-test resource archive. Helper змінює
в його приватній копії лише `AndroidManifest.xml`, прибираючи компоненти
startup. `resources.arsc`, resource IDs, compiled XML та всі справжні assets
зберігаються байт у байт; production APK не перепаковується. HTML має
51 588 байтів і три CRLF. Вузький `.gitattributes -text` захищає ці байти
від нормалізації під час checkout.

Команда повертає 0 тільки після природного Gradle terminal 0, одного
свіжого XML case без failures/errors/skips, перевірки фактичного worker
classpath, свіжих compiler outputs та відсутності власних дочірніх
процесів. Бюджет одного Gradle invocation — 720 секунд. Після його
завершення helper чекає дочірні процеси ще до 15 секунд; спостереження
процесів, cleanup та post-audit додають час до цього бюджету. Packager має
90 секунд, case — 180 секунд у межах Gradle invocation. Timeout або
примусове завершення дає помилку. Якщо процеси неможливо спостерігати,
receipt позначає їхній залишок як UNKNOWN і команда повертає помилку.
Cleanup завжди перевіряє власний Popen handle; інших відомих дочірніх
процесів зупиняє лише після повторної перевірки їхніх birth та command. Завершення
`App.onTerminate` саме по собі не означає скасування його jobs; їх містить
завершення окремого worker. Чужі процеси helper не зупиняє.

У каталозі запуску лишаються `raw.log`, `raw.xml` якщо Gradle його створив,
compiler/runtime/packaging receipts, captured worker arguments і PID
snapshots. Артефакти з невдалого запуску зберігаються; автоматичного retry
немає. Після правки нова команда створює окремий каталог.

Ця перевірка охоплює контрольоване складання attached App, Room і public
Огляду. Вона не доводить нормальний startup providers, MainActivity/Home,
спільний backend, поведінку пристрою, reinstall чи прослуховування аудіо.
CI workflow і coverage verifier цим target не змінюються.


Окремий App/MainViewModel case для пізнішого локального Room snapshot:

```sh
python3 scripts/test-controlled-app.py run --case overview-late-room
```

Default команда вище зберігає початковий import case із сімома картками.
Новий case спочатку доводить реальний HTTP relay, public attached App та
точне початкове A у стандартному Room і живому MainViewModel. Інший store
над тим самим App DAO записує незалежне B; public App reader мусить читати
повне B. Той самий subscriber має отримати повне B без другого refresh чи
перебудови VM. Лише фінальна відсутність B після цих controls дає named
`S2_LATE_ROOM_FINAL_MISSING_VM_B_AFTER_COMMITTED_ROOM_AND_PUBLIC_APP_B`.
Setup, початкова готовність, сторонні блоки та cleanup — окремі помилки.
У цього case тіло обмежене 180 секундами coroutine timeout; JUnit timeout
thread не використовується. PAUSED main looper identity перевіряється перед
public VM lifecycle. Фінальне очікування B має межу 10 секунд.

Три класи виключені зі звичайних CI partitions. Зміни одного explicit
класу запускають його власний case; спільні зміни та full-suite fallback
зберігають усі три targets. Case, exact class і method зв'язані від supervisor
до compiler/runtime/packaging receipts і фінального XML. Довільні class,
method чи case overrides не підтримуються. Causal RED не перетворюється
на успішний exit: невдалий Gradle terminal зберігається як помилка команди.

Це пізніший commit у тому самому локальному Room, а не інший пристрій чи
backend. Непорожні literal covers виключають підвантаження обкладинок із
цього очікування. Окремо закриваються test subscriber, public VM/player,
Room та relay; це не доводить закриття всіх unscoped App jobs. Природний
вихід worker залишається їх containment boundary. MainActivity/Home,
production providers, Firestore, audio і deliberate arrivals TTL refresh
цим case не приймаються. Для зміненого helper потрібен також новий
фактичний default запуск із повним семикартковим oracle.


Окремий case для отриманих новинок через справжній App:

```sh
python3 scripts/test-controlled-app.py run --case arrivals-live-feed
```

Перед public onCreate тест зберігає через WorkIndexStore непорожній свіжий
Work index і перевіряє його повний public load. Справжній refresher має
віддати цей index; до explicit feed немає Sluhay arrivals запиту чи target
block. Це виключає отримання тієї самої відповіді startup index job. Умова
warm index є частиною fixture; холодний Work-index startup тут не перевіряється.

Справжній App.sourceCatalog.refreshSourceFeeds(forceRefresh=true) працює
через звичайні adapters і gate. Relay віддає два literal Sluhay cards лише
точному endpoint; решта запитів отримує 404. Тест не вимагає глобально
одного запиту від усіх джерел: перевіряє один attributable Sluhay endpoint,
raw ordinary snapshot, точну повернуту Sluhay row, browse Works та їхні
WorkSource URL. Listening profile, Chapter, playable Source і Library Entry
не створюються. Public App reader має віддати повний той самий блок без
другого arrivals запиту. Час активації й snapshot обмежені справжніми
wall-clock межами explicit дії; clock застосунку не підміняється.

Case використовує той самий provider-free runner, стандартний App Room
і unconfigured Firebase. Lifecycle лишається на PAUSED main looper; тіло
має coroutine timeout 180 секунд, JUnit timeout thread не використовується.
Немає окремого injected catalog, observer чи coordinator. Cleanup незалежно
закриває власні Room та relay й лишає transport fail-closed до природного
виходу supervised worker; скасування всіх unscoped App jobs не заявляється.

Це local App wiring та public overview під warm-index умовою. Normal
providers, MainActivity/Home, remote publication, client B, cold-index
races, пристрій, процес після restart, аудіо й повний #525 лишаються
окремими перевірками. Для зміненої спільної registration потрібні також
обидва попередні official controlled cases; весь suite для цього не запускають.
