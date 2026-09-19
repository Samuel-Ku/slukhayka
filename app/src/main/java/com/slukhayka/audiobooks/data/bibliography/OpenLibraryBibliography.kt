package com.slukhayka.audiobooks.data.bibliography

import com.slukhayka.audiobooks.data.LanguageCode
import com.slukhayka.audiobooks.data.collections.MiniJson
import com.slukhayka.audiobooks.data.source.GateOutcome
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.SourceGateProvider
import com.slukhayka.audiobooks.data.source.SourceRequestClass
import com.slukhayka.audiobooks.data.source.SourceRequestGate
import com.slukhayka.audiobooks.data.source.SourceRequestProfile
import java.net.URLEncoder

/**
 * ADR-0053 / #857 (T4) — the Open Library provider of the external
 * bibliography: the keyless base a tracked Work's metadata is looked up
 * against (search, works, isbn), ahead of the Google Books ISBN fallback
 * (#858). It answers with NORMALIZED [BibliographyCandidate]s and with
 * NOTHING else: an external base never asserts audio (ADR-0053 §2, #853
 * US14), so no field here can claim a narration, a duration or chapters.
 *
 * ## The existing seams, no new rhythm
 *
 * Transport is the shared [HttpFetcher] (ADR-0006 — one HTTP transport); the
 * politeness [SourceRequestGate] decides (fresh cache -> class-aware token ->
 * single-flight -> per-host throat) with the profile this class DECLARES next
 * to its own code ([requestProfile]) — features never classify a request and
 * never know the numbers (ADR-0040). Open Library is not an audio Source, so
 * it is deliberately NOT registered in `SourceRegistry` (ADR-0038): the
 * provider reaches the gate through this seam, exactly like [SourceGateFetcher]
 * does, and the transport's Source-host allowlist stays untouched.
 *
 * ## Cache: the key IS the Work key / ISBN, the TTL is a day
 *
 * All three doors declare [SourceRequestProfile.SEARCH_TTL_MS] (24 h), so a
 * repeated search of the same words, the same OL Work key or the same ISBN is
 * served from the gate's fresh cache with ZERO requests and ZERO tokens. The
 * gate caches by request URL and every URL is built from the Work key / ISBN
 * / query, which is exactly the key #857 asks for. There is deliberately no
 * second cache and no new table.
 *
 * ## Honest states, never fabrication
 *
 * [BibliographyOutcome.Found] with an EMPTY list is a legitimate "the base
 * stated nothing usable"; [BibliographyOutcome.Deferred] is the budget
 * resting (the honest partial state, ADR-0039/0040); [Unavailable] is a
 * failed fetch or an unparseable body. Absent claims stay absent: no cover,
 * no year, no author NAME (Open Library work/edition documents name only
 * author keys), and an unknown language stays unmapped — which no language
 * filter ever hides (ADR-0029, #853 US15/US17).
 *
 * The query is the listener's own Ukrainian words; NO `language:` clause is
 * added, because such a gate would hide exactly the unknown-language rows the
 * milestone forbids hiding (the spec-51 #742 precedent).
 */
class OpenLibraryBibliography(
    private val fetcher: HttpFetcher = HttpFetcher(),
    /**
     * The politeness gate; null resolves [SourceGateProvider.current] at CALL
     * time (the [HttpFetcher] convention), so the install order in `App`
     * never matters. With no gate anywhere the provider degrades to the raw
     * transport — no cache, no budget — the way the shared transport itself
     * does.
     */
    private val gate: SourceRequestGate? = null
) {

    /**
     * ADR-0040 — the provider's own rhythm, declared here rather than in a
     * feature: every door is a listener action whose answer is cached for a
     * day.
     */
    fun requestProfile(endpoint: BibliographyEndpoint): SourceRequestProfile = when (endpoint) {
        BibliographyEndpoint.SEARCH,
        BibliographyEndpoint.WORK,
        BibliographyEndpoint.ISBN ->
            SourceRequestProfile(SourceRequestClass.LISTENER_ACTION, SourceRequestProfile.SEARCH_TTL_MS)
    }

    /**
     * Searches Open Library by the listener's words (a title, an author, or
     * both). A blank query or a non-positive [limit] honestly finds nothing
     * without touching the network.
     */
    suspend fun search(
        query: String,
        limit: Int = DEFAULT_LIMIT
    ): BibliographyOutcome<List<BibliographyCandidate>> {
        val clean = query.trim()
        if (clean.isBlank() || limit <= 0) return BibliographyOutcome.Found(emptyList())
        return mapFound(searchUrl(clean, limit), BibliographyEndpoint.SEARCH) {
            OpenLibraryJson.searchCandidates(it)
        }
    }

    /**
     * Resolves one Open Library Work by its key (`/works/OL…W`, a bare
     * `OL…W`, or a full openlibrary.org URL). A value that names no Work key
     * honestly finds nothing.
     */
    suspend fun work(workKey: String): BibliographyOutcome<List<BibliographyCandidate>> {
        val url = workUrl(workKey) ?: return BibliographyOutcome.Found(emptyList())
        return mapFound(url, BibliographyEndpoint.WORK) {
            listOfNotNull(OpenLibraryJson.workCandidate(it))
        }
    }

    /**
     * Resolves one ISBN (10 or 13 characters, separators ignored) to its Open
     * Library edition. A value that is not an ISBN honestly finds nothing.
     */
    suspend fun isbn(raw: String): BibliographyOutcome<List<BibliographyCandidate>> {
        val clean = normalizeIsbn(raw) ?: return BibliographyOutcome.Found(emptyList())
        return mapFound(isbnDocUrl(clean), BibliographyEndpoint.ISBN) {
            listOfNotNull(OpenLibraryJson.isbnCandidate(it, clean))
        }
    }

    private suspend fun mapFound(
        url: String,
        endpoint: BibliographyEndpoint,
        parse: (String) -> List<BibliographyCandidate>
    ): BibliographyOutcome<List<BibliographyCandidate>> =
        when (val body = fetch(url, endpoint)) {
            is BibliographyOutcome.Found -> BibliographyOutcome.Found(parse(body.value))
            is BibliographyOutcome.Deferred -> body
            BibliographyOutcome.Unavailable -> BibliographyOutcome.Unavailable
        }

    /** One gated request; a blank body is a failed fetch, never an empty answer. */
    private suspend fun fetch(url: String, endpoint: BibliographyEndpoint): BibliographyOutcome<String> {
        val profile = requestProfile(endpoint)
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
        const val SEARCH_ENDPOINT = "https://openlibrary.org/search.json"
        const val WORKS_ROOT = "https://openlibrary.org"
        const val ISBN_ENDPOINT = "https://openlibrary.org/isbn"

        /** The default page of candidates one search asks for. */
        const val DEFAULT_LIMIT = 10

        /**
         * The ONLY fields the normalized card is built from — requesting more
         * would grow the payload without adding a claim the card can carry.
         */
        const val SEARCH_FIELDS = "key,title,author_name,first_publish_year,cover_i,isbn,language"

        /** The cover-image sizes Open Library serves; the card takes medium. */
        fun coverUrl(coverId: Long): String = "https://covers.openlibrary.org/b/id/$coverId-M.jpg"

        fun searchUrl(query: String, limit: Int): String =
            "$SEARCH_ENDPOINT?q=${urlEncode(query)}&fields=$SEARCH_FIELDS&limit=$limit"

        /** An OL Work key (or a full work URL) -> its JSON document URL; null when no key. */
        fun workUrl(workKey: String): String? {
            val trimmed = workKey.trim()
            val after = if ("/works/" in trimmed) trimmed.substringAfter("/works/") else trimmed
            val key = after.substringBefore('/')
                .substringBefore('?')
                .substringBefore(".json")
                .trim()
            // Open Library Work ids are stable `OL<digits>W`; anything else names
            // no Work and must not spend a request.
            if (!key.matches(WORK_KEY)) return null
            return "$WORKS_ROOT/works/$key.json"
        }

        /** A 10/13-character ISBN (separators ignored) -> its edition URL; null otherwise. */
        fun isbnDocUrl(cleanIsbn: String): String = "$ISBN_ENDPOINT/$cleanIsbn.json"

        /** The canonical ISBN form (digits, trailing X upper-cased), or null when it is not one. */
        fun normalizeIsbn(raw: String): String? = raw.trim()
            .filter { it.isDigit() || it == 'X' || it == 'x' }
            .uppercase()
            .takeIf { it.length == 10 || it.length == 13 }

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private val WORK_KEY = Regex("""OL\d+W""")
    }
}

/**
 * One normalized external-bibliography card (ADR-0053 Work-level metadata).
 *
 * Every field is optional except the title: the base states what it states,
 * and an absent claim stays absent — never a guess. [authors] is empty when
 * the document carries no author NAMES (an Open Library work or edition
 * document names only author keys), and the tracked-Work write path refuses a
 * nameless identity on its own rather than let the app invent one.
 *
 * [languages] is every claim the document makes, mapped through
 * [LanguageCode] with unmappable claims dropped (never invented); empty =
 * unknown, which the language filter never hides.
 */
data class BibliographyCandidate(
    val title: String,
    val authors: List<String> = emptyList(),
    val firstPublishYear: Int? = null,
    val isbn: String? = null,
    val workKey: String? = null,
    val coverImageUrl: String? = null,
    val languages: List<String> = emptyList()
)

/** The three Open Library doors this provider declares a rhythm for. */
enum class BibliographyEndpoint {
    /** Keyword search — a title / author the listener typed. */
    SEARCH,

    /** One Work document by its OL key. */
    WORK,

    /** One edition document by its ISBN. */
    ISBN
}

/**
 * The provider's honest answer, mirroring [GateOutcome]: [Found] carries what
 * the base actually stated (an empty list is a legitimate "nothing");
 * [Deferred] is the budget resting and [Unavailable] a failed fetch — both
 * distinct, so a surface can say WHICH truth it shows instead of a blank.
 */
sealed interface BibliographyOutcome<out T> {
    data class Found<T>(val value: T) : BibliographyOutcome<T>
    data class Deferred(val retryAfterMs: Long) : BibliographyOutcome<Nothing>
    data object Unavailable : BibliographyOutcome<Nothing>
}

/**
 * The pure-JVM Open Library parsers (#857), fixture-tested against VERBATIM
 * live responses (2026-09-18) — no Android, no network. All three documents
 * are decoded through the ONE [MiniJson] decoder via [MiniJson.parseLenient],
 * because Open Library documents carry literal JSON nulls that the strict
 * [MiniJson.parse] rejects wholesale.
 *
 * Nothing is invented: a missing author NAME (works/editions carry author
 * keys), cover, year, ISBN or language stays absent.
 */
object OpenLibraryJson {

    /** One `/search.json` response -> a normalized card per usable `docs[]` row. */
    fun searchCandidates(json: String): List<BibliographyCandidate> {
        val root = root(json) ?: return emptyList()
        return (root["docs"] as? List<*>).orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .mapNotNull { searchDocument(it) }
    }

    /** One `/works/<key>.json` document -> a card, or null when it states no title. */
    fun workCandidate(json: String): BibliographyCandidate? {
        val root = root(json) ?: return null
        return candidate(
            title = string(root, "title"),
            authors = emptyList(),
            year = yearOf(string(root, "first_publish_date")),
            isbn = null,
            workKey = string(root, "key").ifBlank { null },
            coverId = firstCoverId(root["covers"]),
            language = languageOf(root["languages"])
        )
    }

    /** One `/isbn/<isbn>.json` edition document -> a card, or null without a title. */
    fun isbnCandidate(json: String, requestedIsbn: String): BibliographyCandidate? {
        val root = root(json) ?: return null
        return candidate(
            title = string(root, "title"),
            authors = emptyList(),
            year = yearOf(string(root, "publish_date")),
            isbn = firstIsbn(root["isbn_13"])
                ?: firstIsbn(root["isbn_10"])
                ?: requestedIsbn.takeIf { it.isNotBlank() },
            workKey = firstNestedKey(root["works"]),
            coverId = firstCoverId(root["covers"]),
            language = languageOf(root["languages"])
        )
    }

    /** A search row: the ONE document that carries author NAMES. */
    private fun searchDocument(doc: Map<*, *>): BibliographyCandidate? = candidate(
        title = string(doc, "title"),
        authors = (doc["author_name"] as? List<*>).orEmpty()
            .mapNotNull { it as? String }
            .filter { it.isNotBlank() },
        year = (doc["first_publish_year"] as? Double)?.toInt(),
        isbn = firstIsbn(doc["isbn"]),
        workKey = string(doc, "key").ifBlank { null },
        coverId = (doc["cover_i"] as? Double)?.toLong(),
        language = languageOf(doc["language"])
    )

    private fun candidate(
        title: String,
        authors: List<String>,
        year: Int?,
        isbn: String?,
        workKey: String?,
        coverId: Long?,
        language: List<String>
    ): BibliographyCandidate? {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return null // a card without a title states nothing usable
        return BibliographyCandidate(
            title = cleanTitle,
            authors = authors,
            firstPublishYear = year,
            isbn = isbn,
            workKey = workKey,
            // Open Library marks "no cover" with 0 / -1; those are absence.
            coverImageUrl = coverId?.takeIf { it > 0 }?.let { OpenLibraryBibliography.coverUrl(it) },
            languages = language
        )
    }

    /** The first cover id of a `covers` array; null when absent or non-numeric. */
    private fun firstCoverId(raw: Any?): Long? =
        (raw as? List<*>).orEmpty().mapNotNull { (it as? Double)?.toLong() }.firstOrNull()

    /** The preferred ISBN of a claim list — an ISBN-13 when there is one. */
    private fun firstIsbn(raw: Any?): String? {
        val values = (raw as? List<*>).orEmpty()
            .mapNotNull { it as? String }
            .filter { it.isNotBlank() }
        return values.firstOrNull { it.length == 13 } ?: values.firstOrNull()
    }

    /** The first `key` of a `[{"key": …}]` array (Works / authors references). */
    private fun firstNestedKey(raw: Any?): String? =
        (raw as? List<*>).orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .mapNotNull { (it["key"] as? String)?.takeIf { key -> key.isNotBlank() } }
            .firstOrNull()

    /**
     * Every language claim mapped through the ONE vocabulary ([LanguageCode]);
     * an unmappable claim is dropped rather than guessed, and an empty result
     * is unknown — never a filter that hides the row.
     */
    private fun languageOf(raw: Any?): List<String> {
        val claims = when (raw) {
            is List<*> -> raw
            null -> return emptyList()
            else -> listOf(raw)
        }
        return claims.mapNotNull { claim ->
            val text = when (claim) {
                is String -> claim
                is Map<*, *> -> claim["key"] as? String ?: ""
                else -> ""
            }
            // "/languages/ukr" -> "ukr"; "ukr" stays itself.
            LanguageCode.normalize(text.substringAfterLast('/'))
        }.distinct()
    }

    /** The year a publication-date claim states ("1995", "c2014", "2014-05-01"). */
    private fun yearOf(text: String): Int? =
        YEAR.find(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun root(json: String): Map<*, *>? = MiniJson.parseLenient(json) as? Map<*, *>

    private fun string(obj: Map<*, *>, key: String): String = (obj[key] as? String).orEmpty()

    private val YEAR = Regex("""(\d{4})""")
}
