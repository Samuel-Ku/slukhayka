# Заміри helper-функцій на Android ART

Сирі результати CI [45397823404](https://github.com/Samuel-Ku/slukhayka/actions/runs/45397823404): Android 15 / API35, x86_64 google_atd, debug APK. Три окремі процеси. Перед таймінгом кожен probe порівнює результати reference та candidate. У raw-файлах збережені оригінальні logcat без додаткового фільтрування архіватором: дев’ять чергованих серій, медіани й рядки TestRunner. Окремі instrumentation-файли містять фактичні `OK (1 test)` та код завершення кожного процесу.

APK містив сім кандидатів разом. Обидва шляхи кожного порівняння використовують інші оптимізовані helper-функції. Це ізольовані операції; коефіцієнти не перемножуються і не описують запуск, UI або фізичний ARM-пристрій.

Відомі витрати: singleton collections +5–10 мкс, marker digits +6,6–7,2%, branded +1,7–3,8%. Причину singleton-різниці не встановлено; її короткий прогрів не доводить steady-state JIT.

`source-manifest.json` містить точні commits і SHA-256 production/probe/fixture файлів; `apk-sha256.txt` — хеші зібраних APK; `summary.json` — усі 42 медіанні порівняння. Метод підготовки — `.github/scripts/prepare-art-perf.py` у ревізії `01adc1039df00283d2e04a6d54ce1c7b223ecd1f`.
