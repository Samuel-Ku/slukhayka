# Незмінний експеримент v2: контракт входу E5

Результат — **NO-GO**. У всіх 24 leave-one-out fold E5 і keyword baseline дали recall@20 = 0 та NDCG@20 = 0. Вимога кращої якості E5 у #487 ще не виконана. Пороги, мітки й результат не змінюються, щоб отримати GO.

Код production виправлення: `00fec76760ca4d102ef8e6ae4ac42d217cbea813`. Прогін виконаний на HEAD `d9b3610c7c9b8ec119b51ab5304b5223204271ee`; два commits між source `00fec767` і run HEAD `d9b3610c` змінювали лише ledger і статус README. Входи v2 зафіксовано 2026-10-04 о 16:50:32.746928 UTC до нового інференсу, SHA-256 `f5deda78a612fbbe5e9f3363b74a4d3f67d329b1246854e565bccad8aa3b9634`, а потім незалежно звірено. Обидва незалежні source reviews завершилися без findings до цього прогону.

V2 додає BOS/EOS із зафіксованого tokenizer.json до загального бюджету 512 та використовує `query: ` для симетричного порівняння книг за [офіційною E5 model card](https://huggingface.co/intfloat/multilingual-e5-small/raw/main/README.md). Повна тотожність сегментації Hugging Face не стверджується. Backend identity включає фактичні asset SHA, runtime і preprocessing. Room schema незмінна; legacy або несумісні vectors стають cache miss. Generation gate і coherent snapshot відвертають стару публікацію після model install та повтори незмінних failed signals.

Дані незмінні від v1: 21 821 реальна картка, 18 188 Works, 17 757 назв, 24 експертні мітки, 112 відомих записів цих творів та 10 guards для окремих пов'язаних творів. Кожен fold використовує 18 184 або 18 185 кандидатів. Модель, тексти, identity aliases, ranking weights і GO-пороги не змінені. Це дозволена бібліографічна proxy-вибірка, не особиста історія завершень і не доказ живої користі. [Експеримент v1](../2026-10-04-production-tokenizer-v1/README.md) залишений побайтно незмінним.

## Перевірка

Targeted Gradle-прогін 13 класів завершився з exit0 за 6m9s: 73 тести, 0 failures, errors чи skipped. Перевірені початкові сім Eval-класів, E5RecommendationInputTest, UnigramTokenizerTest, EmbeddingBackendCacheTest, RoomEmbeddingCacheTest, EmbeddingPassGateTest та EmbeddingPassSnapshotTest. Raw XML і logs збережені поза repository.

Справжній cold ONNX-прогін завершився з exit1 після запису результатів: 18 188 нових E5-векторів за 1548.7s і 18 188 нових keyword-векторів за 76.0s. Початкові cache hits обох моделей — 0. Keyword fallback у семантичній колонці не дозволений.

Стандартний `./gradlew :app:runRecommendationEval --no-daemon --no-configuration-cache --console=plain` відтворив NO-GO з exit1 за 1m29s. Обидва cache journals перевірені повністю, по 18 188 hits, без нового інференсу. Ledger, report, folds, catalog і result hashes лишилися тотожними cold-прогону. FAILED означає спрацювання строгого quality gate; це не успішний CI gate.

Окремий Python-перерахунок і незалежна перевірка підтвердили 24 fold, 18 188 unique Work IDs, точні top-20 ranks, навчальні виключення, 112 alias URLs, candidate counts, 10 distinct guards, метрики 0/0 та вісім result hashes. Перевірений вхідний контракт сам по собі не означає кращої якості ранжування.

| Локальний артефакт у `/private/tmp` | SHA-256 |
|---|---|
| `issue-487-v2-final-targeted-gradle.log` | `694919dd42f6558cc2cec5a022567200c35ef8fab2b372ae99da89edbe30db2e` |
| `issue-487-v2-real-eval.log` | `0b0da332e362e54eb1e99df44089c3d2abb10ea65ae751f13d5727d4b29bd2d9` |
| `issue-487-v2-standard-gradle-eval.log` | `0934ba1707b93f2e91eeabd9ad74a6d11528d957c081598a9db87fa43e11597b` |
| `issue-487-v2-prepare.log` | `9b7dcfbda233548425399eeae52a621d0ff0024d348599375baf383adf2678eb` |
| `issue-487-check-results.py` | `3b0016c4cfbe120352124aeac2a8de2e7c0019b8c5f34111f316acf8eff00ff9` |

## Збережені входи й результати

Ці десять файлів скопійовані побайтно після стандартного replay. [44 сторінки джерела](../../snapshots/librivox-2026-10-04/) зберігаються один раз. Локальні vector journals не комітяться; result manifest містить їхні суми, включно з попередніми contexts, які не використовувалися для v2 scores.

| Файл | SHA-256 |
|---|---|
| [real-scale-inputs.properties](real-scale-inputs.properties) | `f5deda78a612fbbe5e9f3363b74a4d3f67d329b1246854e565bccad8aa3b9634` |
| [EVAL-REPORT.md](EVAL-REPORT.md) | `05508a9a7e7711a3a2e58a9d4c5c22ba1a25eb48e876fda89a03ea1be57ad89f` |
| [real-scale-folds.tsv](real-scale-folds.tsv) | `7aaf34ae9b9a484778fec28f15622ceb9ee26c92d33f1ca7e82a3ee3882abd39` |
| [real-scale-catalog.tsv](real-scale-catalog.tsv) | `b353f5cd211d2f7851f12e8b70bf28890d5dcfb4c63331f363c03e109b8bfbc7` |
| [real-scale-results.sha256](real-scale-results.sha256) | `b9a434153785180d7911899754b5744807ed9bb0d5d61b4b504a611737f5ec9e` |
| [real-scale-model.json](real-scale-model.json) | `87b21a956c0efcd59a550bc19535b02f65817cb734c3a7c834ab5fef518a3800` |
| [real-scale-cohorts.json](real-scale-cohorts.json) | `197205d7457c6ebe805e770940693f927f0d13d43278ea3dd704867199147ea0` |
| [real-scale-cohorts-original.json](real-scale-cohorts-original.json) | `f0ec8687ce9d6321cea44ec6c04eac2dd7ecb5d2676d13f1392708419ed50df0` |
| [real-scale-identity-aliases.json](real-scale-identity-aliases.json) | `44186169f93b64a889e262921e13512aebe7a737ba96267fa5e8a34c7ee60fdc` |
| [real-scale-inputs-pre-dedup-review.properties](real-scale-inputs-pre-dedup-review.properties) | `378c9a71d01f9cbdf0f8a593e22a9f4475b7d017491d66d846ccde606899bf5e` |

## Межа наступної роботи

Цей результат зберігається без змін. Наступна робота потребує окремого обґрунтованого рішення після read-only діагностики шару помилки. Зміни міток, weights, моделі чи порогів заради цього результату не є частиною v2.
