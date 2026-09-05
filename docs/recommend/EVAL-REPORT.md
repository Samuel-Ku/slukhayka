# spec-19 T3 — Recommendation eval gate

> Перевірка 2026-09-05: наведений нижче історичний GO не є чинним
> доказом для нового дизайну. Поле `semanticRecallAtK` у
> `RecommendationEval.evaluate` ділить влучання лише на число fold;
> значення понад 1 є середнім числом влучань, помилково названим Recall.
> Новий протокол і пакет виправлення R1 наведені в
> [дизайні рекомендацій](../specs/2026-09-05-personal-recommendations-design.md).
> Історичні числа збережено; повторного запуску оцінювання в цій сесії не було.

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
