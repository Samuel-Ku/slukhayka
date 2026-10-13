package com.slukhayka.audiobooks.data.merge

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import com.slukhayka.audiobooks.data.db.AudiobookDao
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext

/**
 * Room55 exported-column change detector, called only inside readManualWorkMergeRows.
 * Uses actual SQLite classes and binary64 values; this digest is not an Undo journal.
 * Retained globals and hidden SQLite/FTS storage internals are outside its contract.
 */
internal object ManualWorkMergeStorageFingerprint {
    private const val KEY_BATCH_SIZE = 128
    private const val HASH_CHUNK_SIZE = 262_144

    private data class Table(val name: String, val columns: List<String>, val keys: List<String>) {
        // One full payload per column: do not duplicate large TEXT/BLOB values in CursorWindow.
        val projection: String = columns.flatMap { name ->
            val column = ManualWorkMergeStorageFingerprint.quoted(name)
            listOf("typeof($column)", "CASE WHEN typeof($column) = 'text' THEN CAST($column AS BLOB) ELSE $column END")
        }.joinToString(", ")
        val keyIndices: List<Int> = keys.map { columns.indexOf(it).also { index -> check(index >= 0) } }
    }

    // Original 36 carrier layouts are unchanged by the three-table Room55 expansion.
    // Commands must separately capture journal/redirect/protection decision evidence;
    // that writer preparation is not implemented in the availability baseline.
    private val tables = listOf(
        Table(
            "audiobooks",
            listOf(
                "id", "title", "author", "narrator", "description", "coverDrawableRes", "coverImageUrl",
                "genre", "sourceUrl", "isDownloaded", "totalDurationSeconds", "totalChapters", "rating",
                "sourceTreeUri"
            ),
            listOf("id"),
        ),
        Table(
            "author_aliases",
            listOf(
                "authorId", "normalizedAlias", "rawAlias", "sourceId", "observedAt"
            ),
            listOf("authorId", "normalizedAlias", "sourceId"),
        ),
        Table(
            "author_facets",
            listOf(
                "id", "displayName", "normalizedName", "updatedAt"
            ),
            listOf("id"),
        ),
        Table(
            "bookmarks",
            listOf(
                "id", "bookId", "editionId", "chapterIndex", "chapterTitle", "timestampSeconds", "note",
                "createdAt"
            ),
            listOf("id"),
        ),
        Table(
            "chapters",
            listOf(
                "id", "bookId", "chapterIndex", "title", "durationSeconds", "editionId"
            ),
            listOf("id"),
        ),
        Table(
            "corrections",
            listOf(
                "mergeKey", "kind", "value", "origin", "updatedAt", "updatedBy"
            ),
            listOf("mergeKey", "kind", "value"),
        ),
        Table(
            "edition_facets",
            listOf(
                "editionId", "workId", "narratorId", "language", "durationSeconds", "durationBucketId",
                "chapterCount", "isAbridged", "availabilityAvailable", "availabilityObservedAtMillis",
                "availabilityTtlSeconds", "updatedAt"
            ),
            listOf("editionId"),
        ),
        Table(
            "edition_settings",
            listOf(
                "bookId", "sourceKey", "rewindSeconds", "sleepTimerDefaultSeconds", "volumeBoostEnabled",
                "silenceSkipEnabled", "updatedAt"
            ),
            listOf("bookId", "sourceKey"),
        ),
        Table(
            "editions",
            listOf(
                "id", "workId", "language", "narrator", "totalChapters", "totalDurationSeconds", "addedAt"
            ),
            listOf("id"),
        ),
        Table(
            "embedding_vectors",
            listOf(
                "workId", "textHash", "vector", "updatedAt"
            ),
            listOf("workId"),
        ),
        Table(
            "feed_snapshots",
            listOf(
                "sourceId", "feedKey", "pageCursor", "fetchedAt", "cardsJson"
            ),
            listOf("sourceId", "feedKey", "pageCursor"),
        ),
        Table(
            "genre_assertion_states",
            listOf(
                "workId", "sourceId", "documentUpdatedAt", "provenance"
            ),
            listOf("workId", "sourceId"),
        ),
        Table(
            "genre_assertions",
            listOf(
                "id", "assertionId", "workId", "genreId", "rawText", "sourceId", "observedAt"
            ),
            listOf("id"),
        ),
        Table(
            "genre_facets",
            listOf(
                "id", "displayName", "normalizedName"
            ),
            listOf("id"),
        ),
        Table(
            "library_entries",
            listOf(
                "id", "workId", "origin", "isFavorite", "createdAt", "downloadProgress", "downloadState"
            ),
            listOf("id"),
        ),
        Table(
            "listener_collection_items",
            listOf(
                "collectionId", "bookId", "reason", "addedAt"
            ),
            listOf("collectionId", "bookId"),
        ),
        Table(
            "listener_collections",
            listOf(
                "id", "title", "description", "createdAt", "sourceDocumentId", "sourceTitle", "sourcePseudonym",
                "snapshotAt"
            ),
            listOf("id"),
        ),
        Table(
            "playback_events",
            listOf(
                "id", "bookId", "sourceKey", "kind", "chapterIndex", "positionSeconds", "fromPositionSeconds",
                "timestamp", "deviceId"
            ),
            listOf("id"),
        ),
        Table(
            "playback_failures",
            listOf(
                "id", "timestamp", "bookId", "chapterIndex", "errorCodeName", "streamUrl", "audioEngineMode",
                "category"
            ),
            listOf("id"),
        ),
        Table(
            "playback_progress",
            listOf(
                "editionId", "bookId", "currentChapterIndex", "currentPositionSeconds", "lastListenedAt",
                "isCompleted", "lastPausedAtEpochMs", "preferredSpeed"
            ),
            listOf("editionId"),
        ),
        Table(
            "popularity_assertions",
            listOf(
                "id", "kind", "mergeKey", "rawValue", "sourceId", "observedAt"
            ),
            listOf("id"),
        ),
        Table(
            "readthroughs",
            listOf(
                "id", "libraryEntryId", "workId", "format", "state", "startedAt", "finishedAt", "editionId",
                "unit", "unitValue", "journalJson"
            ),
            listOf("id"),
        ),
        Table(
            "recommendation_preferences",
            listOf(
                "kind", "targetKey", "sourceWorkId", "createdAt"
            ),
            listOf("kind", "targetKey"),
        ),
        Table(
            "series",
            listOf(
                "id", "title", "url", "universeId", "positionInUniverse", "publicationYear"
            ),
            listOf("id"),
        ),
        Table(
            "series_members",
            listOf(
                "workId", "seriesId", "position", "resolvedAt"
            ),
            listOf("workId", "seriesId"),
        ),
        Table(
            "source_tracks",
            listOf(
                "id", "sourceId", "trackIndex", "url", "localFilePath", "contentHash", "isDownloaded"
            ),
            listOf("id"),
        ),
        Table(
            "sources",
            listOf(
                "id", "bookId", "editionId", "type", "url", "streamOnly", "addedAt", "lastScanFingerprint"
            ),
            listOf("id"),
        ),
        Table(
            "submission_states",
            listOf(
                "sourceId", "url", "bookId", "metadataJson", "channelId", "state", "reason", "createdAt",
                "updatedAt"
            ),
            listOf("sourceId"),
        ),
        Table(
            "tombstones",
            listOf(
                "bookId", "deletedAt"
            ),
            listOf("bookId"),
        ),
        Table(
            "universes",
            listOf(
                "id", "name"
            ),
            listOf("id"),
        ),
        Table(
            "work_facet_series",
            listOf(
                "workId", "seriesId"
            ),
            listOf("workId", "seriesId"),
        ),
        Table(
            "work_facets",
            listOf(
                "workId", "canonicalAuthorId", "updatedAt"
            ),
            listOf("workId"),
        ),
        Table(
            "work_genres",
            listOf(
                "workId", "genreId", "sourceId"
            ),
            listOf("workId", "genreId", "sourceId"),
        ),
        Table(
            "work_sources",
            listOf(
                "id", "workId", "sourceId", "sourceUrl", "streamOnly", "coverImageUrl", "durationSeconds",
                "addedAt"
            ),
            listOf("id"),
        ),
        Table(
            "works",
            listOf(
                "id", "mergeKey", "title", "author", "seriesTitle", "seriesUrl", "seriesIndex", "coverImageUrl",
                "addedAt"
            ),
            listOf("id"),
        ),
        Table(
            "works_fts",
            listOf(
                "workId", "title", "author", "series", "narrator"
            ),
            listOf(),
        ),
    )
    private val excluded = listOf("achievement_counters", "achievement_facts", "achievements", "friendship_states", "hidden_reviewers", "listening_stats", "person_bookmarks", "playback_sessions", "social_blocks")

    suspend fun of(
        dao: AudiobookDao,
        roots: List<String>,
        library: ManualWorkMergeLibraryRows,
        catalog: ManualWorkMergeCatalogRows,
        personal: ManualWorkMergePersonalRows,
    ): String {
        val context = currentCoroutineContext()
        context.ensureActive()
        requireRoom55(dao)
        context.ensureActive()
        val encoding = textEncoding(dao)
        context.ensureActive()
        val scopedKeys: Map<String, List<List<Any>>> = mapOf(
            "audiobooks" to library.books.map { listOf(it.id) },
            "author_aliases" to catalog.authorAliases.map { listOf(it.authorId, it.normalizedAlias, it.sourceId) },
            "author_facets" to catalog.authors.map { listOf(it.id) },
            "bookmarks" to library.bookmarks.map { listOf(it.id) },
            "chapters" to library.chapters.map { listOf(it.id) },
            "corrections" to personal.corrections.map { listOf(it.mergeKey, it.kind, it.value) },
            "edition_facets" to catalog.editionFacets.map { listOf(it.editionId) },
            "edition_settings" to library.settings.map { listOf(it.bookId, it.sourceKey) },
            "editions" to library.editions.map { listOf(it.id) },
            "embedding_vectors" to catalog.vectors.map { listOf(it.workId) },
            "feed_snapshots" to personal.feedSnapshots.map { listOf(it.sourceId, it.feedKey, it.pageCursor) },
            "genre_assertion_states" to catalog.genreStates.map { listOf(it.workId, it.sourceId) },
            "genre_assertions" to catalog.genreAssertions.map { listOf(it.id) },
            "genre_facets" to catalog.genres.map { listOf(it.id) },
            "library_entries" to library.entries.map { listOf(it.id) },
            "listener_collection_items" to personal.collectionItems.map { listOf(it.collectionId, it.bookId) },
            "listener_collections" to personal.collections.map { listOf(it.id) },
            "playback_events" to library.events.map { listOf(it.id) },
            "playback_failures" to library.failures.map { listOf(it.id) },
            "playback_progress" to library.progress.map { listOf(it.editionId) },
            "popularity_assertions" to catalog.popularity.map { listOf(it.id) },
            "readthroughs" to library.readthroughs.map { listOf(it.id) },
            "recommendation_preferences" to personal.preferences.map { listOf(it.kind, it.targetKey) },
            "series" to catalog.series.map { listOf(it.id) },
            "series_members" to catalog.seriesMembers.map { listOf(it.workId, it.seriesId) },
            "source_tracks" to library.tracks.map { listOf(it.id) },
            "sources" to library.sources.map { listOf(it.id) },
            "submission_states" to library.submissions.map { listOf(it.sourceId) },
            "tombstones" to personal.tombstones.map { listOf(it.bookId) },
            "universes" to catalog.universes.map { listOf(it.id) },
            "work_facet_series" to catalog.facetSeries.map { listOf(it.workId, it.seriesId) },
            "work_facets" to catalog.workFacets.map { listOf(it.workId) },
            "work_genres" to catalog.workGenres.map { listOf(it.workId, it.genreId, it.sourceId) },
            "work_sources" to catalog.workSources.map { listOf(it.id) },
            "works" to library.works.map { listOf(it.id) },
        )
        val out = Frames(context)
        out.string("ManualWorkMergeStorage\u0000v2\u0000Room55")
        out.string(encoding)
        val participants = roots.distinct().sortedWith(::compareMergeUtf8)
        out.tag('P'.code)
        out.long(participants.size.toLong())
        participants.forEach(out::string)
        excluded.forEach { name -> out.tag('G'.code); out.string(name); out.string("excluded") }
        for (table in tables) {
            context.ensureActive()
            val rows = if (table.name == "works_fts") {
                ftsRows(dao, table, participants, catalog, encoding, context)
            } else {
                keyedRows(dao, table, scopedKeys.getValue(table.name), encoding, context)
            }
            out.tag('T'.code)
            out.string(table.name)
            out.long(table.columns.size.toLong())
            table.columns.forEach(out::string)
            out.long(rows.size.toLong())
            rows.sortedWith(::compareBytes).forEach(out::bytes)
        }
        return "manual-work-merge-storage-v2:" + hex(out.finish())
    }

    private fun requireRoom55(dao: AudiobookDao) {
        dao.manualMergeStorageCursor(SimpleSQLiteQuery("PRAGMA user_version")).use { cursor ->
            check(cursor.columnCount == 1 && cursor.moveToFirst()) { "Missing SQLite schema version" }
            check(cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(0) == 55L) {
                "Storage fingerprint requires Room55"
            }
            check(!cursor.moveToNext()) { "Ambiguous SQLite schema version" }
        }
    }

    private fun textEncoding(dao: AudiobookDao): String =
        dao.manualMergeStorageCursor(SimpleSQLiteQuery("PRAGMA encoding")).use { cursor ->
            check(cursor.columnCount == 1 && cursor.moveToFirst()) { "Missing SQLite text encoding" }
            check(cursor.getType(0) == Cursor.FIELD_TYPE_STRING) { "Invalid SQLite text encoding" }
            val encoding = cursor.getString(0)
            check(encoding in setOf("UTF-8", "UTF-16le", "UTF-16be")) { "Unsupported SQLite text encoding" }
            check(!cursor.moveToNext()) { "Ambiguous SQLite text encoding" }
            encoding
        }

    private fun keyedRows(
        dao: AudiobookDao,
        table: Table,
        keys: List<List<Any>>,
        encoding: String,
        context: CoroutineContext,
    ): List<ByteArray> {
        check(table.keys.isNotEmpty()) { "Missing storage key descriptor for ${table.name}" }
        check(keys.all { it.size == table.keys.size && it.all { key -> key is String || key is Long } }) {
            "Invalid storage key scope for ${table.name}"
        }
        check(keys.toSet().size == keys.size) { "Duplicate typed storage key for ${table.name}" }
        val rows = mutableListOf<ByteArray>()
        for (batch in keys.chunked(KEY_BATCH_SIZE)) {
            context.ensureActive()
            val remaining = batch.toMutableSet()
            val tuple = table.keys.joinToString(" AND ", "(", ")") { "${quoted(it)} = ?" }
            val predicate = batch.joinToString(" OR ") { tuple }
            val query = SimpleSQLiteQuery(
                "SELECT ${table.projection} FROM ${quoted(table.name)} WHERE $predicate",
                batch.flatten().toTypedArray(),
            )
            dao.manualMergeStorageCursor(query).use { cursor ->
                check(cursor.columnCount == table.columns.size * 2) { "Invalid storage projection for ${table.name}" }
                while (cursor.moveToNext()) {
                    context.ensureActive()
                    val (key, digest) = storageRow(cursor, table, encoding, context)
                    check(remaining.remove(key)) { "Unexpected or duplicate storage row for ${table.name}" }
                    rows += digest
                }
            }
            context.ensureActive()
            check(remaining.isEmpty()) { "Missing storage row for ${table.name}" }
        }
        check(rows.size == keys.size) { "Storage row count mismatch for ${table.name}" }
        return rows
    }

    private fun ftsRows(
        dao: AudiobookDao,
        table: Table,
        participants: List<String>,
        catalog: ManualWorkMergeCatalogRows,
        encoding: String,
        context: CoroutineContext,
    ): List<ByteArray> {
        // Identical bound-root predicate to manualMergeFtsRows, in the same transaction.
        // No post-filter or declared PK: keep every raw row and duplicate multiplicity.
        val rows = mutableListOf<ByteArray>()
        for (batch in participants.chunked(KEY_BATCH_SIZE)) {
            context.ensureActive()
            val query = SimpleSQLiteQuery(
                "SELECT ${table.projection} FROM ${quoted(table.name)} WHERE \"workId\" IN (${batch.joinToString(", ") { "?" }})",
                batch.toTypedArray(),
            )
            dao.manualMergeStorageCursor(query).use { cursor ->
                check(cursor.columnCount == table.columns.size * 2) { "Invalid FTS storage projection" }
                while (cursor.moveToNext()) {
                    context.ensureActive()
                    rows += storageRow(cursor, table, encoding, context).second
                }
            }
        }
        context.ensureActive()
        check(rows.size == catalog.ftsRows.size) { "FTS storage row count mismatch" }
        return rows
    }

    private fun storageRow(
        cursor: Cursor,
        table: Table,
        encoding: String,
        context: CoroutineContext,
    ): Pair<List<Any>, ByteArray> {
        val row = Frames(context)
        val key = MutableList<Any>(table.keyIndices.size) { "" }
        row.string("ManualWorkMergeStorageRow\u0000v2\u0000Room55")
        row.string(table.name)
        row.long(table.columns.size.toLong())
        for ((index, name) in table.columns.withIndex()) {
            context.ensureActive()
            row.string(name)
            val typeIndex = index * 2
            val payloadIndex = typeIndex + 1
            check(cursor.getType(typeIndex) == Cursor.FIELD_TYPE_STRING) { "Missing storage class in ${table.name}" }
            val type = when (cursor.getString(typeIndex)) {
                "null" -> Cursor.FIELD_TYPE_NULL
                "integer" -> Cursor.FIELD_TYPE_INTEGER
                "real" -> Cursor.FIELD_TYPE_FLOAT
                "text" -> Cursor.FIELD_TYPE_STRING
                "blob" -> Cursor.FIELD_TYPE_BLOB
                else -> error("Unknown SQLite storage class in ${table.name}")
            }
            val expectedPayloadType = if (type == Cursor.FIELD_TYPE_STRING) Cursor.FIELD_TYPE_BLOB else type
            check(cursor.getType(payloadIndex) == expectedPayloadType) { "Invalid storage payload in ${table.name}" }
            row.tag(type)
            val keyIndex = table.keyIndices.indexOf(index)
            when (type) {
                Cursor.FIELD_TYPE_NULL -> check(keyIndex < 0) { "Null storage key in ${table.name}" }
                Cursor.FIELD_TYPE_INTEGER -> {
                    val value = cursor.getLong(payloadIndex)
                    row.long(value)
                    if (keyIndex >= 0) key[keyIndex] = value
                }
                Cursor.FIELD_TYPE_FLOAT -> {
                    check(keyIndex < 0) { "Unrepresentable REAL storage key in ${table.name}" }
                    row.long(cursor.getDouble(payloadIndex).toRawBits())
                }
                Cursor.FIELD_TYPE_STRING -> {
                    val bytes = cursor.getBlob(payloadIndex)
                    row.bytes(bytes)
                    if (keyIndex >= 0) key[keyIndex] = decodeTextKey(bytes, encoding)
                }
                Cursor.FIELD_TYPE_BLOB -> {
                    check(keyIndex < 0) { "Unrepresentable BLOB storage key in ${table.name}" }
                    row.bytes(cursor.getBlob(payloadIndex))
                }
            }
        }
        return key to row.finish()
    }

    /** Decoding is solely for PK coverage; the digest always uses the original bytes. */
    private fun decodeTextKey(bytes: ByteArray, encoding: String): String {
        val charset = when (encoding) {
            "UTF-8" -> Charsets.UTF_8
            "UTF-16le" -> Charsets.UTF_16LE
            "UTF-16be" -> Charsets.UTF_16BE
            else -> error("Unsupported SQLite text encoding")
        }
        return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private class Frames(private val context: CoroutineContext) {
        private val digest = MessageDigest.getInstance("SHA-256")
        fun tag(value: Int) { digest.update(value.toByte()) }
        fun long(value: Long) {
            val bytes = ByteArray(8) { index -> (value ushr (56 - index * 8)).toByte() }
            digest.update(bytes)
        }
        fun string(value: String) = bytes(value.toByteArray(Charsets.UTF_8))
        fun bytes(value: ByteArray) {
            long(value.size.toLong())
            var offset = 0
            while (offset < value.size) {
                context.ensureActive()
                val size = minOf(HASH_CHUNK_SIZE, value.size - offset)
                digest.update(value, offset, size)
                offset += size
            }
        }
        fun finish(): ByteArray { context.ensureActive(); return digest.digest() }
    }

    private fun quoted(name: String): String = "\"${name.replace("\"", "\"\"")}\""

    private fun compareBytes(first: ByteArray, second: ByteArray): Int {
        for (index in 0 until minOf(first.size, second.size)) {
            val order = (first[index].toInt() and 255).compareTo(second[index].toInt() and 255)
            if (order != 0) return order
        }
        return first.size.compareTo(second.size)
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        val digits = "0123456789abcdef"
        bytes.forEach { byte ->
            val value = byte.toInt() and 255
            append(digits[value ushr 4]); append(digits[value and 15])
        }
    }
}
