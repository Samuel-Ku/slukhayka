package com.slukhayka.audiobooks.data.universe

import com.slukhayka.audiobooks.data.collections.CollectionMatcher
import com.slukhayka.audiobooks.data.source.HttpFetcher
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Spec-26 T4 — the residual measurement. Runs the FIXED Wikidata provider
 * (T2: author in the search query; T3: retry-with-backoff on 429) over a
 * real catalog sample and classifies every miss by cause, writing a report.
 *
 * The measurement is two-variant per book:
 *  - **A — the shipped pipeline**: `resolve(title, author)` — title AND
 *    author tokens in the query (T2).
 *  - **B — the title-only counterfactual**: for A-misses only, a research
 *    probe re-searches the title WITHOUT the author token and verifies the
 *    candidate's P50 + series claim. This separates the two failure kinds
 *    that the shipped pipeline conflates: works that are NOT on Wikidata at
 *    all, and works that ARE but whose labels do not carry the author's
 *    name (wbsearchentities ANDs the tokens against label/alias text, so the
 *    T2 author token actively hides label-only works — verified live on
 *    «Кобзар»: title-only finds Q2658157, title+author finds nothing).
 *    Such books land in `title_only_recovers` — NOT a residual, but a
 *    pipeline-fix candidate.
 *
 * ML Kit translation (T1) is device-only, so variant A runs WITHOUT the
 * translator — the report states this explicitly. The probe mirrors the
 * provider's search/P50/series-check steps (research code, drift accepted).
 *
 * Run via `./gradlew runUniverseResidualEval` (JavaExec on the host JVM).
 * Throttle discipline: EVERY request is paced [requestPaceMillis] apart —
 * the within-book burst is what triggers Wikidata's rate-limit window (a
 * 2.5 s between books was NOT enough; 1 s between requests is). 429s that
 * still slip through are a measured category, not a crash. Arguments (also
 * via `--args="path paceMs limit"`):
 *   [0] report path (default docs/specs/2026-08-16-universe-residual-report.md)
 *   [1] request pace, ms (default 1000)
 *   [2] book limit (default the whole sample)
 */
object RunUniverseResidualEval {

    private val CATALOG_RESOURCE = "/recommend/eval-catalog.tsv"
    private val LANGUAGES = listOf("uk", "ru", "en")
    private val OUTCOME_LABELS = mapOf(
        "resolved" to "розпізнано (поточний шлях)",
        "title_only_recovers" to "на Wikidata, але автор у запиті (T2) ховає",
        "not_on_wikidata" to "немає на Wikidata",
        "author_mismatch" to "не зійшовся автор",
        "no_series_claim" to "немає P179",
        "chain_unplaceable" to "ланцюг не будується",
        "throttled" to "тротлінг (429)"
    )
    /** The genuine residual — misses that even title-only cannot cure. */
    private val RESIDUAL_CAUSES = setOf(
        "not_on_wikidata", "author_mismatch", "no_series_claim", "chain_unplaceable", "throttled"
    )

    private enum class Probe { RECOVERS, NOT_FOUND, AUTHOR_MISMATCH, NO_SERIES, THROTTLED }

    private data class Book(val title: String, val author: String)

    private val fetcher = HttpFetcher()
    private var requestPaceMillis: Long = 1_000L

    @JvmStatic
    fun main(args: Array<String>) {
        val reportFile = File(
            args.getOrNull(0)
                ?: "docs/specs/2026-08-16-universe-residual-report.md"
        )
        this.requestPaceMillis = args.getOrNull(1)?.toLongOrNull() ?: 1_000L
        val limit = args.getOrNull(2)?.toIntOrNull() ?: Int.MAX_VALUE

        val books = loadCatalog().take(limit)
        println("Spec-26 T4 residual measurement: ${books.size} books, ${requestPaceMillis}ms between requests")

        val counts = linkedMapOf<String, Int>()
        val misses = mutableListOf<Triple<String, String, String>>() // title, author, cause

        runBlocking {
            books.forEachIndexed { index, book ->
                // Variant A — the shipped pipeline (T2 author token + T3 retry).
                // fetchJson paces every request so the within-book burst does
                // not trigger Wikidata's rate-limit window.
                val aDiagnostics = mutableListOf<ResolutionDiagnostic>()
                val provider = WikidataSeriesProvider(
                    fetch = { url -> pacedFetch(url) },
                    diagnostic = { aDiagnostics += it }
                )
                val resolution = provider.resolve(book.title, book.author)
                // Variant B — the title-only counterfactual, for misses only.
                val probe = if (resolution == null) probeTitleOnly(book.title, book.author) else null

                val outcome = when {
                    resolution != null -> "resolved"
                    ResolutionDiagnostic.THROTTLED in aDiagnostics -> "throttled"
                    probe == Probe.RECOVERS -> "title_only_recovers"
                    probe == Probe.NO_SERIES -> "no_series_claim"
                    probe == Probe.AUTHOR_MISMATCH -> "author_mismatch"
                    probe == Probe.NOT_FOUND -> "not_on_wikidata"
                    else -> "throttled"
                }
                counts[outcome] = (counts[outcome] ?: 0) + 1
                if (resolution == null) misses += Triple(book.title, book.author, outcome)
                println("[${index + 1}/${books.size}] ${book.title} — ${book.author} → $outcome")
            }
        }

        reportFile.parentFile?.mkdirs()
        reportFile.writeText(buildReport(books.size, counts, misses))
        println()
        printSummary(counts, books.size)
        println("Report: ${reportFile.absolutePath}")
    }

    /**
     * The title-only counterfactual: re-searches the title WITHOUT the author
     * token over uk → ru → en and verifies the first candidate whose P50
     * agrees AND carries a series claim (P179/P629; P921 excluded — it needs
     * the instance-of gate). Mirrors the provider's search/P50 steps so the
     * two failure kinds separate: RECOVERS = the work IS resolvable, the T2
     * author token hid it.
     */
    private suspend fun probeTitleOnly(title: String, author: String): Probe {
        var saw429 = false
        for (language in LANGUAGES) {
            val result = fetchWithRetry(searchUrl(language, title))
            if (result.statusCode == 429) {
                saw429 = true
                continue
            }
            val ids = WikidataParser.searchHitIds(result.body)
            if (ids.isEmpty()) continue
            for (candidate in ids.take(3)) {
                val workJson = fetchEntity(candidate) ?: continue
                if (!probeAuthorMatches(candidate, workJson, author)) continue
                val hasSeries = WikidataParser.seriesIds(workJson, candidate).isNotEmpty() ||
                    WikidataParser.editionOfIds(workJson, candidate).isNotEmpty()
                return if (hasSeries) Probe.RECOVERS else Probe.NO_SERIES
            }
            return Probe.AUTHOR_MISMATCH
        }
        return if (saw429) Probe.THROTTLED else Probe.NOT_FOUND
    }

    private suspend fun probeAuthorMatches(qid: String, workJson: String, bookAuthor: String): Boolean {
        val authorIds = WikidataParser.authorIds(workJson, qid)
        if (authorIds.isEmpty()) return false
        val labelsJson = fetchEntity(authorIds.joinToString("|")) ?: return false
        val normalized = CollectionMatcher.normalizeAuthor(bookAuthor)
        return authorIds.any { id ->
            val label = WikidataParser.label(labelsJson, id, LANGUAGES)
            label != null && CollectionMatcher.normalizeAuthor(label) == normalized
        }
    }

    /** Paced + retried request (the probe and the provider seam share it). */
    private suspend fun pacedFetch(url: String): WikidataResponse = fetchWithRetry(url)

    private suspend fun fetchEntity(ids: String): String? =
        fetchWithRetry(entitiesUrl(ids)).body.takeIf { it.isNotBlank() }

    /** Pace every request, then retry 429s (same backoff as the provider). */
    private suspend fun fetchWithRetry(url: String, attempts: Int = 3): WikidataResponse {
        delay(requestPaceMillis)
        var attempt = 1
        while (true) {
            val (status, body) = fetcher.getTextResult(url)
            val result = WikidataResponse(status, body)
            if (result.statusCode != 429 || attempt >= attempts) return result
            delay(500L * (1L shl (attempt - 1)))
            attempt++
        }
    }

    private fun searchUrl(language: String, title: String): String =
        "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json" +
            "&language=$language&uselang=$language&type=item&search=${URLEncoder.encode(title, "UTF-8")}"

    private fun entitiesUrl(ids: String): String =
        "https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&ids=$ids&props=claims|labels"

    private fun loadCatalog(): List<Book> {
        val lines = RunUniverseResidualEval::class.java.getResourceAsStream(CATALOG_RESOURCE)
            ?.bufferedReader()?.readLines()
            ?: error("catalog fixture not found: $CATALOG_RESOURCE")
        return lines.drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("\t")
            if (parts.size < 3) null
            else Book(parts[1].trim(), parts[2].trim())
        }.filter { it.title.isNotBlank() && it.author.isNotBlank() }
    }

    private fun buildReport(
        total: Int,
        counts: Map<String, Int>,
        misses: List<Triple<String, String, String>>
    ): String {
        val resolved = counts["resolved"] ?: 0
        val recovered = counts["title_only_recovers"] ?: 0
        val residual = counts.entries.filter { it.key in RESIDUAL_CAUSES }.sumOf { it.value }
        val rows = OUTCOME_LABELS.keys.mapNotNull { cause ->
            val count = counts[cause] ?: return@mapNotNull null
            "| ${OUTCOME_LABELS[cause]} | $count | ${"%.1f".format(count * 100.0 / total)}% |"
        }.joinToString("\n")

        val missLines = misses.joinToString("\n") { (title, author, cause) ->
            "- **$title** ($author) — ${OUTCOME_LABELS[cause]}"
        }

        return """
            |# Замір залишку резолюції всесвітів (spec-26 T4)
            |
            |**Дата:** ${java.time.LocalDate.now()}
            |**Вибірка:** $total книг із `app/src/test/resources/recommend/eval-catalog.tsv` (реальний каталог).
            |**Варіант A:** виправлений Wikidata-провайдер (T2 — автор у запиті, T3 — retry на 429), реальний транспорт, жива API.
            |**Варіант B:** title-only контрфактуал (для промахів A) — пошук лише за назвою + P50-верифікація, щоб відділити «немає на Wikidata» від «T2-токен автора ховає твір».
            |**Обмеження:** ML Kit переклад (T1) — device-only, у JVM-харнесі вимкнений (його ефект відомий: твори без uk-лейблів, як якір «Трохи ненависті», резолвляться через ru-переклад). Проб не перевіряє P921 і ланцюг P155/P156 (лише P179/P629) — на загальний підсумок впливає незначно.
            |
            |## Підсумок
            |
            |Поточний шлях розпізнає **$resolved** з $total (${"%.1f".format(resolved * 100.0 / total)}%). Ще **$recovered** книг (${"%.1f".format(recovered * 100.0 / total)}%) — на Wikidata з підтвердженим автором, але **автор у пошуковому запиті (T2) їх ховає**: `wbsearchentities` AND-ить токени по тексту лейблів, а в лейблах творів імені автора зазвичай немає (перевірено наживо: «Кобзар» знаходиться, «Кобзар Тарас Шевченко» — ні). Це не залишок бази, а кандидат на фікс пайплайну (наприклад, пошук спершу лише за назвою, автор — як розв'язка неоднозначності).
            |
            |**Справжній залишок** (не закривається навіть title-only) — **$residual** книг (${"%.1f".format(residual * 100.0 / total)}%).
            |
            |## Розбивка за причинами
            |
            || Причина | Книг | Частка |
            || --- | ---: | ---: |
            |$rows
            |
            |## Нерозпізнані книги (варіант A)
            |
            |$missLines
            |
            |## Висновок про обсяг спільної бази
            |
            |Firestore зберігає одну резолюцію на розпізнану книгу (`universe_resolutions/{workId}`, ~0.5 КБ на документ). Поточний шлях наповнить базу ≈ **$resolved документами** (одиниці КБ — безкоштовний тариф 1 ГБ із величезним запасом), і кожен наступний користувач читає готове, не б'ючи Wikidata.
            |
            |Якщо T2-регресію виправити (title-only спершу), база зросте ще на ≈ **$recovered** документів — досі копійки. **Справжній залишок — $residual книг (${"%.1f".format(residual * 100.0 / total)}%)** — спільна база не закриє в принципі: резолюція не відбулась, писати нічого. Цей обсяг мають покривати курований ассет + канал фідбеку (скарга → перерезолюція → виправлення).
        """.trimMargin()
    }

    private fun printSummary(counts: Map<String, Int>, total: Int) {
        println("=== Summary ===")
        for ((cause, count) in counts) {
            println("  ${OUTCOME_LABELS[cause]}: $count (${"%.1f".format(count * 100.0 / total)}%)")
        }
    }
}
