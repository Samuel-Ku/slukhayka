# Холодний Linux-прогін personal-interests-v5-main54

Звичайний [CI #37969862229](https://github.com/Samuel-Ku/slukhayka/actions/runs/37969862229) заново обчислив усі вектори й отримав **NO-GO**. Recall@20 і NDCG@20 E5 та keyword baseline — 0 на всіх 24 folds. Кращої якості E5 досі немає.

CI перевіряв merge `086c0df1e6d329d551591bb639f0b2f582e9b918`: HEAD `b46835f66e1ac26e9dc44e0a086bf291700ce7ae` із main `f8827d867c3a8b3d174530fe494a26b39660f73a`. Ubuntu 24.04.5, Linux x64, Temurin 21.0.12-1. Заморожені входи SHA-256 `87c4f398706c8d16fcf5e4bc7d7071feeecb7db941622894b13b93154965b6d5` ті самі, що в [локальних Mac-прогонах](../reproducibility.md).

Команди цього CI:

```sh
./gradlew :app:downloadE5Model --no-daemon
./gradlew :app:runRecommendationEval --no-daemon
```

Assets відновлено з кешу; download target повідомив, що модель уже є. Evaluator перевірив pinned model/tokenizer/runtime й source fingerprints. Поточний vector cache спочатку мав 0/18 188 для кожної моделі. Linux обчислив 18 188 нових E5 vectors за 901,3 секунди та 18 188 keyword vectors за 13,1. Це звичайний full-catalog CI, окремий від попередньої ручної діагностики одного Твору.

Звірив усі 24 пари cohort/heldOutWork, 18 188 унікальних Works, candidate counts 18 184–18 185, top-20, training exclusions та ranks. В обох моделях усі held-out Works поза top-20. У всіх 24 folds із Mac відрізняється лише semanticTopK: збіг множин — 16–19 із 20, у середньому 18. Baseline top-20, folds identity, candidate counts і ranks незмінні. Причину розбіжності цим зіставленням не визначаю.

П’ять raw файлів з artifact `11637610502` збережені тут побайтно. Report, catalog і ledger збігаються з Mac. Folds і файл контрольних сум відрізняються. У Linux manifest п’ять entries: три для завантажених файлів і дві заявлені суми journals. Mac manifest також містить шість історичних journals. Заявлена сума поточного semantic journal відрізняється; baseline journal — та сама. Самих native vectors у пакеті немає. Перевірив три наявні manifest targets; byte parity vectors, compiled classes чи native backend цим не доведена.

Файл `EVAL-REPORT.md` зберігає відносні посилання початкової папки main54. Для переходу за ними відкривай [побайтно тотожний звіт у початковій папці](../EVAL-REPORT.md). [Незмінна preregistration](../README.md) лишається там само.

| Файл | SHA-256 Linux |
| --- | --- |
| [EVAL-REPORT.md](EVAL-REPORT.md) | `d76e20b33d03f2510dcc1d6ee866c108dba8a37556c566beeee1d6346432ab0b` |
| [real-scale-inputs.properties](real-scale-inputs.properties) | `87c4f398706c8d16fcf5e4bc7d7071feeecb7db941622894b13b93154965b6d5` |
| [real-scale-folds.tsv](real-scale-folds.tsv) | `752f057b8dce965b0138defebb8f57708105632408e658489e8752cab6d90bef` |
| [real-scale-catalog.tsv](real-scale-catalog.tsv) | `200f369f2870e2a88b2ddc5c712d5ab70d778fb41f26456cd0f86153a7218a86` |
| [real-scale-results.sha256](real-scale-results.sha256) | `33c228e3228b9f05b2502d210a2b2e31a5d0bc34736cdc3492b634e62081b11b` |

Після запису результатів strict gate повернув exit1. Далі Gradle повідомив про 2 525 configuration-cache storage problems, 358 unique, у Chaquopy/AGP tasks. Це окрема помилка кешу після завершених оцінок; вона не пояснює нульові метрики. Весь CI завершився failure; решта jobs успішні або пропущені, Kover aggregation пройшов.

Це бібліографічна proxy-перевірка, не доказ користі під час слухання. Quality gate та runtime-release silent-fallback AC #487 залишаються відкритими. Старі результати Mac, preregistration, модель, алгоритм, labels і пороги збережені.
