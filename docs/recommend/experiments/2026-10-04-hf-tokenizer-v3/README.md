# Незмінний експеримент v3: оголошений HF tokenizer

Результат — **NO-GO**. E5 і keyword baseline дали recall@20 = 0 та NDCG@20 = 0 у всіх 24 leave-one-out fold. Вимога кращої якості E5 у #487 ще не виконана. Мітки, модель, ranking weights та пороги не змінюються заради GO.

Production tokenizer виправлено в `f7a1a8de7d477c3eb7895d210ce4acb82c8df1be`. Окремий `9ed54c4abc2619ba27fb8330a303a2aadc557ac3` змінює лише два абзаци generated report. Входи v3 зафіксовано 2026-10-04 о 19:11:45.072137 UTC до нового інференсу, а потім незалежно перевірено; SHA-256 `4d33b8393bf533fae886db9150317bab95c2911c35b4f93fa4103409e465ebec`. Ledger-only commit і HEAD повного прогону — `009cb87b5ca8d3c77faa7637620b98671907b080`. Обидва незалежні source reviews завершилися без findings до цього прогону; correction report prose теж має окремий Spec PASS.

Виправлення виконує scalar-aware Unigram із unknown edge, pinned Precompiled charsmap, Metaspace і raw added tokens. Unicode 16 segmenter має зафіксовані таблиці; це не залежна від JVM апроксимація. Query prefix, BOS/EOS, budget 512 і pooling лишилися як у v2. [Джерела й ліцензії](../../tokenizer-provenance.md) пояснюють підтриманий контракт та межі parity доказу.

Дані незмінні від v2: 21 821 реальна картка, 18 188 Works, 17 757 різних нормалізованих назв, 24 експертні мітки, 112 записів тотожності й 10 guards для окремих пов'язаних творів. Fold мають 18 184 або 18 185 кандидатів. Це дозволена бібліографічна proxy-вибірка, не журнал завершень слухачів і не доказ живої користі. [V1](../2026-10-04-production-tokenizer-v1/README.md) та [v2](../2026-10-04-e5-model-input-v2/README.md) лишилися побайтно незмінними.

## Перевірка

Targeted Gradle session 43718 завершився з exit0 за 6m6s: 15 класів, 92 тести, 0 failures, errors чи skips. Свіжий pure-JVM прогін тих самих 15 класів теж дав 92/0. Production segmenter пройшов усі 1 093 офіційні Unicode випадки. Public raw/model encode дав точний збіг усіх 30 порівнянь на 15 текстах, зафіксованих до IDs, і всіх 68 порівнянь на незалежних 34 текстах. Обидва набори поза relevance cohorts. Parity не визначає quality gate.

Actual cold ONNX session 53010 завершився з exit1 після запису результатів. Семантичний кеш почався з 0/18 188; усі 18 188 E5-векторів обчислено заново за 1547.9s. Keyword baseline повторно використав усі 18 188 перевірених векторів. Підміна семантичної колонки keyword fallback заборонена.

Стандартний `./gradlew :app:runRecommendationEval --no-daemon --no-configuration-cache --console=plain`, session 35391, повернув exit1 за 3m49s. Обидва journals перевірені повністю: по 18 188 cache hits, без нового інференсу. Report, folds, catalog, ledger і result hashes тотожні cold-прогону. FAILED тут означає справжній NO-GO, а не успішний CI gate.

Незалежна перевірка підтвердила всі 24 folds, training exclusion, candidate counts, top-20 ranks і всі дев'ять result SHAs. Окремо перевірені dimension, norm та checksum кожного з 18 188 semantic і 18 188 baseline записів. Raw XML, logs, parity outputs і binary journals збережені поза Git; їх не підміняє цей README.

| Raw proof | SHA-256 |
| --- | --- |
| Targeted Gradle log | `55583eab28d0fc0af37bc34c6334e3dffe30cf0329374b77835aea756b9d4390` |
| Cold log | `592669f627540108d2608e1cafee4c70d7c85d9b06303187b0ba5736689fed05` |
| Standard Gradle log | `9d21cd9c2d43ab99b4be945d5be0940201072f40b2b53737c5a57d3696ba4bfe` |
| Native 15-class XML manifest | `e08e97d383040341d741d863a25fadb7f69053ba4be247152d833e3a44a60d69` |
| Final 15-text public encode output | `3140b46460de7d7070697744593727bd1de113f8831f60f3ee2f111d16131201` |
| Independent 34-text public encode output | `4b028babebb88cc8a9770c65d17ad8f551957c7a0f100c1974a9dd95a32c94bd` |

## Архів

Десять data/report файлів — побайтні копії actual current v3 після cold і standard replay. `real-scale-results.sha256` містить суми локальних journals разом із збереженими попередніми contexts. Поточні метрики використовують лише перевірений context v3 і незмінний baseline. Модель і journals не комітяться. Наступне виправлення потребує окремого обґрунтування, source review та preregistration до нового інференсу; новий експеримент не дозволений цим архівом.
