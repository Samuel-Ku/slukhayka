package com.slukhayka.audiobooks.data.merge

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import org.json.JSONArray

/** Frozen identity predicates, not saved row IDs: every operation read discovers new dependants. */
internal class ManualWorkMergeOperationScope private constructor(fields: List<List<String>>) {
    private val values = fields.map { immutableMergeList(it) }
    val workIds get() = values[0]
    val workKeys get() = values[1]
    val bookIds get() = values[2]
    val editionIds get() = values[3]
    val sourceIds get() = values[4]
    val providerIds get() = values[5]
    val sourceUrls get() = values[6]
    val seriesIds get() = values[7]
    val universeIds get() = values[8]
    val authorIds get() = values[9]
    val genreIds get() = values[10]

    fun encode(): JSONArray = JSONArray().put(DOMAIN).put(1).also { result ->
        values.forEach { result.put(JSONArray(it)) }
        require(result.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Operation scope byte bound" }
    }

    fun digest(): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(value: String) {
                require(Charsets.UTF_8.newEncoder().canEncode(value)) { "Malformed operation identity" }
                val encoded = value.toByteArray(Charsets.UTF_8)
                out.writeInt(encoded.size); out.write(encoded)
            }
            text("$DOMAIN/v1")
            for ((index, list) in values.withIndex()) {
                text(FIELDS[index]); out.writeInt(list.size); list.forEach(::text)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** A persisted descriptor cannot omit the anchors recoverable from the raw journal. */
    fun validateOriginals(
        roots: List<String>, canonical: String, books: List<String>, protectedEditions: List<String>,
        before: ManualWorkMergeJournal.Image, journal: ManualWorkMergeJournal,
    ) {
        require(workIds == canonicalize(roots + canonical)) { "Operation scope participants disagree" }
        require(bookIds == canonicalize(books)) { "Operation scope library anchors disagree" }
        require(editionIds.containsAll(protectedEditions)) { "Operation scope Edition anchors disagree" }
        fun text(row: ManualWorkMergeJournal.Row, column: String) = journal.text(row, column, before.encoding)
        require(workKeys.containsAll(roots + canonical + before.rows.getValue("works").map { text(it, "mergeKey") }))
        require(seriesIds.containsAll(before.rows.getValue("series_members").map { text(it, "seriesId") } +
            before.rows.getValue("work_facet_series").map { text(it, "seriesId") }))
        require(authorIds.containsAll(before.rows.getValue("work_facets").mapNotNull {
            journal.nullableText(it, "canonicalAuthorId", before.encoding)
        }))
        require(genreIds.containsAll(before.rows.getValue("genre_assertions").map { text(it, "genreId") } +
            before.rows.getValue("work_genres").map { text(it, "genreId") }))
        require(providerIds.containsAll(before.rows.getValue("work_sources").map { text(it, "sourceId") }))
        require(sourceUrls.containsAll(before.rows.getValue("work_sources").map { text(it, "sourceUrl") }.filter { it.isNotBlank() }))
    }

    companion object {
        private const val DOMAIN = "ManualWorkMergeOperationScope"
        private const val MAX_BYTES = 384 * 1024
        private const val MAX_IDENTITIES = 10_000
        private val FIELDS = listOf("workIds", "workKeys", "bookIds", "editionIds", "sourceIds", "providerIds", "sourceUrls", "seriesIds", "universeIds", "authorIds", "genreIds")

        private fun canonicalize(values: List<String>): List<String> = values.distinct().sortedWith(::compareMergeUtf8)
        private fun create(fields: List<List<String>>): ManualWorkMergeOperationScope {
            require(fields.size == FIELDS.size && fields.sumOf { it.size } <= MAX_IDENTITIES) { "Operation scope identity bound" }
            for (list in fields) {
                require(list == canonicalize(list) && list.all { it.isNotBlank() && Charsets.UTF_8.newEncoder().canEncode(it) }) {
                    "Operation scope identities must be unique, ordered and well formed"
                }
            }
            require(fields[0].isNotEmpty() && fields[1].isNotEmpty())
            return ManualWorkMergeOperationScope(fields).also { it.encode() }
        }

        fun capture(snapshot: ManualWorkMergeSnapshot, canonical: String): ManualWorkMergeOperationScope {
            val library = snapshot.library; val catalog = snapshot.catalog
            return create(listOf(
                snapshot.rootWorkIds + canonical,
                snapshot.rootWorkIds + library.works.map { it.mergeKey }.filter { it.isNotBlank() } + canonical,
                library.entries.map { it.id }.distinct(),
                library.editions.map { it.id } + catalog.editionFacets.map { it.editionId },
                library.sources.map { it.id },
                catalog.workSources.map { it.sourceId } + catalog.genreStates.map { it.sourceId } +
                    catalog.genreAssertions.map { it.sourceId } + catalog.popularity.map { it.sourceId },
                (catalog.workSources.map { it.sourceUrl } + library.sources.map { it.url } + library.books.map { it.sourceUrl }).filter { it.isNotBlank() },
                catalog.series.map { it.id }, catalog.universes.map { it.id },
                catalog.authors.map { it.id }, catalog.genres.map { it.id },
            ).map(::canonicalize))
        }

        fun decode(array: JSONArray): ManualWorkMergeOperationScope {
            require(array.length() == FIELDS.size + 2 && array.get(0) == DOMAIN && array.get(1) == 1) { "Unknown operation scope" }
            require(array.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Operation scope byte bound" }
            return create(FIELDS.indices.map { index ->
                val list = array.get(index + 2) as? JSONArray ?: error("Invalid operation scope list")
                require(list.length() <= MAX_IDENTITIES)
                (0 until list.length()).map { list.get(it) as? String ?: error("Invalid operation scope identity") }
            })
        }
    }
}
