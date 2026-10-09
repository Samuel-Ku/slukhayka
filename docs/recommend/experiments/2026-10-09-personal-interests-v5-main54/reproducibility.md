# Відтворення personal-interests-v5-main54

Зафіксував окремий протокол після інтеграції main `f8827d867c3a8b3d174530fe494a26b39660f73a`. Чинний production GenreIdentity розширено в #702, тому старі source fingerprints більше не збігалися. Алгоритм v5, модель, тексти, мітки та пороги збережено.

Штатний `--prepare` завершився до будь-яких нових оцінок. Ledger і [незмінну preregistration](README.md) закомітив у `b937415c7f1b2aa3af9ac841dcdd914e11054a9b`. Обидва наступні запуски виконано на цьому самому commit через звичайний target:

```sh
./gradlew :app:runRecommendationEval --max-workers=1 --no-daemon --no-build-cache --no-configuration-cache --console=plain
```

Повний прогін завершився за 146,0 секунди, стандартний повтор — за 147,9. Обидва природно повернули exit1 після запису [NO-GO](EVAL-REPORT.md): recall@20 = 0 і NDCG@20 = 0 для E5 та keyword baseline на всіх 24 folds. Тайм-аутів не було. Кращої якості E5 цей експеримент не показав.

128 зафіксованих source/input files незмінні між запусками. Усі п’ять результатів нижче відтворено побайтно. Старі архіви й входи збережені.

| Файл | SHA-256 обох запусків |
| --- | --- |
| `EVAL-REPORT.md` | `d76e20b33d03f2510dcc1d6ee866c108dba8a37556c566beeee1d6346432ab0b` |
| `real-scale-folds.tsv` | `72a08c9b905746f2cddf29205423111fcf5f6639fdf0d8fef596544cbce07123` |
| `real-scale-catalog.tsv` | `200f369f2870e2a88b2ddc5c712d5ab70d778fb41f26456cd0f86153a7218a86` |
| `real-scale-results.sha256` | `47e14a1fbeac826c7a73ee7e598c8d94e0d24ba853a441d4bb6fd2320b0918e3` |
| `real-scale-inputs.properties` | `87c4f398706c8d16fcf5e4bc7d7071feeecb7db941622894b13b93154965b6d5` |

На кожному запуску evaluator перевірив і повторно використав по 18 188 поточних векторів E5 та baseline. Усі вісім наявних журналів побайтно незмінні до й після обох запусків. Це Mac-журнали з попередніх прийнятих прогонів; нових embeddings не обчислював. Незалежно звірено 24 folds, top-20, training exclusions, candidate counts, ranks, нульові метрики та 11 entries файлу контрольних сум. Окрема перевірка стандартного повтору звірила ті самі результати й журнали.

Фізичні JavaExec-команди обох процесів збережено в локальних доказах. Поточні evaluator, ranker і GenreIdentity завантажувалися з актуальних Kotlin outputs. Пізніші старі копії Genre/Facet у runtime jar перекриті порядком classpath; вони не були effective classes цих двох процесів. Після завершення власні Gradle та JavaExec-процеси відсутні.

Це перевірка незмінної бібліографічної proxy-вибірки й поточного production ранжування з перевіреним кешем. Вона не є cold inference, особистим журналом слухача чи перевіркою користі у живому використанні. [Діагностика Mac/Linux одного Твору](../../boundary-comparison-2026-10-09.md) встановила розбіжність raw hidden state до pooling; ці Mac-журнали не доводять Linux native parity. Нового ручного Linux-запуску тут не було.
