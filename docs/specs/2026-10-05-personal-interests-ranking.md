# Окремі інтереси в персональних рекомендаціях

- Дата: 2026-10-05
- Статус: поведінку погоджено; реалізація й нова перевірка якості попереду.
- Тікет: #487

## Навіщо ця зміна

Повний recording-text-v4 та стандартний Gradle-повтор дали **NO-GO**: recall@20 і NDCG@20 обох моделей — 0 на всіх 24 fold. Вимога кращої якості E5 ще не виконана. [Архів v4](../recommend/experiments/2026-10-04-recording-text-v4/README.md) зберігає фактичні входи й результати; цей документ не перетворює їх на GO. [CI на HEAD `03d11044`](https://github.com/Samuel-Ku/slukhayka/actions/runs/37283908998/job/111678189327) теж відтворив NO-GO 0/0 з Java exit1; докази завантажено. Це невиконаний quality gate, а не зелений CI.

Користувач прямо погодив план окремих інтересів відповіддю «добре зроби це» після рекомендації реалізувати його. Це окремий дозвіл на описану нижче поведінку. Попереднє «будь-які» стосувалося книжок для бібліографічної вибірки. Воно не було дозволом змінити ранжування.

Замість одного позитивного centroid зберігаю окремі позитивні інтереси та негативне свідчення. Книга може відповідати одному з інтересів, навіть якщо він далеко від інших. Негативний сигнал зменшує оцінку; слабший позитивний сигнал не отримує силу найсильнішого лише через нормування свого вектора.

Це обмежена частина [вересневого дизайну, §6](2026-09-05-personal-recommendations-design.md#6-локальний-підбір-і-пояснення): maximum-of-interests і positive cap 20. Тут не приймаю його ширшу нову формулу, перерозподіл відсутніх weights, 180-day freshness, нові exploration rules, graph, onboarding чи зміну precedence рейтингів. Числа не підбиратимуться за нинішніми 24 fold.

## Межа зміни й публічні інтерфейси

Ранжування живе в спільному pure production `RecommendationPersonalization.rank` у [RecommendationPersonalization.kt](../../app/src/main/java/com/slukhayka/audiobooks/data/recommend/RecommendationPersonalization.kt). App wrapper — `RecommendationEngine.recommendWithVectors` у [RecommendationEngine.kt](../../app/src/main/java/com/slukhayka/audiobooks/data/recommend/RecommendationEngine.kt). Full-catalog evaluator викликає цей самий wrapper і production ranker для E5 та keyword baseline. Окремого evaluator-only ранжування немає.

Це погоджені seams для public behavioral TDD: direct `rank`, vector wrapper і full-catalog evaluator. Тести спостерігають список, score, semanticScore та reasonTitle, а не приватні helpers. Один нейтральний RED → мінімальний GREEN за раз. Існуючі гарантії й тести зберігаються.

Вхід — чинний coherent snapshot vectors одного captured backend/context. Розмірність і скінченні значення перевіряються на цій межі. Кандидати й сигнали без вектора пропускаються. Нового inline embedding, I/O чи довільного ремонту векторів немає. Один canonical signal на Work подає чинний upstream builder; collection, deduplication та rating precedence не змінюються.

Work лишається авторським твором, незалежним від Edition, narrator чи Source. Library, listening state, collectors, candidate projection, transport, cache generation gates, картки, Play/coordinator routes і source badges не змінюються.

## Позитивні й негативні supports

Support придатний, якщо має вектор і скінченний ненульовий signed weight. Сигнали з нульовим, NaN або нескінченним weight не створюють support.

1. До cap розділити всі придатні supports на `Pall` з weight > 0 та `N` з weight < 0.
2. Обчислити `M = max(abs(weight))` серед **усіх** придатних позитивних і негативних supports, до positive cap. Якщо `Pall` порожня, personal ranker повертає порожній список. Загальна добірка лишається чинною поведінкою caller.
3. Залишити `P`: перші 20 позитивних supports за abs(weight) descending, потім stable Work ID ascending. ID порівнюються чинним Kotlin String order. Негативні `N` **не обмежуються cap**.
4. Усі позитивні semantic, metadata й reason calculations використовують тільки retained `P`. Негативні calculations використовують увесь `N`.
5. Для кожного retained support `q = abs(weight) / M`. Знак уже визначає групу; `q` не від'ємний. Для кандидата `c = cosine(candidate, support).coerceIn(0, 1)`.
6. `simPlus = max(P, q * c)`. `simMinus = max(N, q * c)`; порожнє `N` дає 0. `semantic = simPlus − 0.70 * simMinus`.

Спільний denominator включає і дуже сильний negative support, і позитивний support, який згодом відкине cap. Negative maximum не множиться вдруге на signed weight. Coefficient 0.70 незмінний. Загальний semantic чи score не обрізається до 0..1.

Кандидат потребує `simPlus > 0`, або чинної source-label eligibility. Reason anchor — retained positive support із найбільшим `q * c`, tie — stable Work ID ascending. Його title стає чинним reasonTitle. Більший raw cosine слабкого сигналу не змінює пояснення, якщо weighted contribution менший. Source recommendation slots зберігають чинну badge/display policy.

## Metadata affinity та score

Metadata лишається **binary-any**, без множення на `q`, sum чи fraction. Для кожного component:

`affinity = anyMatch(candidate, P) − anyMatch(candidate, N)`

Діапазон −1..1. Спільний facet у позитивній і негативній групі взаємно скасовується. Positive supports поза cap не дають bonus; усі придатні negatives дають penalty. Це м'який внесок, окремий від explicit hidden-author action.

Author і series порівнюються за непорожніми ключами саме `RecommendationPersonalization.identityKey`. Немає fuzzy person inference. Genre використовує фактичний [GenreIdentity.fromSourceText](../../app/src/main/java/com/slukhayka/audiobooks/data/facets/GenreIdentity.kt): facet IDs мають непорожній intersection. Blank і non-genre не дають affinity. `Science Fiction` та `sci-fi` збігаються за чинним canonical ID. `Space Tales` не стає science-fiction, але його чинний stable raw facet може збігатися із самим собою.

Score лишається weighted sum semantic, author, genre, series, freshness та source popularity. Configured `ScoreWeights` і user settings зберігаються. Defaults:

| Component | Weight |
| --- | ---: |
| semantic | .55 |
| author | .15 |
| genre | .10 |
| series | .05 |
| freshness | .05 |
| popularity | .10 |

Freshness, єдиний normalized source rank/rating component і його чинні bounds незмінні. Немає нових cutoffs, rescaling чи weight redistribution.

Зберігаються hard exclusions, known/hidden Work filters, hidden-author filtering, descending score/stable ID sort, greedy diversity caps: максимум дві книги непорожнього author key та одна непорожнього series key. Blank author/series не утворюють спільної групи. Будь-які supplied nonblank keys підлягають чинному identityKey/cap; нового inferred unknown-value ban немає. Source-first exploration, fallback і wrapper exploration policy не змінюються.

## Нейтральні приклади до реалізації

Це вигадані IDs і metadata з injected unit vectors. Вони не є модельними результатами, listening history чи acceptance labels. У кожному direct `rank` виклику явно: `explorationCount=0`, default weights, popularity=0, publishedAt=null, sourceLabels empty. Незазначені metadata порожні. Кожний `P` має weight +1, кожний `N` −1, якщо не вказано інше. Supports не входять до candidate pool. Виключення тільки явно зазначені. Повні candidate IDs визначають deterministic ties.

| № | Вхід | Очікувана публічна поведінка |
| --- | --- | --- |
| 1 | P1=(1,0), P2=(0,1). Candidates `01-A`=(1,0), `01-B`=(0,1), `01-Bridge`=(1,1)/√2. Усі metadata empty; topN=3. | IDs `[01-A, 01-B, 01-Bridge]`. A/B tie за ID. reasonTitle A=P1, B=P2. |
| 2 | P=(1,0), N=(.8,.6). `02-X`=(.8,.6), `02-Y`=(.8,−.6); topN=2. | IDs `[02-Y, 02-X]`: однаковий позитивний overlap, у X більший negative maximum. |
| 3 | P=(1,0), author=`Positive Author`, genre=`Science Fiction`, series=`Positive Series`. Усі п'ять candidates мають vector=P. `03-author` збігається лише за author; `03-genre` має лише genre=`sci-fi`; `03-series` збігається лише за series; `03-none` має empty metadata; `03-space` має лише genre=`Space Tales`. topN=5. | IDs `[03-author, 03-genre, 03-series, 03-none, 03-space]`. Default .15>.10>.05 визначають lifts. None=Space за score, tie за ID. Space не отримує science-fiction lift. |
| 4 | P=(1,0), empty metadata. N=(0,1), author=`Negative Author`, genre=`poetry`, series=`Negative Series`. X/Y обидва=(1,0); `04-X` має всі N facets, `04-Y` — empty metadata. topN=2. | IDs `[04-Y, 04-X]` за однакового semantic. Negative metadata віднімає всі три affinities. P не створює вигаданого positive facet. Explicit hidden-author exclusion лишається окремим hard filter. |
| 5 | P=(1,0), empty metadata. Кожен vector=(x,√(1−x²)): A1=.95, C=.90, A2=.85, A3=.80, B=.75. IDs `05-A1`, `05-C`, `05-A2`, `05-A3`, `05-B`. A1/A2/A3 author=`Author A`, series S1/S2/S3; C author=`Author C`, series S1; B author=`Author B`, series S4. `05-H`=.99 author=`Hidden Author` у excludedAuthors. `05-K`=.98 у excludedWorkIds. Усі genres empty; topN=3. | Після hard exclusions порядок A1>C>A2>A3>B. Greedy result IDs `[05-A1, 05-A2, 05-B]`: C відкидає series cap, A3 — author cap. Указані nonblank keys різні саме за цим fixture. |
| 6 | P=(1,0). `06-V1`/`06-V2` мають vector=P і empty metadata; `06-M` без вектора; topN=3. Другий виклик має тільки N=(0,1), weight −1, той самий pool. | Перший result IDs `[06-V1, 06-V2]` за ID, M пропущений. Другий result empty: немає придатного positive support і вигаданого personal reason. |

### Додаткові межі

- **Weighted reason.** Candidate=(1,0). Pweak=(1,0), weight +1; Pstrong=(.6,.8), weight +2; neutral metadata. M=2. Reason=Pstrong, хоча raw cosine Pweak більший. Окремий tie виклик: тільки `P01`=(.6,.8) і `P02`=(.6,−.8), обидва +2, той самий candidate й empty metadata. Reason=P01 за stable ID.
- **Спільний denominator.** P=(1,0), +1; N=(0,1), −2; candidate=P. M=2, simPlus=.5, simMinus=0. Це перевіряє negative membership у normalization без зміни .70.
- **Positive cap tie.** 21 support `P01`…`P21`, усі +1. P01…P20=(1,0), empty metadata; P21=(0,1), genre=`poetry`. Retained IDs рівно P01…P20. Candidate A=(1,0), genre=`poetry`, eligible, але не має genre bonus від P21. Candidate B=(0,1), empty metadata, ineligible без source label, хоча збігається з відкинутим P21.
- **Uncapped negatives.** P=(1,0), +1, empty metadata. 21 support N01…N21, усі −1. N01…N20=(0,1), empty metadata; N21=(1,0), genre=`poetry`. Candidate=(1,0), genre=`poetry`: N21 дає simMinus=1 і negative genre affinity; semantic=.30. Cap не стирає 21-й negative.
- **Unknown genre self-match.** P=(1,0), +1, genre=`Space Tales`. Candidates із genres `space tales` і `sci-fi`, обидва vector=P, інші metadata empty. Перший має genre affinity через той самий чинний raw facet ID, другий — ні. Це не нове genre inference.

Ці очікування фіксують поведінку продукту. Вони не є виконаним scoring experiment і не доводять наступний GO.

## App, evaluator і наступний freeze

App передає чинні coherent vectors до спільного ranker. Невідоме або відсутнє свідчення не запускає inline embedding. Нейтральні negative fixtures перевіряють продукт окремо; completion-only LOO не доводить якість негативних сигналів.

Обидві моделі evaluator використовують цей самий новий production ranker, повний каталог, ті самі Work texts, 24 proxy positives і 112 aliases. Held-out/training exclusions, показані top-20, diversity та exploration лишаються чинними. Чинний evaluator подає completion supports з weight +.9; за однакової сили вони нормуються до q=1. Weight лишається незмінним; нової behavior-strength recipe немає. Evaluator-only shortcut чи metric-selected labels заборонені.

Порядок: погоджена specification і нейтральні fixtures → public-seam vertical TDD → targeted перевірки → незалежні source reviews → нові actual source/protocol/facet fingerprints та preregistration ledger → перевірка frozen входів → acceptance scoring → стандартний повтор гейту. Нові оцінки не запускаються до source review та freeze.

Усі 44 файли архівів v1–v4 зберігаються незмінними. Model assets, raw source feed, 24 мітки, 112 aliases, representatives, Work texts, preprocessing/cache policies, configured weights, K=20 і пороги незмінні. Ranking-only зміна не перейменовує embedding context. Journals можна повторно використати тільки за чинними перевірками actual model/context/ID/text; новий protocol freeze усе одно потрібен.

Немає sweeps coefficient, positive cap, .70, weights, labels, representatives, K або threshold на цих cohorts. Cap 20 взятий із попереднього дизайну, не з current fold scores.

Строгий гейт з [ADR-0061](../adr/0061-real-catalog-recommendation-eval.md) лишається: recall@20 E5 **строго більший** за baseline і NDCG@20 **не нижчий**. NO-GO повертає exit1 та лишає quality AC відкритим. Лише фактичний повний прогін і стандартний повтор можуть підтвердити GO. Дозволена expert proxy-вибірка не є особистою історією й не доводить живу користь; нової personal-history вимоги тут немає.
