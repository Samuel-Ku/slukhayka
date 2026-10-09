# personal-interests-v5-main54: підготовлена перевірка інтеграції

Це preregistration підготовленого протоколу, не receipt запуску. На момент написання actual freeze, інференс, fold scores та CI-повтор **ще не виконані**. Нового рішення quality gate немає. Після source review цей файл прив'язується до нового ledger через SHA-256; не змінюй його після freeze.

Історичний [personal-interests-v5](../2026-10-05-personal-interests-v5/README.md) дав NO-GO: recall@20 та NDCG@20 обох моделей — 0 на всіх 24 folds. Його inputs, report, catalog, folds та result hashes зберігаю побайтно. Цей протокол не переписує старий результат.

## Єдина зміна протоколу

Main розширив чинний GenreIdentity у #702 / PR1160: додано сім жанрових shelves, які справді заявляють джерела, aliases та migration support. Freeze v5 прив'язаний до старого source файлу й правильно відмовляється оцінювати current main з чужими fingerprints.

Перевіряю той самий алгоритм [окремих інтересів v5](../../../specs/2026-10-05-personal-interests-ranking.md) із **чинним production GenreIdentity**. App та обидві моделі evaluator використовують один production ranker. Історичної копії facets або evaluator-only алгоритму немає. Це integration version протоколу, а не нова формула рекомендацій.

Не змінюю assets/model revision, tokenizer/backend v3, recording-text-v4, raw snapshot, 18 188 Works, 17 757 назв, 24 relevance labels, 112 aliases чи representative selection. Positive cap 20, усі negatives, denominator усіх signed supports, coefficient .70, configured weights, K20, freshness, diversity, exploration та exclusions лишаються v5. Labels — погоджена expert bibliographic proxy, не історія слухача. Підбору чисел чи міток за метриками немає.

Гейт незмінний: E5 recall@20 строго більший за keyword baseline; E5 NDCG@20 не нижчий. NO-GO записує докази та повертає exit1. Він лишає quality AC #487 відкритим. Навіть GO на цій вибірці не доводить користі для живого слухача.

## Порядок до нових оцінок

Source integration з current main і незалежні Spec/Standards reviews → окремий official `--prepare` → незалежна перевірка actual source/protocol/facet/registration fingerprints і frozen input ledger → acceptance scoring → стандартний повтор. Під час підготовки цього документа цей порядок ще не виконаний; source preparation не є receipt inference або CI dispatch.

Runner сам обчислить fingerprints із фактичних merged source bytes. Наперед обчисленого чи скопійованого integration ledger тут немає. `--prepare` використовує actual runtime jar та ORT environment/version без embedding/fold scoring; це native підготовка, а не чисте читання source.

Збережений поруч `real-scale-inputs-pre-dedup-review.properties` — побайтна історична копія, потрібна чинному lineage hash. Поточні cohorts, original cohorts, identity aliases, model lock та tokenizer provenance лишаються у [спільній папці](../../README.md). Snapshot — `docs/recommend/snapshots/librivox-2026-10-04`. Новий ledger і чотири файли результатів з'являться тут лише після відповідних actual запусків; готових метрик цей README не містить.

Embedding context не перейменовується через жанрову інтеграцію. Journals придатні лише після штатних model/context/ID/text/dimension/norm/checksum перевірок; їхню платформу й походження треба вказати в run evidence. Cache identity не включає OS/JDK. One-work Mac/Linux diagnostic уже показав raw-hidden difference перед pooling, тому reuse Mac journals не є Linux parity proof. Нового native comparison, manual Linux dispatch або іншого model experiment цим документом не запускаю.

## Команди після source reviews

```sh
./gradlew :app:runRecommendationEval --args="--prepare" --no-daemon --no-configuration-cache --max-workers=2 --console=plain
# Лише після перевірки actual freeze, окремо:
./gradlew :app:runRecommendationEval --no-daemon --no-configuration-cache --max-workers=2 --console=plain
```

Assets мають бути встановлені за [чинним model lock](../../real-scale-model.json). Normal CI залишає strict quality gate і 14-day evidence retention; окремий one-work diagnostic workflow не змінюється. Відсутність нового ledger блокує автоматичний scoring, а stale fingerprints блокують його після freeze.
