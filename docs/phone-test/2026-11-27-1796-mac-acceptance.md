# #1796 — Mac / Android-приймання, 2026-11-27

Загальний стан: **BLOCKED, production GO не видано**. Це проміжний звіт. Частина клієнтських перевірок пройшла на реальному телефоні; HTTPS-доставка й порівняння ресурсів ще не прийняті.

Код candidate: `eaf95d035b5737d05173824af1dbcea81eefcf69`, PR #1797. Голова звірена через GitHub. Основна копія — чистий `main@566d0fa75bce53c336d1fc2196a09ecc8bb2e07a`, інструкції збігаються з `origin/main` (freshness exit 0). Для перевірок створена окрема гілка `codex/1796-mac-acceptance-20261127` у `/private/tmp/slukhayka-1796-mac-acceptance`. Production-код не редагувався. Існуюче резервування `codex-mac-recommendations` не перезаписувалося; вузький обсяг приймання опубліковано в issue за прямим дорученням власника.

Телефон: модель `25102PCBEG`, Android 16, API 36. Лише USB ADB, авторизований пристрій. Серійний номер у звіт не включено. Уже встановлені основний, debug і ux1806 пакети збережено. Новий пакет `com.slukhayka.audiobooks.recommend1796`, versionCode 30; тестовий — той самий пакет із `.test`.

APK SHA-256: `376860fefcdc0967b8e4b1cc3799ff9db8940390ffa38451c48ca4f69a110933`. ApplicationId підтверджено `output-metadata.json` та `aapt dump badging`. Немає uninstall, clear-data, downgrade, обходу конфлікту підпису, мережевого ADB, forwarding, reverse, порту чи тунелю. URL і ключ графа в цьому APK порожні: він перевіряє локальний fallback, а не серверну конфігурацію.

| Критерій | Стан | Доказ і межа |
|---|---|---|
| USB, модель, Android, інструменти | PASS | `adb -d shell getprop ...`, Perfetto query і simpleperf list: exit 0. Команди/коди у `device-preflight.json`. |
| Ізольована збірка та встановлення | PASS | Обидві Gradle-збірки й install: exit 0. Основні пакети лишилися встановленими. |
| Прийняття справжнього ECDSA-підпису | PASS, fixture | Android instrumentation, committed seed envelope/public key, перевірка Android verifier. Синтетичний годинник. Не HTTPS. |
| Змінений граф, неправильний підпис, неправильний envelope | PASS, fixture | Device-тест перевірив відхилення й точну незмінність попереднього кеша. Transport ін'єктований. |
| Прострочений підписаний граф | PASS, fixture | Після зсуву тестового годинника на 8 діб refresh повертає null і не переписує файл. |
| Кеш, throttling, перезапуск store, офлайн, незавершений staging | PASS, fixture | Повторні екземпляри store читають кеш; одна спроба в межах доби; offline endpoint порожній; `.tmp` не замінив кеш. Це не системний offline UI-прогін. |
| Чинний серверний контейнер через HTTPS | BLOCKED | Потрібен новий комплект після audio-only виправлень: code SHA, container digest, expiry, public key, погоджений HTTPS URL та звіт воркера. Запит у #1796 та через DSH Remote. |
| Негативні сценарії реальної HTTPS-доставки | BLOCKED | Не підмінено fixture-тестом; спільна публікація не змінювалася. |
| Приховування й відновлення в UI | PASS, тестова бібліотека | Приховано публічний seed-твір. Після завершення процесу instrumentation і нового старту вибір присутній у налаштуваннях; «Відновити» прибрало його. Лише новий ізольований пакет. |
| «Менше подібного» й скасування в UI | PASS для збереження вибору | Дію застосовано до публічної рекомендації; налаштування показали «Менше схожих» із відповідним ключем, відновлення прибрало запис. Якість зміни ранжування слухачем не оцінена. |
| Відкриття та старт рекомендованого аудіо | PASS, один твір | Картка → деталі → «Слухати»; MediaSession candidate показала PLAYING, позиція зросла. Прослуханий зміст людиною не підтверджений. |
| Різноманітність повної добірки | BLOCKED | Видимі різні автори; кількісну перевірку всіх 10 місць на фіксованому каталозі не завершено. Unit/CI-механіка не є оцінкою якості. |
| Повний offline UI і повторний запуск з production-кешем | BLOCKED | Production-кеш ще не отриманий. Системну мережу телефона не змінювали. |
| 30 хвилин із вимкненим екраном | BLOCKED, спроба перервана | На 0 с — Dozing/PLAYING; на 30 с — Awake/PLAYING; на 60 с — Awake/PAUSED. Пауза сталася приблизно на 34 с. Причина не встановлена. Збір зупинено після трьох семплів; exit 143. Це не успішний 30-хвилинний прогін. |
| Baseline / candidate CPU, пам'ять, температура, енергія | BLOCKED | Немає завершених пар на однакових каталозі/сигналах/аудіо та початковій температурі. Заряджання й стороння медіаактивність обмежують умови. Покращення не заявляється. |
| Оцінка власника/слухачів | BLOCKED | Підготовлено `listener-protocol.md`. Відгуки не вигадувалися. |
| Незалежний review та повний CI | BLOCKED | Review gate — fail; Room Robolectric-only ще in progress на останній перевірці. Власного review:pass не ставили. Інші показані перевірки, включно з content pipeline, успішні. |

## Команди та результати

Із candidate checkout, через спільний слот:

```sh
/Users/MARKETING1/Downloads/Audiobooks/Слухайка/.worktrees/gt ./gradlew --offline --init-script /private/tmp/slukhayka-1796-evidence/isolate.gradle :app:assembleDebug
# exit 0, BUILD SUCCESSFUL, 6m40s
/Users/MARKETING1/Downloads/Audiobooks/Слухайка/.worktrees/gt ./gradlew --offline --init-script /private/tmp/slukhayka-1796-evidence/isolate.gradle :app:assembleDebugAndroidTest
# exit 0, 1m20s; повтор після виправлення підключення harness: exit 0, 55s
adb -d install app/build/outputs/apk/debug/app-debug.apk
# exit 0, Success
adb -d install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# exit 0, Success; оновлено лише власний тестовий пакет
adb -d shell am instrument -w -r -e class com.slukhayka.audiobooks.data.recommend.SignedGraphDeviceAcceptanceTest com.slukhayka.audiobooks.recommend1796.test/com.slukhayka.audiobooks.IsolatedDatabaseTestRunner
# shell exit 0; OK (1 test), 0.655 s, test status 0
```

Перший device-запуск — FAIL harness: `ClassNotFoundException`. Зовнішній java source-dir не додав Kotlin-тест до APK. Після копіювання harness у стандартний `app/src/androidTest/java/...` й перебудови клас виконався успішно. Початковий невдалий лог збережено. `am instrument` може повернути shell exit 0 навіть за невдалого тесту: тут PASS визначено за JUnit-результатом, а не лише exit-кодом. Harness і public assets збережені поруч зі звітом. Production-код та signature verifier не послаблювалися.

Перший COLD `am start -W`: exit 0, TotalTime 1504 ms, WaitTime 1507 ms. Це один запуск, без статистичної або baseline-претензії.

Окрема початкова серія: 4 семпли за 31.2 s, PSS 265564–545452 KiB, RSS 422536–707876 KiB, температура батареї 39.0–39.1 °C. Різниця CPU — 820 ticks при `getconf CLK_TCK=100` (8.2 CPU s процесу). Серія охоплює запуск і перехід до Огляду, живе завантаження каталогу; її не можна назвати стабільним idle або вартостю лише ранжування. Дані — `candidate-idle-samples.json` (історична назва файла).

Під час перерваної спроби: позиція аудіо 164467 → 194467 → 198697 ms; потім PAUSED. Статус батареї AC powered=true; заряд зріс 62 → 63 %. Температура 38.3–38.4 °C. `/sys/.../current_now` і `/sys/.../charge_counter` повернули exit 1 / Permission denied. Доступний `dumpsys battery` — OS-оцінка під заряджанням, не вимір енергії застосунку. Simpleperf/Perfetto наявні; їхню успішну сесію профілювання тут не заявляємо.

## Що потрібно далі

Серверному агенту — завершити свою перебудову й опублікувати комплект артефактів у #1796. На Mac після цього — зібрати окремий APK із URL/ключем, перевірити справжній download/cache/TTL/негативні оновлення без зміни спільної публікації. Для довгих вимірювань потрібен вільний телефон та сталі умови; власнику поставлено уточнення через чат. Причину паузи слід відокремити від явного втручання, audio focus та помилки програвача.

Незалежному reviewer — свіжий review на завершеному CI поточної голови. Власнику — оцінка добірок і окреме рішення про production після всіх доказів. PR не мерджено, реліз і розклад не запускалися.

## Уточнення керування телефоном

Власник після цього прогону прямо заборонив блокувати телефон. Команда KEYCODE_SLEEP (223), яку я застосував для screen-off проби, активувала захищене блокування; це моя помилка керування тестом. Екран увімкнено KEYCODE_WAKEUP (224), захист не обходився й не змінювався. Більше не виконувати sleep, power-toggle чи lock-команди та не змінювати захист/таймаути. Screen-off сценарій можливий лише коли власник сам вимкне екран; агент лише спостерігає.
