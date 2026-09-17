# spec-19 T3 / #487 — Recommendation eval gate

> **Стан 2026-09-16 (#487).** Історичний GO нижче **не чинний**: він
> порахований на 140-книжковому каталозі, а `semanticRecallAtK` ділив
> влучання лише на число fold (значення понад 1 — середнє число влучань,
> помилково назване Recall). Виправлено в #487:
> - recall@K тепер = влучання / розмір релевантної множини, усереднене по
>   fold, тобто завжди в [0, 1] (`RecommendationEval` + тест
>   «recall is a fraction of the relevant set»);
> - **механічний запобіжник**: Gradle-задача `verifyE5ModelAssets` падає,
>   якщо релізна збірка не має `model.onnx`/`tokenizer.json`, і приєднана до
>   `preReleaseBuild` — реліз більше не може мовчки зібратися на
>   keyword-басейні;
> - офлайн-гейт (чисті JVM-тести `RecommendationEvalTest`,
>   `RecommendationModelPolicyTest`) зелений.
>
> **Що лишається:** наскрізний прогін на каталозі реального масштабу
> (10k+ Works, реальні снапшоти фідів) із замороженою E5-моделлю. Асети
> моделі не комітяться (100 МБ), тому прогін робиться командою
> `./gradlew downloadE5Model && ./gradlew runRecommendationEval` у середовищі
> з мережею; вирішальний вердикт «адекватно/не адекватно» слухач винесе з
> живого тижня користування (поза тікетом).

**Date:** 2026-08-14
**Decision:** GO
**Model:** multilingual-e5-small (384-dim, int8 ONNX, mean-pooled, L2-normalized)
**Method:** seeded (42) leave-one-out over the listener's completed shelf;
each fold ranks a pool of the other completions + 40 distractors
from a 140-book catalogue; recall@20 and NDCG@20.

| Embedder | recall@20 | ndcg@20 |
|---|---|---|
| semantic (ONNX e5-small) | 4,1429 | 0,5402 |
| baseline (genre+author) | 1,1429 | 0,1782 |

GO: the semantic ranking beats the
genre+author baseline. The UI ticket (#120) may start.

**Reproduce:** `./gradlew runRecommendationEval` (the ONNX model is fetched by
`downloadE5Model` — it is not committed; see app/build.gradle.kts).
