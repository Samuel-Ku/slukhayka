# Офлайн-перевірка рекомендацій

Для #487 використовую реальні картки LibriVox із Archive.org: 21 821 raw запис, 18 188 авторських Works і 17 757 різних нормалізованих назв. У кожному fold змагається весь доступний каталог. 140-книжковий навчальний приклад не є доказом цього гейту.

Поточний personal-interests-v5 — **NO-GO**: E5 і keyword baseline дали recall@20 = 0 та NDCG@20 = 0 на всіх 24 folds. Стандартний Gradle-повтор повернув exit1 та відтворив усі п'ять результатів побайтно. Вимога кращої якості E5 ще не виконана. [Незмінний архів v5](experiments/2026-10-05-personal-interests-v5/README.md) зберігає входи, ранги й докази; [поточний звіт](EVAL-REPORT.md) містить фактичні метрики.

За [погодженою specification](../specs/2026-10-05-personal-interests-ranking.md) app та обидві моделі evaluator використовують один production ranker з окремими зваженими інтересами, positive cap 20, усіма negatives і binary genre/author/series affinity. Model, Work texts, labels, configured числа, diversity, exploration та strict gate незмінні. Source commit — `114a22a4b53361bc175f1241a27b6ae1f83c4966`; Standards і Spec — без findings. Фінальний public host — 46/46, цільовий native Gradle — 61/61 у шести класах. Нейтральні тести не визначають GO.

[Поточний ledger](real-scale-inputs.properties), SHA-256 `28ca087d7867a3090b5686adb8f29b71d1dc79bfd8ea0819fca224a5bbc44bb1`, зафіксовано 2026-10-05 о 10:28:54.748957 UTC до fold scores. Після незалежного actual freeze review commit `ab5cffec7a35f44efb0ee0769f523dd692fb4ece` став HEAD обох повних запусків. Кожний ID/text і всі 36 376 поточних вектори перевірені. Ranking-only зміна зберігає embedding context: по 18 188 cache hits обох моделей, нових векторів — 0. Усі 24 top-20, training exclusions і 11 result SHAs перераховані незалежно.

[Recording-text-v4](experiments/2026-10-04-recording-text-v4/README.md) також дав NO-GO 0/0 після cold та standard run. Його source `4e1347a4`, ledger SHA `3e0dcb9e05280c93635ba2ddaad5a5beed737411b33185f1dc69d15c37ece64e` і freeze/run HEAD `61bc5614` збережені в архіві. Тоді свіжо обчислено по 18 188 векторів; саме ці повністю перевірені journals повторно використовує v5. V4 прибирає лише точно впізнані службові шаблони запису, зберігаючи цитати й неоднозначний текст. Змінено тексти 12 914 Works; решту catalog TSV збережено. [Правила й межі](../adr/0061-real-catalog-recommendation-eval.md#експеримент-recording-text-v4-службовий-текст-запису) описують очищення. Усі 44 файли v1–v4 та вісім journals незмінні.

У v2 виправлено BOS/EOS і budget 512 за pinned tokenizer.json, симетричний `query: ` за [офіційною E5 model card](https://huggingface.co/intfloat/multilingual-e5-small/raw/main/README.md), backend/cache context та coherent publication після install. Source commit — `00fec767`; [його ledger](experiments/2026-10-04-e5-model-input-v2/real-scale-inputs.properties) SHA-256 `f5deda78a612fbbe5e9f3363b74a4d3f67d329b1246854e565bccad8aa3b9634` та [звіт](experiments/2026-10-04-e5-model-input-v2/EVAL-REPORT.md) лишаються незмінними. Це виправлення контракту не дало переваги на вибірці.

Tokenizer v3 (`f7a1a8de`) виконує unknown scalar edge та оголошений Precompiled charsmap із зафіксованими Unicode 16 tables. [Джерела, fixtures і ліцензії](tokenizer-provenance.md) описують контракт. Обидва source reviews пройдено до нового прогону; template-only `9ed54c4a` теж перевірено. Входи зафіксовано 2026-10-04 о 19:11:45.072137 UTC до нового інференсу й незалежно звірено: [ledger v3](experiments/2026-10-04-hf-tokenizer-v3/real-scale-inputs.properties), SHA-256 `4d33b8393bf533fae886db9150317bab95c2911c35b4f93fa4103409e465ebec`, freeze/run HEAD `009cb87b`. Native — 92/92, tokenizer parity — 98 точних raw/model порівнянь і всі 1 093 Unicode випадки. Actual cold 53010 та standard replay 35391 дали [архівований NO-GO v3](experiments/2026-10-04-hf-tokenizer-v3/EVAL-REPORT.md). У цьому експерименті модель, мітки, candidate texts, ranking weights і пороги були незмінні.

Мітки — п'ять експертних тематичних груп, 24 справжні твори. Це не особисті завершення слухачів. Початкові 25 міток збережені окремо. The Secret Adversary не знайдено в заморожених картках; до інференсу зафіксовано виключення без заміни. Дані, мітки, тексти, модель і код протоколу прив'язані контрольними сумами до [реєстру входів](real-scale-inputs.properties).

Перший прогін обчислив частину векторів і був зупинений до будь-якого fold чи метрики. Незалежна перевірка знайшла повторні записи одного твору, які могли потрапити і в історію, і в кандидати. [Його входи](real-scale-inputs-pre-dedup-review.properties) збережені без змін. До повторного інференсу виправлено тотожність усіх 24 цільових творів: [окремий реєстр](real-scale-identity-aliases.json) зводить 112 записів, зокрема 22 переклади. Кожна картка звіряється за точними назвою, автором та SHA-256 опису. Вибір релевантних творів і пороги лишилися незмінними. Часткові вектори старого контексту не використовуються для нового прогону.

Продовження, авторські перекази, ранні самостійні тексти, п'єси та збірки лишаються окремими Works. Роман Peter and Wendy і його видання Peter Pan звірені за [17 розділами тексту 1911 року](https://www.gutenberg.org/cache/epub/16/pg16-images.html) та списками розділів відповідних карток Archive.org. The Story of Peter Pan — окремий переказ Daniel O’Connor, як прямо вказує заморожений опис. Німецький Das Geschlecht der Zukunft звірений з [оригінальною назвою The Coming Race](https://projekt-gutenberg.org/authors/edward-bulwer-lytton/books/das-geschlecht-der-zukunft-2/). Це перевірка відомих тотожностей у замороженому корпусі, а не повна бібліографія всіх перекладів чи доказ відсутності перетину змісту зі збірками.

## Запуск

Потрібні JDK 21, Android SDK для наявного Gradle-проєкту й зафіксовані модель та токенізатор у `app/src/main/assets/models/e5`. Модель не комітиться. Її ревізія та суми лежать у [real-scale-model.json](real-scale-model.json). Runner відкидає відсутні або змінені assets; не можна отримати семантичний результат із keyword fallback.

```sh
./gradlew downloadE5Model --no-configuration-cache
./gradlew runRecommendationEval --no-configuration-cache
```

Перший запуск обчислює всі вектори. Журнал зберігається в `.gradle/recommendation-eval-cache`. Перерваний прогін продовжує незавершену роботу. Перевірені вектори не обчислюються повторно. Модель, production текст і контрольна сума вектора мають збігатися. Пошкоджений завершений запис спричиняє помилку, а не мовчазне відновлення з іншої моделі.

GO потребує строго більшого recall@20 та NDCG@20 не нижче keyword baseline. NO-GO записує [звіт](EVAL-REPORT.md), ранги, списки top-K та їхні суми, потім повертає ненульовий exit code. Змінна FAIL_ON_NO_GO більше не може вимкнути гейт. Пороги й позитивні твори після результату не змінюються.

Зміна входів або коду протоколу відносно реєстру зупиняє прогін. Для нового експерименту спочатку зафіксуй окремий реєстр, мітки й папку звіту, до будь-яких оцінок. Runner приймає явні `--snapshot`, `--cohorts`, `--identities`, `--cache`, `--report`. `--prepare` перевіряє джерела, мітки й реєстр тотожності та фіксує входи без інференсу й метрик. Перший позиційний аргумент — папка assets. У JavaExec ці аргументи передаються через `--args`; старий аргумент «число негативних кандидатів» більше не підтримується.

## Знімки джерела

Заморожені [44 сторінки](snapshots/librivox-2026-10-04/) дозволяють повторити оцінювання без звернення до джерела. Loader перевіряє SHA-256, URL запиту, порядок ID, загальне число й межі сторінок. Ідентифікатори джерела та URL кожного запису зберігаються у підсумковій таблиці Works.

Новий знімок збирай в окрему папку:

```sh
./gradlew runRecommendationEval --args="--acquire /path/to/new-snapshot" --no-configuration-cache
```

Збирач має межу 44 сторінки по 500 карток. Він використовує production SourceRequestGate і бюджет BACKGROUND; повторно не завантажує завершені сторінки. Після першої відсортованої десятитисячної ділянки переходить на строгий курсор identifier. Зміна числа карток або повтор межового ID припиняє збір. Живий повторний збір не замінює ці закомічені сторінки й мітки автоматично.

Правила зіставлення, leave-one-out і межі доказу записані в [ADR-0061](../adr/0061-real-catalog-recommendation-eval.md). GO на бібліографічній вибірці не доводить користь для живих слухачів. Користувач дозволив довільну бібліографічну вибірку для цього гейту; особистий журнал завершень не є додатковою вимогою. Для висновку про живу користь потрібна окрема перевірка.
