# Незмінний експеримент v1: production tokenizer

Результат — **NO-GO**. У 24 leave-one-out fold обидві моделі дали recall@20 = 0 і NDCG@20 = 0. Семантична модель не перевершила baseline. Це не успішне виконання вимоги якості #487.

Код: `c89e3a32ab31e1f2b40a73180feb39fd7aaff1fe`. Реєстр входів зафіксований до повторного інференсу: `8bd9b1677f4a547b4939d30ddb53c6c59e5a986383e7b3a8affc93866bb1b82c`. У ньому прив'язані джерела, код протоколу, модель, tokenizer, runtime, тексти й мітки. 21 821 справжня картка після зіставлення дала 18 188 Works і 17 757 різних назв. У кожному fold змагається 18 184 або 18 185 кандидатів. 24 мітки — експертна бібліографічна вибірка, не реальна історія завершень слухачів.

Перший частковий прогін до виправлення тотожностей був зупинений з exit130 до будь-яких fold і метрик. У цьому експерименті всі 112 відомих записів 24 цільових творів уже зведені; 10 перевірених окремих творів залишені окремими. Мітки та пороги не змінювалися.

## Перевірка

Цільовий Gradle-прогін семи класів на цьому коді завершився з exit0: 37 тестів, 0 помилок, 0 пропусків, 6m31s. Класи: RecommendationFullCatalogEvalTest, RecommendationEvalCatalogTest, RecommendationEvalIdentityAliasesTest, RecommendationFeedSnapshotTest, RecommendationEvalVectorCacheTest, RecommendationEvalModelLockTest та RecommendationEvalTest.

Справжній cold ONNX-прогін завершився з exit1: усі 18 188 E5-векторів обчислені за 1571.2s, усі keyword-вектори — за 80.2s. Немає keyword fallback у семантичній колонці. Потім стандартний `./gradlew :app:runRecommendationEval --no-daemon --no-configuration-cache` відтворив NO-GO з exit1 за 1m26s. Обидва журнали перевірені повністю, по 18 188 векторів; повторного інференсу не було. Gradle FAILED тут означає чесне спрацювання гейту, а не успішну CI-перевірку якості.

Окремий Python-перерахунок підтвердив усі 24 fold, повний пул, навчальні виключення, точні позиції top-20, тотожність 112 URL цільових творів, метрики та контрольні суми вихідних файлів. Координатор незалежно підтвердив ці результати й 10 окремих творів. Raw logs і JUnit XML залишені локальними артефактами, не документацією.

| Локальний артефакт у `/private/tmp` | SHA-256 |
|---|---|
| `issue-487-final-targeted-gradle.log` | `55bfd65b08b0f57eda3f894ca7a9214f530ca2fed962e143eacb3b7505678186` |
| `issue-487-real-eval-post-identity.log` | `8e66247a758a15f9d503832a6ad84478aa40e5dd99bdee64454244532f9d938b` |
| `issue-487-standard-gradle-eval.log` | `3c9a4a03dd68948c327fe079ce12ecf74834760aa2754b66a2fe88f9a9098009` |
| `issue-487-check-results.py` | `3b0016c4cfbe120352124aeac2a8de2e7c0019b8c5f34111f316acf8eff00ff9` |

## Збережені входи й результати

Усі файли нижче скопійовані побайтно після стандартного Gradle replay. [44 сторінки джерела](../../snapshots/librivox-2026-10-04/) зберігаються один раз у спільній папці знімків. Локальні журнали векторів не комітяться; результати містять їхні суми, включно зі збереженим попереднім частковим контекстом, який не використаний для цих метрик.

| Файл | SHA-256 |
|---|---|
| [real-scale-inputs.properties](real-scale-inputs.properties) | `8bd9b1677f4a547b4939d30ddb53c6c59e5a986383e7b3a8affc93866bb1b82c` |
| [EVAL-REPORT.md](EVAL-REPORT.md) | `1390a896c23e221a11adb08bdf902d318e3021a4900b4bfd55208193bf39037c` |
| [real-scale-folds.tsv](real-scale-folds.tsv) | `1ff7bc307582b12567e5379bcb45ba601f2ee36bfe2cf134513135e7fc9f6546` |
| [real-scale-catalog.tsv](real-scale-catalog.tsv) | `b353f5cd211d2f7851f12e8b70bf28890d5dcfb4c63331f363c03e109b8bfbc7` |
| [real-scale-results.sha256](real-scale-results.sha256) | `c8ea9e2db05f5cef106cfec81060e4494296a13fe82704cea680e0b732973206` |
| [real-scale-model.json](real-scale-model.json) | `87b21a956c0efcd59a550bc19535b02f65817cb734c3a7c834ab5fef518a3800` |
| [real-scale-cohorts.json](real-scale-cohorts.json) | `197205d7457c6ebe805e770940693f927f0d13d43278ea3dd704867199147ea0` |
| [real-scale-cohorts-original.json](real-scale-cohorts-original.json) | `f0ec8687ce9d6321cea44ec6c04eac2dd7ecb5d2676d13f1392708419ed50df0` |
| [real-scale-identity-aliases.json](real-scale-identity-aliases.json) | `44186169f93b64a889e262921e13512aebe7a737ba96267fa5e8a34c7ee60fdc` |
| [real-scale-inputs-pre-dedup-review.properties](real-scale-inputs-pre-dedup-review.properties) | `378c9a71d01f9cbdf0f8a593e22a9f4475b7d017491d66d846ccde606899bf5e` |

## Наступна перевірка

Зафіксований tokenizer.json задає single-sequence postprocessor `<s>` (0), sequence A, `</s>` (2). Цей production шлях не додає спеціальних токенів. Це незалежна невідповідність контракту, а не підстава переписати цей результат. Виправлення потребує окремого RED/GREEN тесту, перевірки та нового реєстру до наступного інференсу. Мітки й пороги цього експерименту лишаються незмінними. Поліпшення після виправлення не припускається наперед.
