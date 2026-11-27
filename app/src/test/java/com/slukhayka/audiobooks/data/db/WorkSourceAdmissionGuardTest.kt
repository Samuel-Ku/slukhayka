package com.slukhayka.audiobooks.data.db

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.source.SourceRegistry.streamOnlyFor
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Rejected own-page claims preserve the actual catalogue carriers and search projection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkSourceAdmissionGuardTest {
    private lateinit var context: Context
    private lateinit var database: AudiobookDatabase
    private lateinit var dao: AudiobookDao
    private lateinit var catalog: SourceCatalog
    private val observedSql = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                    observedSql += sqlQuery
                }
            }, Executor { it.run() })
            .build()
        dao = database.audiobookDao()
        catalog = SourceCatalog(dao, emptyList(), LibraryImport(dao, context, emptyList()))
    }

    @After
    fun tearDown() {
        database.close()
    }

    private data class Seed(val work: WorkEntity, val carrier: WorkSourceEntity, val match: String)
    private data class SqlRows(val columns: List<String>, val rows: List<List<Any?>>)
    private data class State(
        val works: List<WorkEntity>,
        val sources: SqlRows,
        val perWorkSources: Map<String, List<WorkSourceEntity>>,
        val sourceCount: Int,
        val searchRows: SqlRows,
        val searchCount: Int,
        val matches: Map<String, List<String>>,
    )

    private suspend fun seed(
        title: String = "Пані Боварі",
        author: String = "Гюстав Флобер",
        narrator: String = "Іван Франко",
        url: String = "https://sluhay.com/bovari",
        match: String = "боварі*",
        cover: String = "https://sluhay.com/covers/bovari.jpg",
    ): Seed {
        observedSql.clear()
        val work = catalog.writeWorkEdition(
            sourceId = "sluhay", title = title, author = author, narrator = narrator,
            sourceUrl = url, streamOnly = streamOnlyFor("sluhay"),
            coverImageUrl = cover, durationSeconds = 7_200L,
        ).work
        val carrier = dao.getWorkSourcesForWorkSync(work.id).single()
        assertEquals(work, dao.getWorkById(work.id))
        assertEquals(listOf(work.id), dao.matchWorkSearch(match, 10))
        // Positive control for the real public observer, not a manually invoked callback.
        assertTrue("public catalog seed must emit actual FTS DELETE", observedSql.any { isFtsMutation(it) && it.trimStart().startsWith("DELETE", ignoreCase = true) })
        assertTrue("public catalog seed must emit actual FTS INSERT", observedSql.any { isFtsMutation(it) && it.trimStart().startsWith("INSERT", ignoreCase = true) })
        return Seed(work, carrier, match)
    }

    private fun sqlRows(sql: String): SqlRows = database.openHelper.readableDatabase.query(sql).use { cursor ->
        val rows = mutableListOf<List<Any?>>()
        while (cursor.moveToNext()) {
            rows += (0 until cursor.columnCount).map { column ->
                when (cursor.getType(column)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                    else -> error("Unsupported real cursor field type")
                }
            }
        }
        SqlRows(cursor.columnNames.toList(), rows)
    }

    private suspend fun snapshot(seeds: List<Seed>): State {
        val works = dao.observeWorks().first().sortedBy { it.id }
        return State(
            works = works,
            sources = sqlRows("SELECT * FROM work_sources ORDER BY id"),
            perWorkSources = works.associate { it.id to dao.getWorkSourcesForWorkSync(it.id).sortedBy { source -> source.id } },
            sourceCount = dao.countWorkSources(),
            searchRows = sqlRows("SELECT rowid, workId, title, author, series, narrator FROM works_fts ORDER BY rowid"),
            searchCount = dao.workSearchRowCount(),
            matches = seeds.associate { it.match to dao.matchWorkSearch(it.match, 10) },
        )
    }

    private fun canonical(workId: String, sourceId: String, url: String) =
        "$workId|$sourceId|${Integer.toHexString(url.hashCode())}"

    private fun isFtsMutation(sql: String): Boolean = Regex(
        """^\s*(?:INSERT(?:\s+OR\s+\w+)?\s+INTO|REPLACE\s+INTO|UPDATE(?:\s+OR\s+\w+)?|DELETE\s+FROM)\s+[`"\[]?works_fts(?:[`"\]]|\b)""",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(sql)

    private suspend fun rejected(claim: WorkSourceEntity, seeds: List<Seed>, before: State) {
        observedSql.clear()
        // A natural exception is a failure too; do not turn it into a false verdict.
        assertFalse("claim must be rejected before any carrier/index admission", dao.mergeAdmittedWorkSource(claim))
        assertEquals("all actual parents, complete carriers and FTS rows remain identical", before, snapshot(seeds))
        assertTrue("rejection must attempt no FTS DML", observedSql.none(::isFtsMutation))
    }

    @Test
    fun `missing parent claim returns false without admitting an orphan or changing search`() = runBlocking {
        val admitted = seed()
        val missingId = "missing-parent-525"
        assertNull(dao.getWorkById(missingId))
        val url = "https://sluhay.com/fixtures/missing-parent-525"
        val claim = admitted.carrier.copy(
            id = canonical(missingId, "sluhay", url), workId = missingId, sourceUrl = url,
            coverImageUrl = "https://sluhay.com/covers/fresh.jpg", durationSeconds = 8_100L,
        )
        val before = snapshot(listOf(admitted))
        rejected(claim, listOf(admitted), before)
        assertNull(dao.getWorkById(missingId))
        assertNull(dao.getWorkSourceById(claim.id))
        assertEquals(admitted.carrier, dao.getWorkSourceById(admitted.carrier.id))
    }

    @Test
    fun `blank source identity and noncanonical key cannot fork admitted carriers`() = runBlocking {
        val admitted = seed()
        val source = admitted.carrier
        val claims = listOf(
            source.copy(sourceId = " \t ", id = canonical(source.workId, " \t ", source.sourceUrl)),
            source.copy(sourceUrl = " \t ", id = canonical(source.workId, source.sourceId, " \t ")),
            source.copy(id = "legacy-unscoped-525"),
        )
        val before = snapshot(listOf(admitted))
        for (claim in claims) {
            assertNull(dao.getWorkSourceById(claim.id))
            rejected(claim, listOf(admitted), before)
            assertNull(dao.getWorkSourceById(claim.id))
            assertEquals(source, dao.getWorkSourceById(source.id))
        }
    }

    @Test
    fun `global occupied carrier id cannot be reparented by a canonical claim`() = runBlocking {
        val first = seed()
        val second = seed(
            title = "Місто", author = "Валер’ян Підмогильний", narrator = "Богдан Бенюк",
            url = "https://sluhay.com/misto", match = "місто*", cover = "https://sluhay.com/covers/misto.jpg",
        )
        assertNotEquals(first.work.id, second.work.id)
        val url = "https://sluhay.com/fixtures/occupied-parent-525"
        val occupiedId = canonical(first.work.id, "sluhay", url)
        // Deliberate schema/FK-valid legacy fixture: a real B parent owns an
        // ID canonical for A. Current public catalog does not create this shape.
        val legacy = second.carrier.copy(id = occupiedId, sourceUrl = url)
        dao.upsertWorkSource(legacy)
        dao.refreshWorkSearchIndex(second.work.id)
        assertEquals(legacy, dao.getWorkSourceById(occupiedId))
        val claim = first.carrier.copy(
            id = occupiedId, sourceUrl = url,
            coverImageUrl = "https://sluhay.com/covers/fresh.jpg", durationSeconds = 8_100L,
        )
        assertEquals(canonical(claim.workId, claim.sourceId, claim.sourceUrl), claim.id)
        val seeds = listOf(first, second)
        val before = snapshot(seeds)
        rejected(claim, seeds, before)
        assertEquals(legacy, dao.getWorkSourceById(occupiedId))
        assertEquals(listOf(first.carrier), dao.getWorkSourcesForWorkSync(first.work.id))
        assertEquals(setOf(second.carrier, legacy), dao.getWorkSourcesForWorkSync(second.work.id).toSet())
    }

    @Test
    fun `equal URL hashes never permit overwriting a different admitted full URL`() = runBlocking {
        val admittedUrl = "https://sluhay.com/fixtures/Aa"
        val proposedUrl = "https://sluhay.com/fixtures/BB"
        assertNotEquals(admittedUrl, proposedUrl)
        assertEquals(admittedUrl.hashCode(), proposedUrl.hashCode())
        assertEquals("db5a18dc", Integer.toHexString(admittedUrl.hashCode()))
        assertEquals("db5a18dc", Integer.toHexString(proposedUrl.hashCode()))
        val admitted = seed(url = admittedUrl)
        assertEquals(canonical(admitted.work.id, "sluhay", admittedUrl), admitted.carrier.id)
        val claim = admitted.carrier.copy(
            id = canonical(admitted.work.id, "sluhay", proposedUrl), sourceUrl = proposedUrl,
            coverImageUrl = "https://sluhay.com/covers/fresh.jpg", durationSeconds = 8_100L,
        )
        assertEquals(admitted.carrier.id, claim.id)
        val before = snapshot(listOf(admitted))
        rejected(claim, listOf(admitted), before)
        assertEquals(admitted.carrier, dao.getWorkSourceById(claim.id))
        assertEquals(listOf(admitted.carrier), dao.getWorkSourcesForWorkSync(admitted.work.id))
        assertEquals(1, dao.countWorkSources())
    }
}
