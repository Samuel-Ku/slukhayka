# План: агентське середовище після #1486 (#1493)

Ретро сесії, що злила #1488/#1489/#1491, знайшло п'ять місць, де середовище
коштувало дорого або мовчало. Кожен зріз — окремий маленький PR, щоб огляд
бачив один намір, а мутаційний свідок — один предмет.

## Зріз A — docs-only диф не запускає матрикс

`scripts/test_partitions.py select` віддає `fullSuite: true` («unmapped changed
path») на будь-який `docs/**.md` чи `README.md`, тож `scripts/test-changed.sh`
ганяє шість партицій на коміт, який фізично не може зламати тести. У сесії це
коштувало двох прогонів по ~25–40 хв.

- селектор: `is_documentation_only` (`docs/` або `.md`/`.mdx`) → `partitions: {}`,
  `fullSuite: false`, `reason: "documentation only"`; змішаний диф (доки + код)
  і далі мапиться кодом, невідомий недеревяний шлях — і далі фолбек;
- `test-changed.sh`: окреме повідомлення й `exit 0`, коли партицій і чеків нема;
- свідки: `test_test_partitions.py` (docs-only, документи+код, незмінений
  unmapped-фолбек) і `test_test_wrappers.py` (жодного виклику Gradle).

## Зріз B — guard проти втрати тестів

`scripts/test_count_guard.py` порівнює `@Test`/`@ParameterizedTest` у змінених
`*Test.kt` з базою PR; зменшення без `test-removal:` у commit body — відмова.
Підключення: CI-джоба з базою PR, свідок у `guard_witnesses.py`, юніт-тести.

## Зріз C — довгі прогони

Розділ у `docs/agents/swarm.md`: `setsid nohup`, лог/pid/exit-файл, опитування
файлів; межа — це стосується лише віддалених прогонів, локальні лишаються як є.

## Зріз D — статус build-слота

`build-slot-guard.py --status` (або окремий скрипт): free/busy, PID і команда
власника, кількість процесів, що чекають; pointer у `local-environment.md`.

## Зріз E — покажчики й процес

`AGENTS.md` → `scripts/pr-snapshot.py`; «Reviewer brief» у
`work-coordination.md`; `.github/PULL_REQUEST_TEMPLATE.md` із Claims/Evidence/
Merge danger. Це docs/process, без логіки — але з тим самим оглядом.

## Поза обсягом

- Свіжість guidance і required-чек вердикту — #1492 (інша сесія).
- Виправлення вже видалених тестів guard не робить: він лише вимагає явного
  `test-removal:` і робить втрату видимою.
