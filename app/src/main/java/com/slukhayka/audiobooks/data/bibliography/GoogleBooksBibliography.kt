package com.slukhayka.audiobooks.data.bibliography

import com.slukhayka.audiobooks.data.LanguageCode
import com.slukhayka.audiobooks.data.collections.MiniJson
import com.slukhayka.audiobooks.data.source.GateOutcome
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.SourceGateProvider
import com.slukhayka.audiobooks.data.source.SourceRequestClass
import com.slukhayka.audiobooks.data.source.SourceRequestGate
import com.slukhayka.audiobooks.data.source.SourceRequestProfile

/**
 * ADR-0053 / #858 (T5) — the Google Books FALLBACK of the external
 * bibliography: the second base a tracked Work's ISBN is asked against after
 * Open Library stated that it does not know the edition ([BibliographyChain]).
 * It answers with the SAME normalized [BibliographyCandidate]s and the same
 * [BibliographyOutcome] vocabulary as [OpenLibraryBibliography], and it can
 * assert NOTHING about audio (ADR-0053 §2, #853 US14).
 *
 * ## The key never enters the client
 *
 * Google Books without a key answers 429 (verified live 2026-09-19: «Quota
 * exceeded … books.googleapis.com»), so the key is mandatory and it lives
 * ONLY in the Cloudflare worker as an env-secret (`GOOGLE_BOOKS_KEY`). This
 * provider therefore does not build a `googleapis.com` request at all: its
 * ONE door is the Web Transport worker's keyless route [ISBN_ROUTE]
 * (`<base>/api/bibliography/isbn?isbn=…`), which appends the key server-side
 * and returns the Google Books body verbatim. A request URL built here can
 * never carry a key, and no code in the client knows one.
 *
 * ## The base is wired, never invented
 *
 * [webTransportBase] is injected: the repository commits no deployment URL,
 * so a default would be a fabricated host. Wiring supplies the Web Transport
 * worker origin; a blank base honestly degrades to [BibliographyOutcome.Unavailable]
 * (nothing was checkable) instead of firing a relative request. Note the
 * documented gap this leaves: the Web Transport worker is the Web Client's
 * door (CONTEXT.md, ADR-0024) and the Android app has no such base configured
 * today — the provider is the contract, its wiring is a separate decision.
 *
 * ## The existing seam, no new rhythm
 *
 * Transport is the shared [HttpFetcher] (ADR-0006); the politeness
 * [SourceRequestGate] decides with the profile this class DECLARES next to its
 * code ([requestProfile]) — the same LISTENER_ACTION class and the same 24 h
 * [SourceRequestProfile.SEARCH_TTL_MS] as the Open Library doors, so the cache
 * layer is the one T4 already rides (ADR-0040: features never classify a
 * request and never know the numbers). The gate's budget host is the WORKER,
 * not Google: the client is polite to the door it actually opens, and the
 * worker answers for its own upstream quota. With no gate installed the
 * provider degrades to the raw transport — the way the shared transport does.
 *
 * ## Honest states, never fabrication
 *
 * [BibliographyOutcome.Found] with an EMPTY list is a legitimate "the base
 * stated nothing usable"; [Deferred] is the budget resting (ADR-0039/0040);
 * [Unavailable] is a failed fetch, a non-200 door answer or an unparseable
 * body. Absent claims stay absent: no cover, no year, no author, no language.
 */
class GoogleBooksBibliography(
    /** The Web Transport worker origin, e.g. a `*.workers.dev` URL. No default exists on purpose. */
    webTransportBase: String,
    private val fetcher: HttpFetcher = HttpFetcher(),
    /**
     * The politeness gate; null resolves [SourceGateProvider.current] at CALL
     * time (the [HttpFetcher] convention). With no gate anywhere the provider
     * degrades to the raw transport — no cache, no budget.
     */
    private val gate: SourceRequestGate? = null
) {

    private val routeBase: String = webTransportBase.trim().trimEnd('/')

    /**
     * ADR-0040 — the provider's own rhythm, declared here rather than in a
     * feature. Google Books is the ISBN fallback only (#858), so there is ONE
     * door: a listener action whose answer stays fresh for a day, exactly like
     * the Open Library doors.
     */
    fun requestProfile(): SourceRequestProfile =
        SourceRequestProfile(SourceRequestClass.LISTENER_ACTION, SourceRequestProfile.SEARCH_TTL_MS)

    /**
     * Resolves one ISBN (10 or 13 characters, separators ignored) to its
     * Google Books edition through the worker's keyless route. A value that is
     * not an ISBN honestly finds nothing without touching the network; a blank
     * [routeBase] honestly reports the door unusable.
     */
    suspend fun isbn(raw: String): BibliographyOutcome<List<BibliographyCandidate>> {
        // The ONE ISBN normalization (T4) — a second copy would be a second truth.
        val clean = OpenLibraryBibliography.normalizeIsbn(raw) ?: return BibliographyOutcome.Found(emptyList())
        if (routeBase.isBlank()) return BibliographyOutcome.Unavailable
        return when (val body = fetch(routeUrl(routeBase, clean))) {
            is BibliographyOutcome.Found ->
                BibliographyOutcome.Found(GoogleBooksJson.isbnCandidates(body.value, clean))
            is BibliographyOutcome.Deferred -> body
            BibliographyOutcome.Unavailable -> BibliographyOutcome.Unavailable
        }
    }

    /** One gated request; a blank body is a failed fetch, never an empty answer. */
    private suspend fun fetch(url: String): BibliographyOutcome<String> {
        val profile = requestProfile()
        val activeGate = gate ?: SourceGateProvider.current
        if (activeGate == null) {
            val body = fetcher.getText(url)
            return if (body.isBlank()) BibliographyOutcome.Unavailable else BibliographyOutcome.Found(body)
        }
        return when (val outcome = activeGate.run(url, profile.requestClass, profile.cacheTtlMillis) {
            fetcher.getText(url).ifBlank { null }
        }) {
            is GateOutcome.Fresh -> BibliographyOutcome.Found(outcome.value)
            is GateOutcome.Fetched -> BibliographyOutcome.Found(outcome.value)
            is GateOutcome.Deferred -> BibliographyOutcome.Deferred(outcome.retryAfterMs)
            GateOutcome.Unavailable -> BibliographyOutcome.Unavailable
        }
    }

    companion object {
        /**
         * The Web Transport worker's keyless Google Books door: the worker
         * appends `GOOGLE_BOOKS_KEY` and returns the upstream body verbatim
         * (statuses transparent, like the relay recipe).
         */
        const val ISBN_ROUTE = "/api/bibliography/isbn"

        /** The one URL this provider ever opens — ISBN in, no key anywhere. */
        fun routeUrl(routeBase: String, cleanIsbn: String): String =
            "${routeBase.trim().trimEnd('/')}$ISBN_ROUTE?isbn=$cleanIsbn"
    }
}

/**
 * The pure-JVM Google Books parsers (#858), fixture-tested through the ONE
 * [MiniJson] decoder via [MiniJson.parseLenient] (real `books#volumes` bodies
 * carry literal nulls). No Android, no network, no key.
 *
 * Nothing is invented: a missing title drops the row, and a missing author,
 * cover, year or language stays absent. The requested ISBN is carried back
 * when the document names no identifier — that is the value the caller ASKED
 * with, not a guess.
 */
object GoogleBooksJson {

    /**
     * One `books#volumes` response -> a normalized card per usable `items[]`
     * row. `totalItems: 0` (or a missing `items`) is an honest empty list.
     */
    fun isbnCandidates(json: String, requestedIsbn: String): List<BibliographyCandidate> {
        val root = MiniJson.parseLenient(json) as? Map<*, *> ?: return emptyList()
        return (root["items"] as? List<*>).orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .mapNotNull { volume(it, requestedIsbn) }
    }

    /** One volume: `volumeInfo` is the only shape that states cardable claims. */
    private fun volume(item: Map<*, *>, requestedIsbn: String): BibliographyCandidate? {
        val info = item["volumeInfo"] as? Map<*, *> ?: return null
        val title = (info["title"] as? String).orEmpty().trim()
        if (title.isBlank()) return null // a card without a title states nothing usable
        return BibliographyCandidate(
            title = title,
            authors = strings(info["authors"]),
            firstPublishYear = yearOf(info["publishedDate"] as? String),
            isbn = identifierOf(info["industryIdentifiers"]) ?: requestedIsbn.takeIf { it.isNotBlank() },
            // A Google Books volume id is NOT an Open Library Work key; mixing
            // the namespaces would let a consumer build an openlibrary.org URL
            // from it, so the claim stays absent instead.
            workKey = null,
            coverImageUrl = coverUrl(info["imageLinks"]),
            languages = languagesOf(info["language"])
        )
    }

    /** Every non-blank author NAME the volume states, in document order. */
    private fun strings(raw: Any?): List<String> =
        (raw as? List<*>).orEmpty()
            .mapNotNull { it as? String }
            .map { it.trim() }
            .filter { it.isNotBlank() }

    /**
     * The preferred ISBN of a volume — an ISBN-13 when there is one, then an
     * ISBN-10. `OTHER`/`ISSN` claims are not ISBNs and are ignored.
     */
    private fun identifierOf(raw: Any?): String? {
        val claims = (raw as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        fun claimOf(type: String): String? = claims
            .firstOrNull { (it["type"] as? String)?.equals(type, ignoreCase = true) == true }
            ?.let { (it["identifier"] as? String)?.trim() }
            ?.takeIf { it.isNotBlank() }
        return claimOf("ISBN_13") ?: claimOf("ISBN_10")
    }

    /**
     * The volume's cover (`thumbnail`, else `smallThumbnail`). The API hands
     * these out as `http://books.google.com/…`; the app's network security
     * config forbids cleartext, so the SAME resource is requested over https —
     * and nothing else about the URL is touched.
     */
    private fun coverUrl(raw: Any?): String? {
        val links = raw as? Map<*, *> ?: return null
        val url = (links["thumbnail"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: (links["smallThumbnail"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        return if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
    }

    /**
     * Every language claim mapped through the ONE vocabulary ([LanguageCode]);
     * an unmappable claim is dropped rather than guessed, and an empty result
     * is unknown — never a filter that hides the row.
     */
    private fun languagesOf(raw: Any?): List<String> {
        val claims = when (raw) {
            is List<*> -> raw
            null -> return emptyList()
            else -> listOf(raw)
        }
        return claims.mapNotNull { LanguageCode.normalize(it as? String) }.distinct()
    }

    /** The year a `publishedDate` states ("2014", "2014-05", "2014-05-01"). */
    private fun yearOf(text: String?): Int? =
        YEAR.find(text.orEmpty())?.groupValues?.get(1)?.toIntOrNull()

    private val YEAR = Regex("""(\d{4})""")
}
