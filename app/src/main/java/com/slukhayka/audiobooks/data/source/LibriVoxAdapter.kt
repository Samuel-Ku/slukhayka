package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.LanguageCode
import com.slukhayka.audiobooks.data.collections.MiniJson

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
     * Spec-45 T3 (#491) — a playable LibriVox book from its archive.org
     * mirror page: the metadata API (`archive.org/metadata/<identifier>`)
     * serves the ordered VBR MP3 sections with their id3 titles, track
     * numbers and durations. The archive mirror is the SAME sourceId, so the
     * materialised chapters/tracks need no new playback or download
     * special-casing. A URL that is not an archive details page (or a
     * response that carries nothing) reports nothing playable — never a
     * fabricated stream.
     */
    override suspend fun fetchBookPage(url: String): SourceBookDetail {
        val identifier = identifierOf(url) ?: return SourceBookDetail("", "", url = url, chapters = emptyList())
        val json = fetcher.getText("https://archive.org/metadata/$identifier")
        if (json.isBlank()) return SourceBookDetail("", "", url = url, chapters = emptyList())
        return metadataDetail(json, url)
    }

    // --- card shapes --------------------------------------------------------

    /**
     * The archive identifier of a `url_zip_file` record (the api's only
     * archive.org handle): `…/compress/<identifier>/…`. Cards carry the
     * archive details page, so [fetchBookPage] serves ONE page shape for both
     * transports. Read from the DECODED field value — [MiniJson] unescapes
     * every slash (`https:\/\/…`) before the field read.
     */
    private fun archiveIdentifierOf(raw: Map<*, *>): String {
        val zipUrl = string(raw, "url_zip_file")
        if (zipUrl.isBlank()) return ""
        return zipUrl.substringAfter("archive.org/compress/", "").substringBefore('/')
    }

    /** The API's first author record — `first_name last_name`. */
    private fun firstAuthor(raw: Map<*, *>): String {
        val author = (raw["authors"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return ""
        val first = string(author, "first_name")
        val last = string(author, "last_name")
        return listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** The archive identifier when [url] is an `archive.org/details/` page. */
    private fun identifierOf(url: String): String? =
        url.substringAfter("archive.org/details/", "").takeIf { it.isNotBlank() }
            ?.substringBefore('?')

    /** One parsed archive advanced-search document (the mirror card source). */
    private inner class ArchiveDoc(val raw: Map<*, *>) {
        val identifier: String = string(raw, "identifier")
        val title: String = string(raw, "title")
        val creator: String = string(raw, "creator")
        val language: String = string(raw, "language")

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
    private inner class ApiBook(val raw: Map<*, *>) {
        val title: String = string(raw, "title")
        val language: String = string(raw, "language")
        val librivoxUrl: String = string(raw, "url_librivox")
        val author: String = firstAuthor(raw)
        val durationSeconds: Long = (raw["totaltimesecs"] as? Double)?.toLong() ?: 0L
        val archiveIdentifier: String = archiveIdentifierOf(raw)

        /**
         * English records only (the LibriVox start of spec-45). The API
         * reports the language as a word ("English", "German", …); the word
         * is mapped to its BCP-47 tag and normalized — never guessed. Cards
         * carry the archive.org mirror page (T3 #491 plays from it), the
         * identifier the api embeds in `url_zip_file`.
         */
        fun toSourceBook(): SourceBook? {
            if (language != "English") return null
            if (title.isBlank() || archiveIdentifier.isBlank()) return null
            return SourceBook(
                title = title,
                author = author,
                url = "https://archive.org/details/$archiveIdentifier",
                language = LanguageCode.normalize(API_LANGUAGE_TAGS[language]).orEmpty(),
                totalDurationSeconds = durationSeconds,
                sourceId = sourceId
            )
        }
    }

    // --- book page (T3 #491) ------------------------------------------------

    /** One audio file of the metadata response (a book section). */
    private inner class MetadataFile(val raw: Map<*, *>) {
        val format: String = string(raw, "format")
        val name: String = string(raw, "name")
        val title: String = string(raw, "title")
        val track: String = string(raw, "track")
        val length: String = string(raw, "length")
    }

    /**
     * A full book detail from the archive metadata response. The ordered
     * chapters are the VBR MP3s (the archive also stores 64/128 Kbps
     * duplicates and covers — never chapters); their id3 [MetadataFile.title]
     * is the real section name, [MetadataFile.track] the order and
     * [MetadataFile.length] the clock duration.
     */
    private fun metadataDetail(json: String, url: String): SourceBookDetail {
        val identifier = identifierOf(url) ?: return SourceBookDetail("", "", url = url, chapters = emptyList())
        val root = root(json) ?: return SourceBookDetail("", "", url = url, chapters = emptyList())
        val metaObj = root["metadata"] as? Map<*, *>
        val title = metaObj?.let { string(it, "title") }.orEmpty()
        val author = metaObj?.let { string(it, "creator") }.orEmpty()
        val language = metaObj?.let { string(it, "language") }.orEmpty()
        val description = metaObj?.let { string(it, "description") }.orEmpty()
        // Spec-45 (#405) R3 (#510): the archive item carries no narrator
        // field — the CONFIRMED claim lives in the standard LibriVox
        // description phrase ("Read in English by <name>"). Absent claim →
        // no name (ADR-0014: never fabricated, the author never becomes the
        // narrator).
        val decodedDescription = decodeEntities(stripTags(description)).trim()
        val narrator = narratorFromDescription(decodedDescription)
        val chapters = (root["files"] as? List<*>).orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .map(::MetadataFile)
            .filter { it.format == "VBR MP3" && it.name.isNotBlank() }
            .sortedWith(compareBy({ it.track.toIntOrNull() ?: Int.MAX_VALUE }, { it.name }))
            .map { file ->
                SourceChapter(
                    title = file.title.ifBlank { file.name.removeSuffix(".mp3") },
                    streamUrl = "https://archive.org/download/$identifier/${urlEncode(file.name)}",
                    durationSeconds = parseDurationSeconds(file.length) ?: 0L
                )
            }
        return SourceBookDetail(
            title = title,
            author = author,
            url = url,
            coverImageUrl = "https://archive.org/download/$identifier/__ia_thumb.jpg",
            language = LanguageCode.normalize(language).orEmpty(),
            narrator = narrator,
            chapters = chapters,
            description = decodedDescription,
            totalDurationSeconds = null
        )
    }

    // --- JSON extraction (the repo's ONE pure decoder, MiniJson) ----------
    // Spec-45 (#405) R2 (#509): catalogue, search and the metadata page share
    // the repo's single recursive-descent JSON parser ([MiniJson]) — escaped
    // quotes, backslashes, \uXXXX escapes and braces inside strings are
    // decoded ONCE, correctly; a malformed response parses to null and every
    // read degrades to EMPTY (never a crash, never fabricated tracks).

    /** The decoded root object, or null for any malformed/blank response. */
    private fun root(json: String): Map<*, *>? =
        MiniJson.parse(json) as? Map<*, *>

    /** One string field of a decoded object ("" for absent/non-string). */
    private fun string(obj: Map<*, *>, key: String): String =
        (obj[key] as? String).orEmpty()

    /** The archive search envelope nests the docs under `response.docs`. */
    private fun archiveDocsFrom(json: String): List<ArchiveDoc> =
        ((root(json)?.get("response") as? Map<*, *>)?.get("docs") as? List<*>)
            .orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .map(::ArchiveDoc)

    private fun apiBooksFrom(json: String): List<ApiBook> =
        (root(json)?.get("books") as? List<*>)
            .orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .map(::ApiBook)

    /**
     * Spec-45 (#405) R3 (#510) — the confirmed narrator claim from the
     * standard LibriVox description phrases; "" when none matches (a name is
     * never invented and the author's text never becomes the narrator).
     */
    private fun narratorFromDescription(description: String): String {
        for (pattern in NARRATOR_PATTERNS) {
            pattern.find(description)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return ""
    }

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
        /** R3 (#510): LibriVox's standard narrator-claim phrases. */
        val NARRATOR_PATTERNS = listOf(
            Regex("""[Rr]ead in English by ([^.<>]+)"""),
            Regex("""[Rr]ead by ([^.<>]+)"""),
            Regex("""[Nn]arrated by ([^.<>]+)""")
        )

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
