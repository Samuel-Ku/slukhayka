package com.slukhayka.audiobooks.data.recommend

import com.slukhayka.audiobooks.data.LanguageCode
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.decodeEntities

/** Conservative bibliographic projection of real, provenance-bearing source records. */
object RecommendationEvalCatalog {
    data class Catalog(
        val works: List<RecommendationEngine.Candidate>,
        val sourceUrls: Map<String, List<String>>,
        val rawRecords: Int,
        val duplicateEditions: Int,
        val excludedRussian: Int,
        val missingIdentity: Int
    ) {
        /** Extra cardinality guard: author aliases cannot inflate this count. */
        val distinctTitles: Int get() = works.map { normalizedTitle(it.title) }.distinct().size
    }

    fun fromRecords(records: List<Map<*, *>>, aliases: RecommendationEvalIdentityAliases? = null): Catalog {
        val ids = records.map { it["identifier"] as? String ?: error("Missing source identifier") }
        require(ids.distinct().size == ids.size) { "A repeated source record cannot inflate the catalog" }
        var excludedRussian = 0
        var missingIdentity = 0
        val grouped = linkedMapOf<String, MutableList<Pair<RecommendationEngine.Candidate, String>>>()
        for (raw in records.sortedBy { it["identifier"].toString() }) {
            val languages = strings(raw["language"])
            if (languages.any { LanguageCode.normalize(it) == "ru" }) { excludedRussian++; continue }
            val author = strings(raw["creator"]).joinToString("; ").let(::decodeEntities).trim()
            val asserted = aliases?.workFor(raw["identifier"].toString())
            val title = cleanTitle(raw["title"] as? String ?: "", author)
            if (title.isBlank() || author.isBlank() || author.lowercase() in UNKNOWN_AUTHORS) {
                missingIdentity++; continue
            }
            // Bibliographic word order varies (Austen, Jane / Jane Austen).
            // Dates are creator metadata, not a different authored Work.
            val normalized = normalizeAuthor(asserted?.author ?: author)
            if (normalized in UNKNOWN_AUTHORS) { missingIdentity++; continue }
            val tokens = normalized.split(' ').filter { it.isNotBlank() && it !in HONORIFICS }
            val authorKey = tokens.sorted().joinToString(" ")
            val key = MergeKey.keyFor(normalizedTitle(asserted?.title ?: title), authorKey)
            if (key.isBlank()) { missingIdentity++; continue }
            val genres = strings(raw["subject"]).filter { it.lowercase() !in TECHNICAL_TAGS }.distinct().joinToString("; ")
            val work = RecommendationEngine.Candidate(key, title, author, genres,
                description = strings(raw["description"]).joinToString("\n"))
            grouped.getOrPut(key) { mutableListOf() } += work to "https://archive.org/details/${raw["identifier"]}"
        }
        // Within the same authored title, a supplied middle-name expansion
        // must not manufacture another Work. This is a conservative identity
        // lower bound, confined to this frozen evaluation corpus.
        val merged = linkedMapOf<String, MutableList<Pair<RecommendationEngine.Candidate, String>>>()
        for ((_, authors) in grouped.entries.groupBy { it.key.substringBefore('|') }) {
            val sameTitleKeys = mutableListOf<String>()
            for (entry in authors.sortedWith(compareByDescending<Map.Entry<String, MutableList<Pair<RecommendationEngine.Candidate, String>>>> { it.key.substringAfter('|').split(' ').sumOf { word -> if (word.length > 1) word.length else 0 } }.thenBy { it.key })) {
                val tokens = entry.key.substringAfter('|').split(' ')
                val matches = sameTitleKeys.filter { key ->
                    key.substringBefore('|') == entry.key.substringBefore('|') &&
                        ';' !in entry.value.first().first.author &&
                        ';' !in merged.getValue(key).first().first.author &&
                        compatibleAuthor(tokens, key.substringAfter('|').split(' '))
                }
                if (matches.size > 1) {
                    missingIdentity += entry.value.size
                    continue
                }
                val target = matches.singleOrNull() ?: entry.key
                if (target !in sameTitleKeys) sameTitleKeys += target
                merged.getOrPut(target) { mutableListOf() }.addAll(entry.value)
            }
        }
        val works = merged.map { (id, recordings) ->
            val asserted = recordings.mapNotNull { aliases?.workFor(it.second.substringAfterLast('/')) }.distinct()
            require(asserted.size <= 1) { "Distinct asserted Works collided" }
            val representative = asserted.singleOrNull()?.representativeIdentifier
            val recording = if (representative == null) recordings.minBy { it.second }
                else recordings.single { it.second == "https://archive.org/details/$representative" }
            recording.first.copy(id = id)
        }.sortedBy { it.id }
        val urls = merged.mapValues { (_, recordings) -> recordings.map { it.second }.sorted() }
        return Catalog(works, urls, records.size, merged.values.sumOf { it.size - 1 }, excludedRussian, missingIdentity)
    }

    internal fun sameKnownAuthor(candidate: String, canonical: String): Boolean {
        fun tokens(name: String) = normalizeAuthor(name).split(' ')
            .filter { it.isNotBlank() && it !in HONORIFICS }
        if (';' in candidate || ';' in canonical || normalizeAuthor(candidate) in UNKNOWN_AUTHORS ||
            normalizeAuthor(canonical) in UNKNOWN_AUTHORS) return false
        val supplied = tokens(candidate)
        val known = tokens(canonical)
        return compatibleAuthor(supplied, known) || compatibleAuthor(known, supplied)
    }

    private fun compatibleAuthor(candidate: List<String>, expanded: List<String>): Boolean {
        if (candidate.size < 2 || candidate.size > expanded.size) return false
        if (candidate.none { it.length > 1 && it in expanded }) return false
        val available = expanded.toMutableList()
        for (token in candidate.filter { it.length > 1 }) {
            if (!available.remove(token)) return false
        }
        for (initial in candidate.filter { it.length == 1 }) {
            val index = available.indexOfFirst { it.startsWith(initial) }
            if (index < 0) return false
            available.removeAt(index)
        }
        return true
    }

    fun normalizedTitle(raw: String): String {
        val title = cleanTitle(raw).replace('-', ' ')
        // This known stage-play title names a different Work from the 1911
        // novel. Subtitle punctuation must not erase that distinction.
        val normalized = if (BARRIE_PLAY_TITLE.containsMatchIn(title)) MergeKey.normalizePerson(title)
            else MergeKey.normalizeTitle(title)
        return normalized.replace(Regex("^(the|an|a) "), "")
    }

    fun cleanTitle(raw: String, declaredAuthor: String? = null): String {
        var title = decodeEntities(raw).trim()
        while (true) {
            var clean = RECORDING_SUFFIX.replace(title, "").trim()
            // Both punctuation forms are present in the frozen Moby Dick
            // recordings. This is an alternate-title separator, not a new Work.
            val alternate = ALTERNATE_TITLE.find(clean)
            if (alternate != null && clean.substring(0, alternate.range.first).replace('-', ' ')
                    .lowercase(java.util.Locale.ROOT) in KNOWN_ALTERNATE_TITLE_HEADS) {
                clean = clean.substring(0, alternate.range.first).trim()
            }
            val suffix = DECLARED_AUTHOR_SUFFIX.find(clean)
            if (suffix != null && !declaredAuthor.isNullOrBlank()) {
                fun words(name: String) = normalizeAuthor(name).split(' ')
                    .filter { it.isNotBlank() && it !in HONORIFICS }.sorted()
                val supplied = words(declaredAuthor)
                if (supplied.isNotEmpty() && words(suffix.groupValues[1]) == supplied) {
                    clean = clean.substring(0, suffix.range.first).trim()
                }
            }
            val article = Regex(",\\s*(The|An|A)$", RegexOption.IGNORE_CASE).find(clean)
            if (article != null) clean = article.groupValues[1] + " " + clean.substring(0, article.range.first)
            if (clean == title) return clean
            title = clean
        }
    }

    private fun normalizeAuthor(author: String): String {
        val person = author.replace(Regex("\\b\\d{3,4}\\s*[-–]\\s*\\d{0,4}\\b"), "")
        return MergeKey.normalizePerson(person.replace(Regex("[.,]"), " "))
            .replace(Regex("\\b(le|de|du|la)\\s+(?=\\p{L})"), "$1")
    }

    private fun strings(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is List<*> -> value.filterIsInstance<String>()
        else -> emptyList()
    }
    private val KNOWN_ALTERNATE_TITLE_HEADS = setOf("moby dick", "frankenstein")
    private val BARRIE_PLAY_TITLE = Regex("^peter pan\\s*[,;:]\\s*or\\b.*\\bboy\\b.*\\bgrow\\b", RegexOption.IGNORE_CASE)
    private val ALTERNATE_TITLE = Regex("[,;]\\s*or\\b(?:\\s*,\\s*|\\s+).+$", RegexOption.IGNORE_CASE)
    private val DECLARED_AUTHOR_SUFFIX = Regex(",\\s*by\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val RECORDING_SUFFIX = Regex(
        "\\s*\\((?:version\\s*\\d+[^)]*|edition\\s+\\d{4}|solo(?:\\s+version)?|dramatic\\s+(?:reading|recording)|unabridged)\\)\\s*$|,?\\s+version\\s*\\d+\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val HONORIFICS = setOf("sir", "mr", "mrs", "dr", "count")
    private val UNKNOWN_AUTHORS = setOf("anonymous", "unknown", "various", "various authors", "librivox", "librivox volunteers", "unknown author", "author unknown", "anonymous author", "unknown authors", "various writers", "multiple authors", "anon", "anonimo", "anónimo", "anonyme", "unbekannt", "desconocido")
    private val TECHNICAL_TAGS = setOf("librivox", "audiobooks", "audiobook", "audio books", "audio book", "audio", "literature")
}
