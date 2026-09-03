package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.LanguageCode

/**
 * Spec-45 (#405) T2 (#490) — librivox.org joins the adapter seam as the
 * English-language source. The MVP surfaces ENGLISH books only (the spec's
 * LibriVox start), one card per Work, alongside the Ukrainian sources.
 *
 * ## Two transports, ONE sourceId
 *
 * - **librivox.org JSON API** (`/api/feed/audiobooks/?format=json`) — the
 *   catalogue enumeration ([fetchCatalog]): clean titles, `url_librivox`
 *   pages, durations. The API has no keyword search and no date ordering.
 * - **archive.org advanced search** — the `archive.org/details/librivoxaudio`
 *   MIRROR of the same recordings, used for keyword [search] and newest-first
 *   [fetchNew] (the archive adds full-text search + `addeddate`). Cards
 *   returned from the mirror carry `archive.org/details/<identifier>` URLs but
 *   the SAME `sourceId = "librivox"`, so a book found on both transports
 *   merges into one card (the union's merge key + per-source dedup), never a
 *   second catalogue row — "One book, one card".
 *
 * Every returned card claims `language = en` (records are English-filtered at
 * the query/parse level, then normalized through [LanguageCode]).
 *
 * Book pages (chapters/tracks) are spec-45 T3 (#491); until then
 * [fetchBookPage] honestly reports nothing playable.
 */
class LibriVoxAdapter(
    private val fetcher: HttpFetcher = HttpFetcher()
) : SourceAdapter {

    override val sourceId: String = "librivox"

    /** Spec-45 (#405) — this source speaks English. */
    override val contentLanguage = "en"

    /**
     * Archive.org advanced-search keyword search over the librivoxaudio
     * collection (the mirror transport). The query rides as a quoted phrase
     * so user words like "and"/"or" never collide with the archive query
     * operators.
     */
    override suspend fun search(query: String): List<SourceBook> {
        val cleanQuery = query.trim().replace("\"", "")
        if (cleanQuery.isBlank()) return emptyList()
        val json = fetcher.getText(archiveSearchUrl("\"$cleanQuery\"", newestFirst = false))
        return archiveDocsFrom(json).mapNotNull { it.toSourceBook() }
    }

    /**
     * Newest English recordings of the archive mirror, newest first
     * (`addeddate desc`) — the archive is the only transport with a date.
     */
    override suspend fun fetchNew(limit: Int): List<SourceBook> {
        val json = fetcher.getText(archiveSearchUrl("", newestFirst = true, limit = limit))
        return archiveDocsFrom(json).mapNotNull { it.toSourceBook() }.take(limit)
    }

    /**
     * A broad sample of the librivox.org catalogue through its JSON API
     * (English records only — the API has no language filter, so the parse
     * drops the other ~10%). The API has no date ordering; the sample starts
     * at the catalogue head, which is what the source exposes without search.
     */
    override suspend fun fetchCatalog(limit: Int): List<SourceBook> {
        val json = fetcher.getText(
            "https://librivox.org/api/feed/audiobooks/?format=json&limit=$limit&offset=0"
        )
        return apiBooksFrom(json).mapNotNull { it.toSourceBook() }.take(limit)
    }

    /**
     * T2 ships cards; the playable page (chapters/tracks) is spec-45 T3
     * (#491). Until then a page honestly reports nothing playable rather than
     * fabricating streams.
     */
    override suspend fun fetchBookPage(url: String): SourceBookDetail =
        SourceBookDetail("", "", url = url, chapters = emptyList())

    // --- card shapes --------------------------------------------------------

    /** One parsed archive advanced-search document (the mirror card source). */
    private inner class ArchiveDoc(val raw: String) {
        val identifier: String = field(raw, "identifier")
        val title: String = field(raw, "title")
        val creator: String = field(raw, "creator")
        val language: String = field(raw, "language")

        fun toSourceBook(): SourceBook? {
            if (title.isBlank() || identifier.isBlank()) return null
            return SourceBook(
                title = title,
                author = creator,
                url = "https://archive.org/details/$identifier",
                // Archive metadata reports the language code itself ("eng").
                language = LanguageCode.normalize(language).orEmpty(),
                sourceId = sourceId,
                // The archive search response carries no real duration.
                totalDurationSeconds = 0L
            )
        }
    }

    /** One parsed librivox.org API feed record. */
    private inner class ApiBook(val raw: String) {
        val title: String = field(raw, "title")
        val language: String = field(raw, "language")
        val librivoxUrl: String = field(raw, "url_librivox")
        val author: String = firstAuthor(raw)
        val durationSeconds: Long = Regex("\"totaltimesecs\"\\s*:\\s*(\\d+)")
            .find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        /**
         * English records only (the LibriVox start of spec-45). The API
         * reports the language as a word ("English", "German", …); the word
         * is mapped to its BCP-47 tag and normalized — never guessed.
         */
        fun toSourceBook(): SourceBook? {
            if (language != "English") return null
            if (title.isBlank() || librivoxUrl.isBlank()) return null
            return SourceBook(
                title = title,
                author = author,
                url = librivoxUrl,
                language = LanguageCode.normalize(API_LANGUAGE_TAGS[language]).orEmpty(),
                totalDurationSeconds = durationSeconds,
                sourceId = sourceId
            )
        }
    }

    /** The API's word value for English. The filter above only admits it. */
    private fun firstAuthor(raw: String): String {
        val first = Regex("\"first_name\"\\s*:\\s*\"([^\"]*)\"").find(raw)?.groupValues?.get(1).orEmpty()
        val last = Regex("\"last_name\"\\s*:\\s*\"([^\"]*)\"").find(raw)?.groupValues?.get(1).orEmpty()
        return listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")
    }

    // --- JSON extraction (the repo's hand-rolled source-adapter style) ------

    /** The balanced string of every object inside `"key": [ … ]`. */
    private fun objectsIn(json: String, key: String): List<String> {
        val keyIndex = json.indexOf("\"$key\"")
        if (keyIndex < 0) return emptyList()
        val arrayStart = json.indexOf('[', keyIndex)
        if (arrayStart < 0) return emptyList()
        val out = mutableListOf<String>()
        var pos = arrayStart + 1
        while (pos < json.length) {
            val objStart = json.indexOf('{', pos)
            if (objStart < 0) break
            val obj = balancedObject(json, objStart) ?: break
            out += obj
            pos = objStart + obj.length
        }
        return out
    }

    private fun balancedObject(s: String, start: Int): String? {
        var depth = 0
        var inString = false
        for (i in start until s.length) {
            val c = s[i]
            when {
                inString && c == '\\' -> { /* skip escaped char */ }
                inString && c == '"' -> inString = false
                !inString && c == '"' -> inString = true
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** `"key":"value"` — the raw string value ("" for null/number/absent). */
    private fun field(obj: String, key: String): String {
        val keyIndex = obj.indexOf("\"$key\"")
        if (keyIndex < 0) return ""
        val colon = obj.indexOf(':', keyIndex)
        if (colon < 0) return ""
        var i = colon + 1
        while (i < obj.length && obj[i].isWhitespace()) i++
        if (i >= obj.length || obj[i] != '"') return ""
        val sb = StringBuilder()
        i++
        while (i < obj.length) {
            val c = obj[i]
            when {
                c == '\\' && i + 1 < obj.length -> {
                    sb.append(obj[i + 1]); i += 2
                }
                c == '"' -> return sb.toString()
                else -> {
                    sb.append(c); i++
                }
            }
        }
        return ""
    }

    private fun archiveDocsFrom(json: String): List<ArchiveDoc> =
        objectsIn(json, "docs").map(::ArchiveDoc)

    private fun apiBooksFrom(json: String): List<ApiBook> =
        objectsIn(json, "books").map(::ApiBook)

    /** Advanced-search URL over the librivoxaudio mirror collection. */
    private fun archiveSearchUrl(phrase: String, newestFirst: Boolean, limit: Int = DEFAULT_LIMIT): String {
        val baseQuery = "collection:librivoxaudio AND language:eng" +
            if (phrase.isNotBlank()) " AND $phrase" else ""
        val sort = if (newestFirst) "&sort%5B%5D=addeddate+desc" else ""
        return "https://archive.org/advancedsearch.php" +
            "?q=${urlEncode(baseQuery)}" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=language" +
            "&rows=$limit&output=json$sort"
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private companion object {
        const val DEFAULT_LIMIT = 20
        /** The API's language words → BCP-47 tags (only "English" is admitted today). */
        val API_LANGUAGE_TAGS = mapOf(
            "English" to "en",
            "Ukrainian" to "uk",
            "German" to "de",
            "French" to "fr",
            "Spanish" to "es",
            "Italian" to "it",
            "Portuguese" to "pt",
            "Russian" to "ru",
            "Dutch" to "nl",
            "Polish" to "pl",
            "Chinese" to "zh",
            "Japanese" to "ja"
        )
    }
}
