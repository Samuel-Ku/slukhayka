# personal-interests-v5-main-ec19: перевірка поточного main

Це preregistration нового протоколу для main `ec19e50f21de6e22dd9032d2ae3729574adff278`. Actual freeze, повний прогін, стандартний повтор і приватна діагностика ще не виконані. Нових метрик або рішення quality gate немає. Після штатного `--prepare` цей README прив'язується до нового ledger через SHA-256 і більше не змінюється; фактичні результати записуються окремо.

Історичні [personal-interests-v5](../2026-10-05-personal-interests-v5/README.md) та [personal-interests-v5-main54](../2026-10-09-personal-interests-v5-main54/README.md) дали NO-GO 0/0 на 24 folds. Їхні preregistrations, ledgers, reports, catalog, folds і result hashes залишаються побайтно незмінними. [CI після інтеграції main](https://github.com/Samuel-Ku/slukhayka/actions/runs/38077146244/job/114286404867) відмовив старому протоколу через `Frozen evaluation inputs changed` до створення семантичної моделі, інференсу та оцінок. Це відмова за fingerprints, не новий NO-GO.

## Що змінюється

Main додав канонічне `Попаданці` → `portal-fantasy` і пояснення заяви джерела до production GenreIdentity (#1053, PR #1210). Читання жанрів chitaka лишається окремою роботою. Source SHA-256 GenreIdentity змінився з `a0fe417e4f06547c06d712fa38a7e30a9be74c97d0815645783249b556c0b030` на `ad36a8e68d514c494d3915da812e22cc274737f04800ee716278701cf91af0ad`. Зміна source не доводить зміни ranks або якості.

Перевіряю той самий алгоритм [окремих інтересів v5](../../../specs/2026-10-05-personal-interests-ranking.md) із чинним production GenreIdentity. App та обидві моделі evaluator використовують один production ranker. Історичної копії facets, evaluator-only формули чи додаткового semantic запиту немає.

Нові protocol name, registration SHA, protocol source SHA та facet source SHA належать цій інтеграції. Host змінює лише дві константи активного протоколу. Час freeze та ledger SHA з'являться зі штатного runtime, а не з підготовлених наперед значень. CI читає новий ledger для cache key і зберігає результати в новій папці. Embedding context через зміну жанрової ідентичності не перейменовується.

## Що лишається незмінним

Не змінюю 44 raw сторінки [snapshot](../../snapshots/librivox-2026-10-04/), 18 188 Works, 17 757 назв, representative selection, 112 aliases, 24 relevance labels і п'ять груп. [Чинні labels](../../real-scale-cohorts.json), [початкові labels](../../real-scale-cohorts-original.json) та [реєстр тотожності](../../real-scale-identity-aliases.json) залишаються тими самими. Labels — погоджена expert bibliographic proxy, не історія завершень слухача. Відсутній The Secret Adversary не повертається й не замінюється.

[Model lock](../../real-scale-model.json), model revision, model/tokenizer/runtime jar checksums, tokenizer/backend v3 і recording-text-v4 незмінні. Специфікація v5 SHA-256 `145f6e1485b18ae0dd921662d38d878eb00b613585687ce064523d3ac931be2b` лишається тією самою. Positive cap 20, усі negatives, спільний denominator signed supports, coefficient .70, configured weights, K20, freshness, diversity, exploration та exclusions не змінюються. Вага навчальних творів .9 лишається за frozen registry. Не підбираю числа, texts, labels або порядок за отриманими метриками.

`real-scale-inputs-pre-dedup-review.properties` поруч — побайтна копія історичного lineage input з main54, SHA-256 `378c9a71d01f9cbdf0f8a593e22a9f4475b7d017491d66d846ccde606899bf5e`. Це не новий frozen ledger.

## Порядок запусків і докази

Незалежні source reviews → один official `--prepare` → незалежна перевірка actual freeze → один повний official run на 24 folds → один наперед заявлений стандартний replay. Після кожного запуску перевіряю природний exit, actual inputs/source/tool/runtime fingerprints і цілісність історичних матеріалів. До прийнятого freeze scoring не запускається. Під час підготовки цього документа жодного з цих runtime кроків не виконано.

`--prepare` читає фактичний runtime jar та ORT environment/version без embedding або fold scoring. Це native підготовка. Її wall limit — 900 секунд. Кожен повний локальний scoring run обмежений 90 хвилинами. Запуски серіалізуються зі збірками; таймаут або примусове завершення не є прийнятим результатом. Автоматичних повторів, збільшення меж чи послаблення gate немає.

Кеші використовуються лише після штатних перевірок model/context, ID/text, dimension, norm і checksum. Для локального full run та replay використовую окрему перевірену копію журналів; історичні оригінали не змінюю. Незмінний official loader обчислює відсутні вектори: cache miss може спричинити інференс. Це не обіцянка нульового inference. Походження й SHA журналів записуються до actual evidence. Mac journals не є доказом Linux native parity: context не містить OS/JDK, а one-work diagnostic вже показав raw-hidden difference до pooling.

Повний run записує тут п'ять файлів: `real-scale-inputs.properties`, `EVAL-REPORT.md`, `real-scale-folds.tsv`, `real-scale-catalog.tsv`, `real-scale-results.sha256`. Перевіряю всі 24 folds, 48 top-20, training exclusions, catalog та actual result hashes. Стандартний replay має відтворити всі п'ять файлів; наперед такого збігу не стверджую. Зберігаю argv, raw log, terminal exit, source/runtime/cache provenance і before/after fingerprints.

Гейт незмінний: E5 recall@20 строго більший за keyword baseline; E5 NDCG@20 не нижчий. Повний NO-GO записує докази й повертає exit1. Природний NO-GO після перевірки actual evidence допускає вже заявлений replay; infrastructure, fingerprint або cache failure зупиняє послідовність. Quality AC #487 лишається відкритим до прийнятого GO і replay. Навіть GO на цих labels не доводить користі під час живого користування.

## Команди після source reviews

```sh
./gradlew :app:runRecommendationEval --args="--prepare" --max-workers=1 --no-daemon --no-build-cache --no-configuration-cache --console=plain
# Лише після незалежно прийнятого actual freeze; шлях — окрема перевірена копія журналів:
./gradlew :app:runRecommendationEval --args="--cache /absolute/path/to/verified-cache-copy" --max-workers=1 --no-daemon --no-build-cache --no-configuration-cache --console=plain
# Один наперед заявлений стандартний replay після перевірки повного actual результату:
./gradlew :app:runRecommendationEval --args="--cache /absolute/path/to/verified-cache-copy" --max-workers=1 --no-daemon --no-build-cache --no-configuration-cache --console=plain
```

Assets встановлюються за незмінним model lock. Звичайний PR CI після publication виконує official full evaluator, може обчислювати відсутні вектори й лишає strict gate, 90-minute limit та 14-day evidence retention. Нового manual Linux dispatch або native comparison немає. Відсутній ledger блокує scoring; stale fingerprints після freeze також блокують його.

## Окрема приватна діагностика

Після прийнятого повного run і стандартного replay окремий приватний observer може виконати один cached full24 replay, спостерігаючи тільки два calls першого registry fold. Його source packet, compile-only і runtime мають окремі перевірки та fail-closed no-embed sentinel. Final full24 output звіряється з прийнятим current-main reference; старий main54 TSV не є його oracle. Дві спостережені строки не стають official quality result.

Ця діагностика не змінює evaluator, формулу, labels або thresholds. Debuggee limit — 600 секунд, controller — 660 секунд; два observation windows, до 128 method events на window, до 8192 events загалом. Inclusive runtime evidence не перевищує 10 MiB разом із terminal. Post-audit усіх originals/copies/source/classpath/tools/protected inputs обмежений 60 секундами, 512 MiB на файл і 8 GiB загалом. Timeout, cache miss, unknown shapes, mismatch, missing final embed counter або незавершені owned processes дають INCOMPLETE/STOP без автоматичного повтору. Старий incomplete trace не перезапускається.
