# #1796 — Mac / Android-приймання, 2026-11-27

Загальний стан: **BLOCKED для production-рішення**. Реальна HTTPS-доставка, негативні оновлення й офлайн-перезапуск пройшли на телефоні. Не закриті screen-off сценарій, чесне порівняння ресурсів на однаковому навантаженні, оцінка слухачів та незалежний review. PR не мерджено; реліз і production-розклад не запускалися.

## Код і середовище

PR #1797, поточна голова `6258b058278fe4a0ceec04eb1b67ee5a89bc7f92`. Android APK зібрано з `eaf95d035b5737d05173824af1dbcea81eefcf69`. `git diff --exit-code eaf95d03 origin/t1796-local-recommendations -- app build.gradle.kts settings.gradle.kts gradle gradle.properties` повернув 0: клієнтські джерела та конфігурація цих голів однакові. Серверний artifact code — `99076a2f1d1638d13606a89f71eba3cfa4a1c037`. Baseline — merge-base `172901564359cf0729570354ecef0de550231be8`.

Телефон: модель `25102PCBEG`, Android 16 / API 36, авторизований USB ADB. Серійний номер не публікується. Встановлені окремі власні пакети `.recommend1796`, `.recommend1796.test` та `.baseline1796`. Основні застосунки й бібліотека власника не змінювалися. Немає uninstall, clear-data, downgrade, мережевого ADB, портів, тунелів чи зміни захисту.

Власник прямо заборонив блокувати телефон. Перша screen-off спроба через KEYCODE_SLEEP активувала захищене блокування — помилка керування тестом. Екран пробуджено, захист не обходився. Подальші перевірки не використовують sleep, power-toggle або lock і не змінюють таймаути. Screen-off критерій лишається відкритим.

APK SHA256:

- HTTPS candidate: `830ee2df5269fb4f19462878556b03b739b1cb478c79d53787088c099b5321ba`.
- HTTPS instrumentation: `97c225e91f18877039a6b79e316d544ecff1868b62814da0cd195feceab422ab`.
- Baseline: `7b6e5af6ae838a237f4a7c4f1dfcbbfc8e0bd151f90e5dc88166a2a24c44bbfd`.
- Початковий candidate без URL/ключа, використаний для UI й довгого аудіо: `376860fefcdc0967b8e4b1cc3799ff9db8940390ffa38451c48ca4f69a110933`.

## Приймання

| Критерій | Стан | Доказ і межа |
|---|---|---|
| Ізольована збірка, встановлення, USB | PASS | Gradle та `adb -d install -r`: exit 0. Package, pinned URL/key і harness-клас звірені в APK. |
| Реальний підписаний HTTPS-граф | PASS | Застосунок сам завантажив кеш: 8 241 667 байт, SHA256 збігається з операторським. Окремий device-тест використовує production HttpFetcher, verifier і store, справжні HTTPS-відповіді. |
| Змінений граф і неправильний підпис | PASS | Реальні окремі HTTPS-контролі відхилені; попередній придатний кеш байт-у-байт незмінний. |
| Прострочений підписаний граф | PASS | Контроль має справжній чинний підпис, але минулий expiresAt. Відхилений, хороший кеш збережений. |
| TTL, добове обмеження, повторний store | PASS | Device-тест: нові екземпляри store не повторюють завантаження в межах доби; після TTL кеш не повертається. Зсувається лише ін'єктований годинник тесту, не годинник телефона. |
| Системний offline і cold UI | PASS | Wi-Fi/data вимкнені, default network none. Новий процес показав добірку; SHA кеша та timestamp спроби незмінні. Після тесту Wi-Fi/data відновлені. |
| Приховування / відновлення | PASS, тестова бібліотека | Вибір пережив перезапуск; відновлення прибрало його з налаштувань. |
| Менше подібного / скасування | PASS для збереження вибору | Запис у налаштуваннях перевірено й скасовано. Влучність нового ранжування людиною не оцінена. |
| Різноманітність | PASS для одного знімка авторів; серії BLOCKED | Повна карусель: 10 унікальних назв, 9 авторів, максимум 2 твори автора. У всіх seriesTitle null, отже series-cap не доведений. |
| Рекомендоване аудіо | PASS, один твір | «Що сказано»: картка → деталі → відтворення; MediaSession PLAYING і позиція зростає. |
| 30 хвилин стабільності | PASS з увімкненим екраном | 61/61 семплів PLAYING, один PID, 1800.005 s. Це початковий APK без URL/ключа; не screen-off приймання. |
| 30 хвилин screen-off | BLOCKED | Перша спроба перервана: пауза приблизно на 34 s, причину не встановлено. Наступні прогони не блокують телефон і мають стан Awake. |
| Порівняння CPU / пам'яті / температури / енергії | BLOCKED для висновку про покращення | Доступні виміри процесу та обмежений парний запуск; немає однакового recommendation/audio workload і контрольованої початкової температури. Точна енергія недоступна під заряджанням. |
| Якість для слухачів | BLOCKED | Протокол підготовлено в локальних доказах. Відгуків власника не вигадували. |
| CI | PASS для виконаних перевірок | Поточна голова: всі виконані тести, lint, R8, accessibility, worker та Kover успішні; Signed smoke APK skipped. |
| Незалежний review | BLOCKED | Review gate FAILURE. Власного review:pass не ставили. |

## HTTPS-артефакти

Безкоштовний тестовий хост — окрема artifact-гілка вже публічного репозиторію. Незмінний commit `96a60a4ef1edced9366cb0d5dacef2c1b1050e7c`; це не production-активація.

[Чинний контейнер](https://raw.githubusercontent.com/Samuel-Ku/slukhayka/96a60a4ef1edced9366cb0d5dacef2c1b1050e7c/graph.signed.json), поруч у `negative/` — `tampered-graph-old-signature.json`, `wrong-signature.json`, `expired-graph.json`. Mac отримав усі чотири: HTTP 200, curl exit 0. OpenSSL: valid/expired exit 0; tampered/wrong exit 1. Перевірка TTL окрема від перевірки підпису.

Container SHA256 `3ec6d8d8cd5487645ab689aea4e234734e33ed0432363cb4c85b30562e2ab094`. Graph SHA256 `8529f2d2ebd7180e7bebb09da210eb560dd60de2a229f259b1472e1b4dc8476b`. Expiry `2026-12-04T08:58:41Z`. Самостійно прочитано 20 000 ключів і 440 000 ребер. Публічний ключ та digests контролів — у README artifact-гілки та машинних доказах; приватного ключа на Mac не запитували.

Серверний звіт: [коментар агента](https://github.com/Samuel-Ku/slukhayka/issues/1796#issuecomment-7214212664) і `docs/recommend/2026-11-27-1796-server-run.md` у PR. Audio-only 20 000 = 5 024 українських + 14 976 LibriVox. Це серверне свідчення; Mac не дублював побудову каталогу/графа чи налаштування воркера. Cold/warm та ізоляцію оцінює окремий reviewer.

## Команди й свідки

З власного acceptance checkout, через абсолютний `.worktrees/gt` репозиторію:

```sh
<root>/.worktrees/gt ./gradlew --offline --init-script <evidence>/isolate.gradle :app:assembleDebug :app:assembleDebugAndroidTest -Precommendation.graphUrl=<immutable-HTTPS-URL> -Precommendation.graphPublicKey=<operator-public-key>
# exit 0, BUILD SUCCESSFUL, 5m15s
adb -d install -r <candidate.apk>
adb -d install -r <androidTest.apk>
# обидва exit 0
adb -d shell am instrument -w -r -e class com.slukhayka.audiobooks.data.recommend.SignedGraphHttpsDeviceAcceptanceTest -e graphUrl <valid> -e tampered <tampered> -e wrongSignature <wrong> -e expired <expired> com.slukhayka.audiobooks.recommend1796.test/com.slukhayka.audiobooks.IsolatedDatabaseTestRunner
# shell exit 0; OK (1 test), 18.807 s, test status 0
```

Це шаблон команди; точні аргументи, init-script, harness і логи збережені локально. Свідки: `build-https.log`, `https-apk-verification.json`, `device-https-test.log`, `main-https-cache-created.json`, `offline-ui-result.json`, `operator-host-verification.json`, `https-negatives-host-verification.json`, `diversity-summary.json`, `playback30passive-summary.json`, `pr-current.json`.

Початковий fixture-device тест також пройшов (0.655 s). Перший запуск harness мав ClassNotFoundException через зовнішній java source-dir без Kotlin; підключення виправлено. Цю помилку не зараховано як помилку production-коду. Fixture-тести не підміняють наведений реальний HTTPS-прогін.

## Ресурси та чесні межі

Довгий пасивний прогін: CPU 391.87 s за 1800.005 s, середнє 21.77% одного ядра; PSS 268 154–314 288 KiB, RSS 248 696–485 708 KiB. Батарея 36.4–38.3 °C, заряд 67→76% під заряджанням. Екран Awake у всіх семплах. Змішані foreground/background і коротке профілювання всередині прогону; це вартість усього процесу, не лише рекомендацій і не matched baseline.

Simpleperf stat успішний. Окремий record: 71 sample, 0 lost, 9.555 s; замало для точних причинних відсотків. Перша run-as record-спроба відхилена setproperty permission; стандартний --app record успішний. Kernel symbol restrictions не послаблювалися. Поточні HAL-температури в кінці: battery 37.3 °C, skin 37.685 °C, CPU 48.0–49.4 °C, GPU 47.3–48.4 °C, thermal status 0. Застарілі cached HAL-значення не використані.

Sysfs current_now/charge_counter: permission denied, exit 1. OS-відсоток батареї під заряджанням не є виміром енергії застосунку. Висновку про економію енергії немає.

Baseline отримав модель E5 штатним завантаженням через Wi-Fi. Model SHA256 `f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193`, tokenizer SHA256 `0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39`. У кеші baseline змішані E5/keyword вектори; чистий E5 warm workload не заявляється. Для парного запуску скопійовано лише власну тестову БД і recommendation settings між двома новими пакетами. Особисті дані власника не використовувалися.

## Що лишилося

Обмежений парний запуск завершений; однакова якість чи вартість ранжування ним не доведені. Screen-off без блокування не доведений. Потрібні сталі умови для чесного довгого baseline/candidate порівняння; під USB-заряджанням точної енергії немає.

Незалежному reviewer — перевірити серверні докази, клієнтські результати та поточну голову, видати власний свіжий verdict. Власнику/слухачам — оцінка добірок за протоколом, потім окреме production-рішення. Цей звіт його не замінює.

## Парний запуск Overview, ABBA

Однакові початкові каталоги, сигнали та recommendation settings у власних пакетах; мережа вимкнена, аудіо відсутнє, однакова яскравість 50. Порядок baseline → candidate → candidate → baseline. Cold означає новий процес; warm — повернення того самого PID із фону, не повторний старт Activity. Усі am start та memory probes повернули 0. Wi-Fi/data й автоматичну яскравість відновлено.

| Запуск | Cold Activity TotalTime, ms | CPU процесу до Overview probe, s | Cold PSS, KiB | Warm PSS, KiB | CPU за наступні 5 s, s |
|---|---:|---:|---:|---:|---:|
| Baseline 1 | 1129 | 11.50 | 436210 | 655970 | 1.60 |
| Candidate 1 | 1132 | 12.67 | 426280 | 311572 | 0.58 |
| Candidate 2 | 1222 | 13.76 | 389530 | 309202 | 0.47 |
| Baseline 2 | 1083 | 10.49 | 466860 | 652649 | 1.73 |

На cold probe у всіх чотирьох випадках ще не було recommendation cards; на warm probe у всіх вони вже були. Тому CPU до cold probe не є часом завершеного ранжування, а Activity TotalTime не є часом готовності рекомендацій. Candidate тут має менший warm PSS, але cold CPU/Activity не показують пришвидшення. Два запуски на збірку не доводять загальної регресії чи покращення. Температура батареї на початку 34.9, 35.0, 35.0, 35.0 °C; наприкінці останньої пари 35.6 °C. Контрольованого охолодження та однакової початкової CPU-температури не було.

Початковий строгий byte/input guard повернув false: змінилися work_facets.updatedAt та work_sources.addedAt. Окремий SQLite-аудит довів незмінність решти колонок усіх 11 таблиць, їхнього складу та кількості; це уточнення, а не переписаний PASS початкового guard. Свідки: paired-startup.log, paired-startup-result.json, paired-input-audit.json. Перший readiness harness очікував картки зарано й зупинив попередню спробу; це не доведений дефект baseline. Один синтетичний favourite у тестовій бібліотеці доданий однаково обом пакетам і не подається як відгук власника.
