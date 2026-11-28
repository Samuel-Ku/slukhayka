# Poco F3 у CI

Це додаткова ручна перевірка п'яти наявних інструментованих сюїт на
Poco F3 (API 33). Основний API 35 emulator job лишається у CI.

GitHub-hosted runner збирає APK із перевіреного `main`. Фіксований
pull-worker на Omarchy перевіряє походження build-а, бере готові APK і
запускає їх на Poco. Загальний self-hosted Actions runner не потрібен:
на Omarchy не виконується checkout чи скрипт із PR або artifact-а.

## Фактична готовність

На 2026-11-28 Poco доступний з Omarchy як `192.168.2.121:43045`,
`M2012K11AG` / `alioth`, API 33. Телефон заряджається. Wireless ADB port
може змінитися; serial задається явно, модель перевіряється кожним запуском.
OnePlus 8 Pro відведений під поточні перевірки функціональності.

**Пристрійний CI ще заблокований.** Readiness probe повернув код 2:
`INJECT_EVENTS permission`. MIUI відхиляє навіть порожню подію
`KEYCODE_UNKNOWN`, яка не змінює екран. Раніше цей самий телефон відхилив
`GrantPermissionRule` з `GRANT_RUNTIME_PERMISSIONS`, а USB-інсталяція чекала
фізичного підтвердження. Це перешкода unattended-запуску, не падіння
функціональності Слухайки. Захист і security settings не змінювалися.

GitHub API показав нуль repository runners; їхня реєстрація для цієї схеми
не потрібна. Без справжнього зеленого device receipt CI не вважається готовим.

## Readiness без інсталяцій

На Omarchy потрібні Python 3.11+, `adb` і вже авторизований Poco:

```sh
python3 scripts/device_ci.py --serial 192.168.2.121:43045
```

Скрипт бере неблокуючий lock `slukhayka-poco-f3.lock` у `XDG_RUNTIME_DIR`
(або `/tmp`), перевіряє ADB state, модель, product, завантаження і штатне
право вводу через no-op. Неправильна модель чи відмова MIUI зупиняють його
до інсталяції. Таймаути команд обмежені.

Готовність також потребує штатної unattended-інсталяції CI-пакета,
надання йому `POST_NOTIFICATIONS` і зеленого запуску всіх п'яти сюїт.
Наявність `device` в `adb devices` цього не доводить. Доки штатне
provisioning не дозволяє ці дії без людини, device jobs не виконуються.

## Один CI job

1. Після незалежного огляду й merge workflow запусти **Poco F3 device CI**
   вручну з `main`. Інший ref відхиляється до checkout. Скопіюй run ID.
   Цей job лише збирає APK; його зелений колір не є результатом Poco.
2. На Omarchy використай перевірену локальну копію `device_ci.py` та
   `poco_ci_worker.py` з того самого схваленого main. Worker не оновлює
   себе з artifact-а й не виконує завантажений код. Передавай йому лише ID:

   ```sh
   python3 scripts/poco_ci_worker.py \
     --run-id RUN_ID \
     --serial 192.168.2.121:43045 \
     --reports-dir /tmp/slukhayka-poco-ci
   ```

3. Worker вимагає завершений успішний `workflow_dispatch` саме
   `poco-device.yml`, того самого repository та `main`. GitHub compare API
   має підтвердити, що SHA build-а є предком поточного `main` або ним самим.
   Перед інсталяцією перевіряються source SHA й SHA-256 обох APK.
4. Результат — `receipt.json`, `source.json` і окремі логи у
   `<reports-dir>/<run-id>/`. Наявний каталог не перезаписується: повтор
   потребує явної перевірки попереднього результату й іншого reports-dir.
5. За потреби додай `--publish`: через **наявний** `gh`-доступ worker
   публікує commit comment із source SHA, hosted run URL і хвостами логів,
   та commit status `device/poco-f3`, що посилається на цей receipt.
   Без прапорця немає записів у GitHub. Нові токени чи secrets скрипт не
   встановлює. Якщо чинний доступ не дозволяє comment/status write,
   локальний receipt лишається для координатора; запис не вважається успішним.

При автоматичному виклику координатор передає worker-у run ID тією самою
командою після завершення hosted job. Постійний GitHub runner чи відкритий
мережевий listener на Omarchy не потрібні. Реальна активація й перший
успішний job ще попереду: поточний MIUI preflight завершується до GitHub API.
Автоматичного polling service/timer ще немає. Його можна вмикати лише після
першого справжнього зеленого device job; поточний етап готує ручну доріжку.

## Дані й межі

`.github/gradle/poco-ci.init.gradle` задає окремий applicationId
`com.slukhayka.audiobooks.ci.pocof3` і відповідний test package. Hosted job
читає package name обох готових APK через `aapt2` перед публікацією.
Звичайні `.debug` і release не замінюються.
`IsolatedDatabaseTestRunner` використовує окрему scratch-базу.

Hosted AGP debug key не є стабільним між свіжими runner-ами. Тому після
перевірки походження worker перепідписує **обидва** APK одним локальним
CI-only ключем. Він створюється один раз у
`~/.local/state/slukhayka-poco-ci/debug.keystore` і повторно використовується.
Потрібні `keytool` і Android build-tools 36.0.0; на Omarchy вони наявні.
`ANDROID_HOME` або `SLUKHAYKA_CI_APKSIGNER` задають шлях до інструмента.
Це окремий debug signer зі стандартним debug password, не ключ продукту.
Його не копіюють у репозиторій, APK artifact чи GitHub secrets.
Збережи signer при перенесенні worker-а: якщо CI-пакет уже встановлений,
а локальний ключ загублено, worker відмовляє до генерації іншого ключа.
Uninstall як спосіб виправити підпис не використовується.

Receipt записує вихідні й перепідписані APK hashes та публічний SHA-256
сертифіката. При перевірці пакування два послідовні signing jobs на Omarchy
перепідписали обидва наявні тестові APK тим самим незміненим тимчасовим
ключем; `apksigner verify` підтвердив підпис. На телефоні прочитано лише наявність CI-пакета; інсталяцій чи
інструментації в цьому proof немає. Це не доказ проходження device suites.

Пристрійний скрипт не збирає код, не робить uninstall, не перезапускає ADB
server і не змінює налаштування телефону. Анімації й navigation mode також
не перемикаються: це перевірка OEM-пристрою, не точна копія емулятора.
Усі ADB-команди мають явний `-s`. Після таймауту інструментації скрипт
зупиняє лише свій CI-пакет.

Виконуються ті самі п'ять класів, що у
`scripts/run-instrumented-suites.sh`. Кожен має окремий запуск і свіжу
scratch-базу. Порожній набір, crash, незавершена інструментація і
`FAILURES!!!` не стають зеленими через код виходу ADB 0. Загальний logcat
і скриншоти інших застосунків не збираються.

Host-тести перевіряють guard-и й відбір build-а; вони не доводять, що
телефон запускає UI. Потрібен справжній receipt зі всіх п'яти сюїт.

[GitHub застерігає від загальних self-hosted runners на public repository](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/add-runners).
Тут цю межу тримає pull-worker: він приймає лише готовий перевірений main
build і не виконує довільних Actions jobs на Omarchy.
