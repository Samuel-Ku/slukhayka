# Незмінний експеримент personal-interests-v5

Результат — **NO-GO**. Справжній ONNX E5 і keyword baseline дали recall@20 = 0 та NDCG@20 = 0 на всіх 24 leave-one-out folds. Перший повний запуск і стандартний Gradle-повтор повернули exit1. Вимога кращої якості E5 у #487 лишається невиконаною. Модель, мітки, числа та пороги після результату не змінювались.

За [погодженим контрактом](../../../specs/2026-10-05-personal-interests-ranking.md) замість centroid використовую окремі зважені позитивні інтереси: максимум внеску серед 20 найсильніших supports. Негативні не обмежуються cap. Спільний denominator містить усі придатні signed weights до cap. Semantic — positive maximum мінус .70 negative maximum. Той самий weighted winner дає reasonTitle. Author, series і чинні GenreIdentity facets дають binary-any positive мінус negative affinity. App та обидві моделі повного evaluator викликають один production ranker.

Configured weights, freshness, exclusions, diversity та exploration незмінні. Evaluator completion weight лишається .9. Повніший вересневий redesign не реалізований цим експериментом. Нейтральні public tests перевіряють контракт продукту; вони не є доказом GO.

## Фіксація до оцінок

Актуальний main інтегровано в `a129feeb74a88ad0c9f34cbc3145617cc4cce776`. Specification зафіксовано до TDD у `d2952d81e256c06eeb02e4135b80c84f27ef9fd9`, SHA-256 `145f6e1485b18ae0dd921662d38d878eb00b613585687ce064523d3ac931be2b`. Її початковий prospective status збережено: це незмінний контракт, а фактичний результат записаний тут.

Source commit — `114a22a4b53361bc175f1241a27b6ae1f83c4966`. Standards та Spec завершили source review без findings. [Ledger](real-scale-inputs.properties) зафіксовано 2026-10-05 о 10:28:54.748957 UTC без model inference чи fold scores. SHA-256 — `28ca087d7867a3090b5686adb8f29b71d1dc79bfd8ea0819fca224a5bbc44bb1`. Protocol source — `fe3f2df4ac4f2c1b5cb10c5245598160cf7a58512f165730e5ce62fbebd2f3d8`; actual facet source — `94a74d0914ccd5bf299c97862cca82322a6d06d83a1130beda1ddcb6b18d5ff6`. Після незалежної перевірки actual freeze ledger-only commit `ab5cffec7a35f44efb0ee0769f523dd692fb4ece` став HEAD обох повних запусків.

Залишилися 21 821 raw картка, 18 188 Works, 17 757 різних назв, п'ять груп, 24 мітки, 112 aliases та 10 distinct-related guards. У folds змагаються 18 184 або 18 185 кандидатів із виключенням навчальних книг. Це дозволена експертна бібліографічна proxy-вибірка, не особистий журнал завершень і не доказ живої користі.

Work texts recording-text-v4, модель, tokenizer/backend v3, source feed, labels і representatives незмінні. Candidate text SHA — `88a6022a82d0fb4b636dba710ef065853d1e185230b3a785f6ce9ce804a2ce40`. Public catalog audit підтвердив кожний ID/text та byte-exact TSV проти v4. Ranking-only зміна не перейменовує embedding context. Незалежно перевірено усі 36 376 поточних vector entries: ID, dimension, norm і checksum. Усі вісім journal SHA збережені. Новий protocol freeze усе одно виконаний до scoring.

## Фактична перевірка

Вертикальний public-seam TDD зберігає 30 фактичних фаз: дев'ять RED нових поведінкових випадків та один RED старого centroid expectation, 20 GREEN. Усі 30 компіляцій успішні. Фінальний fresh public host — 46/46; після Standards cleanup ті самі assertions збережені. Native Gradle session 20262 — exit0, 61/61 свіжий тест у шести класах, без failures, errors чи skips: 32+9+5+5+7+3. Повного локального Android-набору не запускали.

Compile-only session 53793 свіжо зібрав host runner. Prepare-only 94968 та public catalog audit 24525 завершились із exit0. Root і незалежний Spec перевірили actual source/model/context fingerprints, chronology, усі тексти, 44 архівні файли й журнали до нових метрик.

Перший повний v5 session 11247 використав по 18 188 перевірених cache hits обох моделей; нових векторів — 0. Actual model assets і semantic backend перевірені; keyword fallback у семантичній колонці заборонений. Після всіх 24 folds записано NO-GO 0/0 та exit1.

Стандартний `./gradlew :app:runRecommendationEval --no-daemon --no-configuration-cache --max-workers=2 --console=plain`, session 24972, повернув exit1 за 2m38s. Він повторно перевірив усі вектори без нового інференсу. Report, ledger, folds, catalog та result hashes побайтно тотожні першому v5 run. FAILED тут означає фактичний NO-GO; це не успішний quality gate.

Незалежний перерахунок підтвердив усі 24 folds, training exclusions, top-20 ranks, candidate counts, 112 aliases, 10 guards та всі 11 result SHAs. Пороги не послаблено. Sweeps labels, coefficient, cap, weights, K чи threshold не виконувались.

| Raw proof | SHA-256 |
| --- | --- |
| Вертикальний TDD, 30 фаз | `eea1e5203117569f8f3be74f6ffec1d525017cc041990648d5a2a0240510565b` |
| Root перевірка TDD logs/source | `9f88920ec52d8e666bb5db9dbad2379dc0605e2f060da8ceec59bfdd0b705e37` |
| Фінальний public host, 46/46 | `2721f3a79e817acb953033d52ff17172a6303263e8fcbf4b0c45fafa9504282c` |
| Native Gradle log | `cea9a4134c3c7cc2dc34fd9a2d72cd3a5f8fb3ed9472f7b3460cbdd3bdd3f444` |
| Свіжий native XML manifest, 61/61 | `b336d22af4d1346f7f56fd80308091b333e668e44c72c20ec349005d0186e785` |
| Source Standards review | `ee99981af98568a2628aa6daf08ea1c4043500f1450276ef20a4c5a53fac2238` |
| Source Spec review | `46eefaf1bc2836253645bc6fa108a9d74c370139f43fcc68b65747b6368a05c3` |
| Незалежний actual freeze review | `5233ca654f346b03ae96b764f40ce68c68a27ef6358351ee50ad7dce017c1a5d` |
| Незалежний actual freeze proof | `3cd85bfd5ec333bb78230df846ab15d2d140a76324e71a2271c59da72bd956dc` |
| Перший повний v5 log | `762a49493ab884b643274d72b5d19d787fad3975ac1297bd7d30bf3a804a1c79` |
| Стандартний Gradle log | `ce063e7f743607af70e4ffd70bc93aaffe2f8b584ecec240ce60f43d391a4fd2` |
| Незалежний result checker | `13a3ca9c2f6019b427cae883737eb12dcc47bc3e9d0cc6506f1e684fd0164a80` |
| Незалежний fold/result proof | `32d1f881dbb400f8c4cea1c2dfb6891a5beba5ae32f4224bdd38873ef89e1d6a` |
| Поточні vector entries proof | `a5b0336352123c1ffec46c9ef3c9403466319e4c690a5d3ff539efb80f46c733` |

## Архів і межа результату

Десять data/report файлів — побайтні копії actual current v5 після першого повного run і standard replay. [Суми результатів](real-scale-results.sha256) включають локальні журнали. Raw logs, native XML, model assets та binary journals збережені поза Git; README не замінює ці докази.

Усі 44 файли [v1](../2026-10-04-production-tokenizer-v1/README.md), [v2](../2026-10-04-e5-model-input-v2/README.md), [v3](../2026-10-04-hf-tokenizer-v3/README.md) та [v4](../2026-10-04-recording-text-v4/README.md) залишилися незмінними. Погоджений product contract реалізований, але він не дав переваги E5 на зафіксованій вибірці. Quality AC #487 залишається відкритим; нового експерименту цей результат не санкціонує.
