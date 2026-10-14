package com.slukhayka.audiobooks.data.merge

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** Recoverable exported SQL values. FTS rowid/pages/shadow state are deliberately outside this format. */
internal class ManualWorkMergeJournal(
    private val database: AudiobookDatabase,
    private val context: CoroutineContext,
) {
    internal data class Cell(val type: String, val number: Long = 0, val bytes: ByteArray = byteArrayOf())
    internal data class Row(val table: String, val cells: List<Cell>)
    internal data class Image(val encoding: String, val rows: Map<String, List<Row>>, val formatVersion: Int)
    private data class Table(val name: String, val columns: List<String>, val scopeColumn: String, val keys: List<String> = listOf("id"))

    private val sql get() = database.openHelper.writableDatabase

    fun capture(workIds: List<String>, bookIds: List<String>, editionIds: List<String> = emptyList(), formatFloor: Int = 1): Image {
        require(supportsVersion(formatFloor))
        context.ensureActive()
        sql.query("PRAGMA user_version").use { cursor ->
            check(cursor.moveToFirst() && cursor.getLong(0) == 55L && !cursor.moveToNext()) { "Journal requires Room55" }
        }
        val encoding = encoding()
        var totalRows = 0
        var retainedBytes = 0
        val result = linkedMapOf<String, List<Row>>()
        for (table in TABLES) {
            val keys = (when (table.name) {
                "library_entries" -> bookIds
                "edition_facets" -> editionIds
                else -> workIds
            }).distinct()
            val rows = mutableListOf<Row>()
            val projection = table.columns.flatMap { column ->
                val name = quoted(column)
                listOf("typeof($name)", "CASE WHEN typeof($name) = 'text' THEN CAST($name AS BLOB) ELSE $name END")
            }.joinToString(", ")
            val queries = listOf(table.scopeColumn to keys) +
                (if (table.name == "edition_facets") listOf("workId" to workIds.distinct()) else emptyList())
            for ((scopeColumn, scopeKeys) in queries) for (batch in scopeKeys.chunked(128)) {
                context.ensureActive()
                val placeholders = batch.joinToString(", ") { "?" }
                sql.query(SimpleSQLiteQuery(
                    "SELECT $projection FROM ${quoted(table.name)} WHERE ${quoted(scopeColumn)} IN ($placeholders)",
                    batch.toTypedArray(),
                )).use { cursor ->
                    check(cursor.columnCount == table.columns.size * 2)
                    while (cursor.moveToNext()) {
                        context.ensureActive()
                        check(++totalRows <= MAX_ROWS) { "Manual merge journal row bound" }
                        val row = Row(table.name, table.columns.indices.map { index -> cell(cursor, index * 2) })
                        retainedBytes += rowBytes(row).size
                        check(retainedBytes <= MAX_PAYLOAD_BYTES) { "Manual merge journal payload bound" }
                        if (table.name != "edition_facets" || rows.none { existing -> sameKey(existing, row) }) rows += row
                    }
                }
            }
            result[table.name] = rows.sortedWith { first, second -> compareBytes(rowBytes(first), rowBytes(second)) }
        }
        val version = when {
            CATALOG_TABLES.any { result.getValue(it.name).isNotEmpty() } || formatFloor == 3 -> 3
            result.getValue("readthroughs").isNotEmpty() || formatFloor == 2 -> 2
            else -> 1
        }
        return Image(encoding, result, version)
    }

    fun encode(image: Image): ByteArray {
        context.ensureActive()
        check(image.rows.values.sumOf { it.size } <= MAX_ROWS) { "Journal row bound" }
        val tables = tablesForVersion(image.formatVersion)
        check(image.formatVersion == 3 || CATALOG_TABLES.all { image.rows.getValue(it.name).isEmpty() }) { "Legacy journal cannot omit catalog rows" }
        check(image.formatVersion != 1 || image.rows.getValue("readthroughs").isEmpty()) { "Legacy journal cannot omit Readthroughs" }
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { stream ->
            stream.writeUTF("ManualWorkMergeRows")
            stream.writeInt(image.formatVersion)
            stream.writeUTF(image.encoding)
            for (table in tables) {
                stream.writeUTF(table.name)
                val rows = image.rows.getValue(table.name)
                check(rows.size <= MAX_ROWS)
                stream.writeInt(rows.size)
                for (row in rows) {
                    context.ensureActive()
                    check(row.table == table.name && row.cells.size == table.columns.size)
                    row.cells.forEach { writeCell(stream, it) }
                    check(buffer.size() <= MAX_PAYLOAD_BYTES) { "Manual merge journal payload bound" }
                }
            }
        }
        return buffer.toByteArray().also { check(it.size <= MAX_PAYLOAD_BYTES) }
    }

    fun decode(bytes: ByteArray): Image {
        context.ensureActive()
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "Manual merge journal payload bound" }
        DataInputStream(ByteArrayInputStream(bytes)).use { stream ->
            require(stream.readUTF() == "ManualWorkMergeRows") { "Unknown journal format" }
            val formatVersion = stream.readInt()
            val tables = tablesForVersion(formatVersion)
            val encoding = stream.readUTF()
            charset(encoding)
            val rows = linkedMapOf<String, List<Row>>()
            var total = 0
            for (table in tables) {
                require(stream.readUTF() == table.name) { "Unknown journal table layout" }
                val count = stream.readInt()
                require(count in 0..MAX_ROWS && total + count <= MAX_ROWS) { "Journal row bound" }
                total += count
                rows[table.name] = List(count) {
                    context.ensureActive()
                    Row(table.name, List(table.columns.size) { readCell(stream) })
                }
            }
            require(stream.read() == -1) { "Trailing journal payload" }
            for (table in TABLES) if (table.name !in rows) rows[table.name] = emptyList()
            return Image(encoding, rows, formatVersion)
        }
    }

    fun sameCell(first: Cell, second: Cell): Boolean = first.type == second.type &&
        first.number == second.number && first.bytes.contentEquals(second.bytes)

    fun text(row: Row, column: String, encoding: String): String {
        val value = row.cells[descriptor(row.table).columns.indexOf(column)]
        require(value.type == "text") { "Expected TEXT identity in ${row.table}" }
        return charset(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value.bytes)).toString()
    }

    fun withText(row: Row, fields: Map<String, String>, encoding: String): Row {
        val table = descriptor(row.table)
        require(fields.keys.all { it in table.columns })
        val values = row.cells.toMutableList()
        for ((column, text) in fields) {
            val encoded = charset(encoding).newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
            val bytes = ByteArray(encoded.remaining()); encoded.get(bytes)
            values[table.columns.indexOf(column)] = Cell("text", bytes = bytes)
        }
        return Row(row.table, values)
    }

    fun insert(row: Row) {
        context.ensureActive()
        val table = descriptor(row.table)
        require(row.cells.size == table.columns.size)
        val columns = table.columns.joinToString(", ") { quoted(it) }
        val parameters = row.cells.joinToString(", ") { if (it.type == "text") "CAST(? AS TEXT)" else "?" }
        sql.compileStatement("INSERT INTO ${quoted(table.name)} ($columns) VALUES ($parameters)").use { statement ->
            row.cells.forEachIndexed { index, cell -> bind(statement, index + 1, cell) }
            statement.executeInsert()
        }
    }

    fun updateById(row: Row, previousId: String): Int {
        context.ensureActive()
        val table = descriptor(row.table)
        require(table.columns.first() == "id" && row.cells.size == table.columns.size)
        val assignments = table.columns.zip(row.cells).joinToString(", ") { (column, value) ->
            "${quoted(column)} = ${if (value.type == "text") "CAST(? AS TEXT)" else "?"}"
        }
        return sql.compileStatement("UPDATE ${quoted(table.name)} SET $assignments WHERE id = ?").use { statement ->
            row.cells.forEachIndexed { index, value -> bind(statement, index + 1, value) }
            statement.bindString(row.cells.size + 1, previousId)
            statement.executeUpdateDelete()
        }
    }

    fun value(row: Row, column: String): Cell = row.cells[descriptor(row.table).columns.indexOf(column).also { require(it >= 0) }]

    fun nullableText(row: Row, column: String, encoding: String): String? =
        if (value(row, column).type == "null") null else text(row, column, encoding)

    fun integer(row: Row, column: String): Long = value(row, column).let {
        require(it.type == "integer") { "Expected INTEGER $column in ${row.table}" }; it.number
    }

    fun key(row: Row, encoding: String): List<String> = descriptor(row.table).keys.map { text(row, it, encoding) }
    fun sameKey(a: Row, b: Row): Boolean = a.table == b.table && descriptor(a.table).keys.all {
        sameCell(value(a, it), value(b, it))
    }
    fun sameRow(a: Row, b: Row): Boolean = a.table == b.table && a.cells.size == b.cells.size &&
        a.cells.zip(b.cells).all { (x, y) -> sameCell(x, y) }
    fun compareRows(a: Row, b: Row): Int = compareBytes(rowBytes(a), rowBytes(b))

    fun deleteByKey(row: Row): Int {
        context.ensureActive()
        val table = descriptor(row.table)
        val values = table.keys.map { value(row, it).also { cell -> require(cell.type == "text") } }
        val where = table.keys.joinToString(" AND ") { "${quoted(it)} = CAST(? AS TEXT)" }
        return sql.compileStatement("DELETE FROM ${quoted(table.name)} WHERE $where").use { statement ->
            values.forEachIndexed { index, cell -> bind(statement, index + 1, cell) }
            statement.executeUpdateDelete()
        }
    }

    /** Bounded occupancy read before planning a generated catalog key, including outside owners. */
    fun rowByKey(tableName: String, keys: List<String>): Row? {
        context.ensureActive()
        val table = descriptor(tableName)
        require(keys.size == table.keys.size)
        val projection = table.columns.flatMap { column ->
            val name = quoted(column)
            listOf("typeof($name)", "CASE WHEN typeof($name) = 'text' THEN CAST($name AS BLOB) ELSE $name END")
        }.joinToString(", ")
        val where = table.keys.joinToString(" AND ") { "${quoted(it)} = ?" }
        return sql.query(SimpleSQLiteQuery("SELECT $projection FROM ${quoted(table.name)} WHERE $where", keys.toTypedArray())).use { cursor ->
            if (!cursor.moveToFirst()) null else Row(tableName, table.columns.indices.map { cell(cursor, it * 2) }).also {
                require(!cursor.moveToNext()) { "Ambiguous catalog key" }
                require(rowBytes(it).size <= MAX_PAYLOAD_BYTES) { "Catalog row payload bound" }
            }
        }
    }

    private fun encoding(): String = sql.query("PRAGMA encoding").use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0).also { charset(it); check(!cursor.moveToNext()) }
    }

    private fun cell(cursor: Cursor, index: Int): Cell {
        check(cursor.getType(index) == Cursor.FIELD_TYPE_STRING)
        val type = cursor.getString(index)
        val payload = index + 1
        val expected = when (type) {
            "null" -> Cursor.FIELD_TYPE_NULL
            "integer" -> Cursor.FIELD_TYPE_INTEGER
            "real" -> Cursor.FIELD_TYPE_FLOAT
            "text", "blob" -> Cursor.FIELD_TYPE_BLOB
            else -> error("Unknown SQL storage class")
        }
        check(cursor.getType(payload) == expected)
        return when (type) {
            "integer" -> Cell(type, number = cursor.getLong(payload))
            "real" -> Cell(type, number = cursor.getDouble(payload).toRawBits())
            "text", "blob" -> Cell(type, bytes = cursor.getBlob(payload).copyOf())
            else -> Cell("null")
        }
    }

    private fun bind(statement: SupportSQLiteStatement, index: Int, value: Cell) = when (value.type) {
        "null" -> statement.bindNull(index)
        "integer" -> statement.bindLong(index, value.number)
        "real" -> statement.bindDouble(index, Double.fromBits(value.number))
        "text", "blob" -> statement.bindBlob(index, value.bytes)
        else -> error("Unknown journal cell")
    }

    private fun writeCell(stream: DataOutputStream, value: Cell) {
        stream.writeUTF(value.type)
        when (value.type) {
            "null" -> Unit
            "integer", "real" -> stream.writeLong(value.number)
            "text", "blob" -> {
                check(value.bytes.size <= MAX_PAYLOAD_BYTES)
                stream.writeInt(value.bytes.size); stream.write(value.bytes)
            }
            else -> error("Unknown journal cell")
        }
    }

    private fun readCell(stream: DataInputStream): Cell = when (val type = stream.readUTF()) {
        "null" -> Cell(type)
        "integer", "real" -> Cell(type, number = stream.readLong())
        "text", "blob" -> {
            val size = stream.readInt(); require(size in 0..MAX_PAYLOAD_BYTES && size <= stream.available())
            Cell(type, bytes = ByteArray(size).also(stream::readFully))
        }
        else -> error("Unknown journal cell")
    }

    private fun rowBytes(row: Row): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { stream -> row.cells.forEach { writeCell(stream, it) } }
    }.toByteArray()

    private fun charset(encoding: String) = when (encoding) {
        "UTF-8" -> Charsets.UTF_8
        "UTF-16le" -> Charsets.UTF_16LE
        "UTF-16be" -> Charsets.UTF_16BE
        else -> error("Unsupported journal TEXT encoding")
    }

    private fun descriptor(name: String) = TABLES.single { it.name == name }
    private fun quoted(name: String) = "\"${name.replace("\"", "\"\"")}\""
    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (index in 0 until minOf(a.size, b.size)) {
            val order = (a[index].toInt() and 255).compareTo(b[index].toInt() and 255)
            if (order != 0) return order
        }
        return a.size.compareTo(b.size)
    }

    companion object {
        fun supportsVersion(version: Int): Boolean = version in 1..3

        private fun tablesForVersion(version: Int): List<Table> = when (version) {
            1 -> LEGACY_TABLES
            2 -> LIBRARY_TABLES
            3 -> TABLES
            else -> error("Unknown journal format")
        }
        // Bound each payload; larger operations need a paged journal before UI exposure.
        private const val MAX_PAYLOAD_BYTES = 384 * 1024
        private const val MAX_ROWS = 10_000
        private val LEGACY_TABLES = listOf(
            Table("works", listOf("id", "mergeKey", "title", "author", "seriesTitle", "seriesUrl", "seriesIndex", "coverImageUrl", "addedAt"), "id"),
            Table("library_entries", listOf("id", "workId", "origin", "isFavorite", "createdAt", "downloadProgress", "downloadState"), "id"),
            Table("work_sources", listOf("id", "workId", "sourceId", "sourceUrl", "streamOnly", "coverImageUrl", "durationSeconds", "addedAt"), "workId"),
            Table("works_fts", listOf("workId", "title", "author", "series", "narrator"), "workId", listOf("workId")),
        )
        private val LIBRARY_TABLES = LEGACY_TABLES + Table(
            "readthroughs",
            listOf("id", "libraryEntryId", "workId", "format", "state", "startedAt", "finishedAt", "editionId", "unit", "unitValue", "journalJson"),
            "workId",
        )
        internal val catalogTables = listOf("series_members", "work_facets", "work_facet_series", "genre_assertion_states", "genre_assertions", "work_genres", "edition_facets", "embedding_vectors")
        private val CATALOG_TABLES = listOf(
            Table("series_members", listOf("workId", "seriesId", "position", "resolvedAt"), "workId", listOf("workId", "seriesId")),
            Table("work_facets", listOf("workId", "canonicalAuthorId", "updatedAt"), "workId", listOf("workId")),
            Table("work_facet_series", listOf("workId", "seriesId"), "workId", listOf("workId", "seriesId")),
            Table("genre_assertion_states", listOf("workId", "sourceId", "documentUpdatedAt", "provenance"), "workId", listOf("workId", "sourceId")),
            Table("genre_assertions", listOf("id", "assertionId", "workId", "genreId", "rawText", "sourceId", "observedAt"), "workId"),
            Table("work_genres", listOf("workId", "genreId", "sourceId"), "workId", listOf("workId", "genreId", "sourceId")),
            Table("edition_facets", listOf("editionId", "workId", "narratorId", "language", "durationSeconds", "durationBucketId", "chapterCount", "isAbridged", "availabilityAvailable", "availabilityObservedAtMillis", "availabilityTtlSeconds", "updatedAt"), "editionId", listOf("editionId")),
            Table("embedding_vectors", listOf("workId", "textHash", "vector", "updatedAt"), "workId", listOf("workId")),
        )
        private val TABLES = LIBRARY_TABLES + CATALOG_TABLES

    }
}
