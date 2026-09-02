package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.collections.MiniJson
import com.slukhayka.audiobooks.data.source.SourceBook

/**
 * Spec #462 Implementation Decision 6 (#467) — the pure JSON codec of the
 * persisted feed snapshots (feed_snapshots.cardsJson): source cards
 * ([SourceBook]) and the 4read homepage snapshot (sections + genre nav).
 *
 * Encode is hand-rolled (the ONE tiny writer beside the ONE parser —
 * [MiniJson] stays the shared decoder, the same convention the collections
 * module follows: pure JVM, no org.json stubs). One deliberate shape rule:
 * NULL FIELDS ARE OMITTED, never written as JSON null — the shared minimal
 * parser reads a null literal as a parse failure (a value must be
 * non-null inside its object/array recursion), so a missing key is the
 * snapshot's only "absent" marker (the typed decoders read absent as null).
 *
 * Decode is best-effort and total: malformed or foreign JSON decodes to
 * EMPTY, never throws — a corrupt snapshot is a cache miss, never a broken
 * refresh.
 */
object FeedSnapshotCodec {

    // --- Source cards (новинки / catalogue feeds) -------------------------

    fun encodeBooks(books: List<SourceBook>): String =
        books.joinToString(prefix = "[", separator = ",", postfix = "]") { book ->
            obj(
                "title" to str(book.title),
                "author" to str(book.author),
                "narrator" to str(book.narrator),
                "url" to str(book.url),
                "coverImageUrl" to book.coverImageUrl?.let(::str),
                "seriesTitle" to book.seriesTitle?.let(::str),
                "seriesIndex" to book.seriesIndex?.toString(),
                "genre" to str(book.genre),
                "totalDurationSeconds" to book.totalDurationSeconds.toString(),
                "sourceId" to str(book.sourceId)
            )
        }

    fun decodeBooks(json: String): List<SourceBook> {
        val root = MiniJson.parse(json) as? List<*> ?: return emptyList()
        return root.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val title = map["title"] as? String ?: return@mapNotNull null
            val url = map["url"] as? String ?: return@mapNotNull null
            val sourceId = map["sourceId"] as? String ?: return@mapNotNull null
            SourceBook(
                title = title,
                author = map["author"] as? String ?: "",
                narrator = map["narrator"] as? String ?: "",
                url = url,
                coverImageUrl = map["coverImageUrl"] as? String,
                seriesTitle = map["seriesTitle"] as? String,
                seriesIndex = (map["seriesIndex"] as? Double)?.toInt(),
                genre = map["genre"] as? String ?: "",
                totalDurationSeconds = (map["totalDurationSeconds"] as? Double)?.toLong() ?: 0L,
                sourceId = sourceId
            )
        }
    }

    // --- 4read homepage snapshot (sections + genre nav) -------------------

    /** One persisted homepage snapshot: the sections plus the sidebar genre nav. */
    data class HomepageSnapshot(
        val sections: List<CatalogSection>,
        val genres: List<CatalogGenre>
    )

    fun encodeHomepage(snapshot: HomepageSnapshot): String = obj(
        "sections" to snapshot.sections.joinToString(prefix = "[", separator = ",", postfix = "]") { section ->
            obj(
                "title" to str(section.title),
                "id" to str(section.id.name),
                "books" to encodeCatalogBooks(section.books),
                "series" to section.series.joinToString(prefix = "[", separator = ",", postfix = "]") { series ->
                    obj(
                        "title" to str(series.title),
                        "url" to str(series.url),
                        "coverImageUrl" to series.coverImageUrl?.let(::str)
                    )
                }
            )
        },
        "genres" to snapshot.genres.joinToString(prefix = "[", separator = ",", postfix = "]") { genre ->
            obj(
                "title" to str(genre.title),
                "url" to str(genre.url)
            )
        }
    )

    fun decodeHomepage(json: String): HomepageSnapshot? {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        val sections = (root["sections"] as? List<*>).orEmpty().mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val id = (map["id"] as? String)?.let { id ->
                runCatching { CatalogSectionId.valueOf(id) }.getOrNull()
            } ?: return@mapNotNull null
            CatalogSection(
                title = map["title"] as? String ?: "",
                books = decodeCatalogBooks(map["books"]),
                series = (map["series"] as? List<*>).orEmpty().mapNotNull { s ->
                    val sm = s as? Map<*, *> ?: return@mapNotNull null
                    val seriesTitle = sm["title"] as? String ?: return@mapNotNull null
                    CatalogSeries(
                        title = seriesTitle,
                        url = sm["url"] as? String ?: "",
                        coverImageUrl = sm["coverImageUrl"] as? String
                    )
                },
                id = id
            )
        }
        val genres = (root["genres"] as? List<*>).orEmpty().mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val title = map["title"] as? String ?: return@mapNotNull null
            CatalogGenre(title = title, url = map["url"] as? String ?: "")
        }
        return HomepageSnapshot(sections, genres)
    }

    private fun encodeCatalogBooks(books: List<CatalogBook>): String =
        books.joinToString(prefix = "[", separator = ",", postfix = "]") { book ->
            obj(
                "id" to str(book.id),
                "title" to str(book.title),
                "author" to str(book.author),
                "url" to str(book.url),
                "coverImageUrl" to book.coverImageUrl?.let(::str),
                "seriesTitle" to book.seriesTitle?.let(::str),
                "seriesUrl" to book.seriesUrl?.let(::str),
                "seriesIndex" to book.seriesIndex?.toString(),
                "totalDurationSeconds" to book.totalDurationSeconds.toString(),
                "narrator" to str(book.narrator)
            )
        }

    private fun decodeCatalogBooks(raw: Any?): List<CatalogBook> =
        (raw as? List<*>).orEmpty().mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val id = map["id"] as? String ?: return@mapNotNull null
            val title = map["title"] as? String ?: return@mapNotNull null
            val url = map["url"] as? String ?: return@mapNotNull null
            CatalogBook(
                id = id,
                title = title,
                author = map["author"] as? String ?: "",
                url = url,
                coverImageUrl = map["coverImageUrl"] as? String,
                seriesTitle = map["seriesTitle"] as? String,
                seriesUrl = map["seriesUrl"] as? String,
                seriesIndex = (map["seriesIndex"] as? Double)?.toInt(),
                totalDurationSeconds = (map["totalDurationSeconds"] as? Double)?.toLong() ?: 0L,
                narrator = map["narrator"] as? String ?: ""
            )
        }

    // --- one tiny writer (beside the ONE shared parser) -------------------

    /** One JSON object: only the present fields are written (see the class doc). */
    private fun obj(vararg fields: Pair<String, String?>): String =
        fields.mapNotNull { (name, json) -> json?.let { "\"$name\":$it" } }
            .joinToString(prefix = "{", separator = ",", postfix = "}")

    private fun str(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
