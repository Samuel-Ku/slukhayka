# Data Enrichment Module

<!-- Generated: 2026-08-16 | Files scanned: 26 | Kotlin lines: ~2,990 -->

## Purpose

The three "smart" feature areas that enrich the catalogue: **series universes**
(which universe a series belongs to, where in it it sits — Wikidata + Firestore
shared store + ML Kit), **on-device recommendations** (semantic embeddings,
pure JVM, nothing leaves the device), and the **home-screen widget** (Glance).
All three read the same Room cache and are best-effort by contract.

## Key Files

### Series Universes (`data/universe/`, spec-25/26 — 15 files)

```
SeriesUniverses.kt             421 lines  (the module: resolve + cache, client-first)
WikidataSeriesProvider.kt      405 lines  (Wikidata SPARQL/Search provider, 429-aware)
WikidataParser.kt              114 lines  (Wikidata JSON → series/members)
WikidataRetryPolicy.kt          40 lines  (exponential backoff on 429)
SeriesUniverses → cache: series / series_members / universes tables

SharedUniverseStore.kt         137 lines  (Firestore read layer, spec-26 T5)
FirestoreUniverseStore.kt       74 lines  (null without google-services.json — app behaves as before)
CuratedSeed.kt                  63 lines  (seed curated assets into shared base, spec-26 T6)

UniverseAssets.kt / UniverseJson.kt / UniverseList.kt / UniverseMatcher.kt
    — curated universe asset loading + alias-aware matching
MlKitTitleTranslator.kt / TitleTranslator.kt
    — on-device uk → ru/en title translation (spec-26 T1, ML Kit, keyless)
UniverseRefreshPass.kt / UniverseRefreshTier.kt
    — background refresh on a tiered schedule (hot ~7d / warm ~30d / cold ~180d, spec-26 T7)
```

### On-device Recommendations (`data/recommend/`, spec-19 — 7 files)

```
RecommendationEngine.kt     121 lines  (rank candidates by signal similarity, reason chips)
TextEmbedder.kt              57 lines  (keyword baseline seam today)
OnnxEmbedder.kt             177 lines  (intfloat/multilingual-e5-small behind the same seam)
UnigramTokenizer.kt         189 lines  (Ukrainian-aware tokenizer)
CatalogEmbeddingService.kt   55 lines  (embed + cache catalogue)
EmbeddingCache.kt            69 lines
RecommendationEval.kt       132 lines  (offline eval harness)
```

### Home-screen Widget (`widget/` — 4 files)

```
AudiobookGlanceWidget.kt         315 lines  (Glance UI: cover, progress, transport)
AudiobookGlanceWidgetReceiver.kt  28 lines
GlanceWidgetState.kt             111 lines  (widget state model)
WidgetActionCallbacks.kt          81 lines  (play/pause/next intents)
```

## Series Universe Architecture

```
Resolution (triggered on book-page/series-page open, background, best-effort):
  SeriesUniverses.resolve(seriesTitle, url)
    ├─ 1. curated UniverseList assets (local, offline, alias-aware)
    ├─ 2. SharedUniverseStore (Firestore — a resolution another user wrote
    │      back is read BEFORE Wikidata; never pays for a Wikidata call)
    ├─ 3. WikidataSeriesProvider fallback (status-aware transport feeds
    │      the 429 retry policy; ML Kit translates ru/en-only labels)
    └─ persist: idempotent REPLACE upserts into series/series_members/universes
               (resolvedAt stamp; spec-26 T7 refreshes stale tiers)

A book/series no layer knows, or a failing layer, contributes NOTHING
(no row, no surfaced error).
```

## Recommendation Architecture

```
Signals (favourite > completed > recently listened, weighted) →
  TextEmbedder (keyword baseline today; ONNX e5-small later, same seam) →
  RecommendationEngine ranks catalogue candidates by cosine similarity,
  top-N excluding known books, each pick carries a reason chip «схоже на Х».
Pure JVM: embeddings computed on-device, nothing leaves the device (Q2/Q8).
```

## Dependencies

- **Inbound:** `App` (constructs `seriesUniverses`, `universeRefreshPass`,
  seeds curated data), `ui/screens/BookDetailScreen` + `SeriesScreen` (universe
  header), `HomeScreen` (recommendations block), launcher (widget)
- **Outbound:** `data.db.*` (DAO + universe tables), `data.source.HttpFetcher`,
  Firebase Firestore (optional), ML Kit (optional), Glance/Compose (widget)

## Common Tasks

| Task | Touch |
|---|---|
| Change universe matching rules | `UniverseMatcher.kt` + curated assets |
| Add a curated universe | New entry in the universe asset JSON (`data/universe/UniverseAssets`) |
| Tune the refresh schedule | `UniverseRefreshTier.kt` (tiers) + `UniverseRefreshPass.kt` (loop) |
| Swap embedding model | Implement `TextEmbedder` (ONNX path exists in `OnnxEmbedder.kt`) |
| Change widget layout | `AudiobookGlanceWidget.kt` + `GlanceWidgetState.kt` |

## Known Issues / Notes

- Firebase/ML Kit are optional: `FirestoreUniverseStore.create` returns null
  without `google-services.json`, and the app then behaves exactly as before.
- Eval harnesses are manual/offline: `RunRecommendationEval.kt`,
  `RunUniverseResidualEval.kt` in `app/src/test/` (report:
  `docs/recommend/EVAL-REPORT.md`).
- Tests: `data/universe/*` (WikidataParserTest, UniverseMatcherTest,
  WikidataRetryPolicyTest, WikidataSeriesProviderTest, CuratedSeedTest,
  SharedResolutionCodecTest, UniverseRefreshTierTest), `data/recommend/*`
  (RecommendationEngineTest, EmbeddingCacheTest, UnigramTokenizerTest),
  `widget/AudiobookGlanceWidgetStateTest`, and Room-backed universe tests in
  `data/repository/` (SeriesUniversesRoomTest, UniverseChainValidationTest,
  UniverseRefreshPassTest, UniverseReportTest).
