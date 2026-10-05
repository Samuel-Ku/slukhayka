# Незмінний експеримент recording-text-v4

Результат — **NO-GO**. E5 і keyword baseline дали recall@20 = 0 та NDCG@20 = 0 на всіх 24 leave-one-out fold. Вимога кращої якості E5 у #487 ще не виконана. Модель, мітки, ранжування й пороги після результату не змінено.

Цей експеримент прибирає лише точно впізнані службові шаблони запису з production Work text. Цитати, неоднозначний зміст, індивідуальні читці й бібліографічні посилання зберігаються. Повного очищення описів не стверджую. [Правила й межі](../../../adr/0061-real-catalog-recommendation-eval.md#експеримент-recording-text-v4-службовий-текст-запису) описують підтримані шаблони. Tokenizer/backend залишається v3; його контракт `e5-input-v3-hf022-unicode16-template-mean-l2` і фактичні assets незмінні.

## Входи й порядок перевірок

Назва папки 2026-10-04 зберігає дату першої реєстрації політики. Фіксація входів та обидва повні запуски відбулися 2026-10-05. Latest main інтегровано в `3d2723db399261568c0a9a9dea7937ef56a7ad38`. П'ять перевірених source/test/docs файлів закомічено в `4e1347a48b4a83733f9dde3682fbeb0616356a07`. Обидва незалежні source reviews завершилися без findings до нових оцінок.

[Входи](real-scale-inputs.properties) зафіксовано 2026-10-05 о 07:05:21.114424 UTC без інференсу й fold scores. SHA-256 ledger — `3e0dcb9e05280c93635ba2ddaad5a5beed737411b33185f1dc69d15c37ece64e`. Після фіксації незалежно звірено всі тексти та тотожності Works. Ledger-only commit і HEAD cold/replay — `61bc5614e4847d04547057ac4a903e4b6658015d`, до нового інференсу.

Залишилися ті самі 21 821 реальна картка, 18 188 Works, 17 757 різних нормалізованих назв, 24 експертні мітки, 112 записів тотожності й 10 guards для окремих пов'язаних творів. У fold 18 184 або 18 185 кандидатів із виключенням навчальних книг. Це бібліографічна proxy-вибірка, не журнал завершень слухачів і не доказ живої користі. Модель, raw feed, мітки, тотожності, K=20, ranking weights і строгий quality gate незмінні.

Змінено тексти 12 914 Works. Candidate text SHA-256 — `88a6022a82d0fb4b636dba710ef065853d1e185230b3a785f6ce9ce804a2ce40`. У catalog TSV змінилася лише колонка textSha256; усі ID, назви, автори, жанри, source URLs та порядок збережені. Його SHA-256 — `200f369f2870e2a88b2ddc5c712d5ab70d778fb41f26456cd0f86153a7218a86`, тотожний підготовленій до інференсу таблиці. Зміна всього набору текстів створила нові контексти journals обох моделей. Backend source hash і semantic preprocessing identity залишилися v3.

## Фактичні запуски

Public host BookRecommendationText дав 17/17 після послідовних RED/GREEN і перевірок меж. Ранню помилку компіляції `unresolved description` збережено окремо; вона не вважається поведінковим RED чи GREEN. Targeted Gradle session 42716 завершився з exit0 за 6m44s: шість класів, 47 свіжих тестів, без failures, errors чи skips. Розподіл — 17+5+10+5+5+5. Після цього actual host runner свіжо скомпільовано в session 31062, а prepare-only 19429 завершився з exit0. Доказ tokenizer v3 не змінено: 98 точних raw/model порівнянь поза cohorts і всі 1 093 офіційні Unicode випадки. Це сумісність входів, а не GO.

Actual cold session 48390 завершився з exit1 після запису NO-GO. Обидва journals почалися з 0/18 188. Усі 18 188 E5-векторів обчислено заново за 1501.4s; усі 18 188 keyword-векторів — за 78.8s. Семантична колонка використовує справжній ONNX E5, без keyword fallback.

Стандартний `./gradlew :app:runRecommendationEval --no-daemon --no-configuration-cache --max-workers=2 --console=plain`, session 50131, повернув exit1 за 1m43s. Обидва journals дали по 18 188 перевірених cache hits, нових векторів — 0. Report, folds, catalog, ledger і result hashes побайтно тотожні cold-прогону. FAILED тут означає справжній NO-GO; цей запуск не є успішним CI gate.

Незалежна перевірка підтвердила всі 24 folds, навчальні виключення, top-20 ranks, counts, 112 aliases, 10 guards і всі 11 result SHAs. Окремо звірено ID, dimension, norm і checksum кожного з 18 188 E5 та 18 188 keyword записів. Шість попередніх journals збережені без змін. Поточні оцінки використовують тільки нові перевірені контексти recording-text-v4.

| Raw proof | SHA-256 |
| --- | --- |
| Public host RED/GREEN evidence manifest | `3bcb080c4e822eb3da672dfe397029804046c4a272058a6bb4534beb487b5c0c` |
| Targeted Gradle log | `d8a4c855e5f21e83c5b1dc806337e6c323343975a603d6f914654a9abf3f1408` |
| Native six-class XML manifest | `10350748cd77e993c96d6c6e85c7868f1fed23a775485443b0b0fe5083b2f766` |
| Cold log | `05ce7238b26ff17f944a7942fbc17ed2e6e099efe5ffa0974314c7eb6fd41b0d` |
| Standard Gradle log | `55f5ed03eda1ada5a4ab8170e2cdb2b47851f9dd4230639b4a9f406f40df0b0f` |
| Independent fold/result proof | `32d1f881dbb400f8c4cea1c2dfb6891a5beba5ae32f4224bdd38873ef89e1d6a` |
| Independent current vector journal proof | `dc81f7b5b7cabb7aa172d7f6e667379025642ed5d85396938ac14911803f6216` |

## Архів

Десять data/report файлів — побайтні копії actual current recording-text-v4 після cold і standard replay. [Суми результатів](real-scale-results.sha256) включають локальні journals попередніх контекстів. Модель, binary journals, raw XML та logs збережені поза Git. Сам README не замінює ці докази.

Усі 33 файли попередніх [v1](../2026-10-04-production-tokenizer-v1/README.md), [v2](../2026-10-04-e5-model-input-v2/README.md) і [v3](../2026-10-04-hf-tokenizer-v3/README.md) лишаються побайтно незмінними. Чистіший текст не дав переваги E5 на зафіксованій вибірці. Новий експеримент потребує окремого обґрунтування, source review та фіксації входів до оцінок; цей архів не дає дозволу змінювати ранжування чи мітки.
