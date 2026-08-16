# Data Sources Module

<!-- Generated: 2026-08-16 | Files scanned: 12 | Kotlin lines: ~2,050 -->

## Purpose

The multi-source catalog seam (spec-10): one `SourceAdapter` per playable
source, each owning its parsing so a markup change in one source fails only
that source's fixture tests. This module is the parser/transport layer under
the catalog, import and enrichment modules.

## Key Files

```
app/src/main/java/com/example/data/source/SourceAdapter.kt   143 lines  (the seam + models)
app/src/main/java/com/example/data/source/FourReadAdapter.kt 123 lines
app/src/main/java/com/example/data/source/SoundBooksAdapter.kt 180 lines
app/src/main/java/com/example/data/source/AudiobookMp3Adapter.kt 169 lines
app/src/main/java/com/example/data/source/LihtarAdapter.kt   138 lines
app/src/main/java/com/example/data/source/SluhayuaAdapter.kt 277 lines
app/src/main/java/com/example/data/source/SluhayAdapter.kt   313 lines  (WebView session-bound)
app/src/main/java/com/example/data/source/HttpFetcher.kt     118 lines  (shared degrade-never-throw transport)
app/src/main/java/com/example/data/source/GlobalSearch.kt    116 lines
app/src/main/java/com/example/data/source/DownloadPolicy.kt   61 lines
app/src/main/java/com/example/data/source/WebViewHtmlParser.kt      329 lines
app/src/main/java/com/example/data/source/WebViewInterceptLogParser.kt  80 lines
```

## The Seam (SourceAdapter)

```kotlin
interface SourceAdapter {
    val sourceId: String                       // "4read", "soundbooks", "audiobookmp3", "lihtar", "sluhayua", "sluhay"
    suspend fun search(query: String): List<SourceBook>
    suspend fun fetchBookPage(url: String): SourceBookDetail
    suspend fun fetchNew(limit: Int = 20): List<SourceBook>
    suspend fun fetchCatalog(limit: Int = 40): List<SourceBook> = fetchNew(limit)
    val sessionBound: Boolean get() = false    // WebView-pattern sources (Cloudflare)
    suspend fun parseCapturedPage(html, url): SourceBookDetail? = null   // ADR-0006
    fun bookId(url: String): String            // stable Work id — produced in exactly this one place (spec-14 T5)
}
```

Normalized models: `SourceBook` (card), `SourceChapter` (stream URL + duration),
`SeriesRef`, `RelatedBook`, `SourceBookDetail` (full page parse: cover, ordered
chapters, rating, genres, series, related, description). Fields a page does not
provide are absent, never fabricated.

## Registered Adapters (App.kt)

| sourceId | Adapter | Notes |
|---|---|---|
| `4read` | `FourReadAdapter` | Native browse source; overrides `bookId` ("4read-slug") and `parseCapturedPage` |
| `soundbooks` | `SoundBooksAdapter` | sound-books.net |
| `audiobookmp3` | `AudiobookMp3Adapter` | audiobook-mp3.com/uk |
| `lihtar` | `LihtarAdapter` | lihtar.in.ua |
| `sluhayua` | `SluhayuaAdapter` | sluhayua source (spec-11) |
| `sluhay` | `SluhayAdapter` | WebView-pattern, session-bound; cookie lambda from `CookieManager` (Android-side only) |

`sluhay` is NOT part of the unified catalogue union (its discovery rides the
live browser session); an absent/stale session surfaces a «відкрити джерело,
щоб оновити» CTA instead of silently dropping the source (spec-13).

## Transport & Parsing

- `HttpFetcher` — the one HTTP client (shared by adapters, catalog, universe).
  Degrade-never-throw by contract; `getTextResult` returns (status, body) for
  the universe 429-retry policy.
- `WebViewHtmlParser` + `WebViewInterceptLogParser` — turn HTML captured from a
  live WebView session (and the intercepted network log) into
  `SourceBookDetail`, the ADR-0006 captured-page door.
- `GlobalSearch` — cross-source search fan-out + merge (`mergeGlobalSearchResults`).
- `DownloadPolicy` — offline-download guard rules per source.

## Dependencies

- **Inbound:** `App` (constructs the adapter list), `data.catalog.SourceCatalog`, `data.imports.LibraryImport`, `data.entries.LibraryEntries`, `data.duration.DurationEnrichment` (rides 4read's fetchBookPage), `data.universe` (reuses HttpFetcher)
- **Outbound:** `HttpFetcher`, JDK/`kotlinx.coroutines`; Android WebView only inside sluhay's cookie lambda / capture path (fixture tests stay JVM-pure)

## Common Tasks

| Task | Touch |
|---|---|
| Add a new source | New adapter in `data/source/` + register in `App.kt` list + fixture tests |
| Fix a source after markup change | That adapter only (+ its fixture tests) — parser seam isolates failures |
| Add WebView capture for a source | Override `sessionBound = true` + `parseCapturedPage` (ADR-0006, no downcasting) |
| Change shared HTTP behavior | `HttpFetcher.kt` |

## Known Issues / Notes

- Fixture tests live beside each adapter (`FourReadAdapterTest`, `SluhayAdapterTest`,
  …) and pin inline HTML — keep them JVM-only (no WebView in unit tests).
- The 4read parser is centralized in `data/catalog/CatalogParser.kt` (spec-11:
  «one 4read parser») — the Explore sections and the adapter both read it.
