package com.slukhayka.audiobooks.data.authors

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.WorkEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthorIndexRoomTest {
    private lateinit var db: AudiobookDatabase
    private lateinit var index: AuthorIndex

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        index = RoomAuthorIndex(db.audiobookDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `canonical aliases search every Work while uncertain identities stay separate`() = runBlocking {
        val works = listOf(
            WorkEntity("w-lesia", "w-lesia", "Лісова пісня", "Леся Українка", addedAt = 3),
            WorkEntity("w-larysa", "w-larysa", "Бояриня", "Лариса Косач", addedAt = 2),
            WorkEntity("w-lookalike", "w-lookalike", "Інша книга", "Lеся Українка", addedAt = 1)
        )
        works.forEach { db.audiobookDao().upsertWork(it) }
        index.indexWorks(works, sourceId = "catalog-union")

        val lesia = AuthorIdentity.fromWorkName("Леся Українка")
        index.applyAssertion(
            canonicalId = lesia.id,
            displayName = "Леся Українка",
            aliases = listOf("Лариса Косач"),
            workIds = setOf("w-lesia", "w-larysa"),
            sourceId = "wikidata",
            observedAt = 10
        )

        // A later catalogue rewrite carries only derived freshness (0) and
        // must not undo the trusted assertion's canonical Work mapping.
        index.indexWorks(listOf(works[1]), sourceId = "catalog-union")

        assertEquals(listOf("Леся Українка"), index.search("косач").map { it.displayName })
        assertEquals(2, index.search("леся").first().workCount)
        assertEquals(emptyList<AuthorSummary>(), index.search("л"))
        assertEquals(
            listOf("Бояриня", "Лісова пісня"),
            index.works(lesia.id).map { it.title }
        )
        assertEquals(
            listOf("Lеся Українка", "Леся Українка"),
            index.authors.first().map { it.displayName }
        )
        assertEquals(lesia.id, index.authorForWork("w-larysa")?.id)
    }

    @Test
    fun `first local read backfills persisted Works without network or provider people`() = runBlocking {
        db.audiobookDao().upsertWork(
            WorkEntity("legacy-work", "legacy-work", "Intermezzo", "Михайло Коцюбинський", addedAt = 1)
        )

        val author = index.search("коцюбинський").single()

        assertEquals("Михайло Коцюбинський", author.displayName)
        assertEquals(listOf("Intermezzo"), index.works(author.id).map(WorkEntity::title))
    }

    @Test
    fun `first read repairs only one bounded page then continues in background`() = runTest {
        val sqlite = db.openHelper.writableDatabase
        db.runInTransaction {
            val insert = sqlite.compileStatement(
                "INSERT INTO works(id, mergeKey, title, author, addedAt) VALUES(?, ?, ?, ?, 0)"
            )
            repeat(RoomAuthorIndex.BACKFILL_BATCH_SIZE + 1) { number ->
                val id = "backfill-${number.toString().padStart(4, '0')}"
                insert.bindString(1, id)
                insert.bindString(2, id)
                insert.bindString(3, "Книга $number")
                insert.bindString(4, "Автор $number")
                insert.executeInsert()
                insert.clearBindings()
            }
        }
        val backgroundDispatcher = StandardTestDispatcher(testScheduler)
        val boundedIndex = RoomAuthorIndex(db.audiobookDao(), CoroutineScope(backgroundDispatcher))

        assertTrue(boundedIndex.search("автор 0").isNotEmpty())
        assertEquals(1, db.audiobookDao().worksMissingCanonicalAuthor(RoomAuthorIndex.BACKFILL_BATCH_SIZE).size)

        testScheduler.advanceUntilIdle()
        assertEquals(0, db.audiobookDao().worksMissingCanonicalAuthor(RoomAuthorIndex.BACKFILL_BATCH_SIZE).size)
    }

    @Test
    fun `newer canonical display survives a raw catalog replay`() = runBlocking {
        val work = WorkEntity("oconnor", "oconnor", "Оповідання", "О'КОННОР", addedAt = 1)
        db.audiobookDao().upsertWork(work)
        val identity = AuthorIdentity.fromWorkName(work.author)
        index.applyAssertion(identity.id, "О’Коннор", emptyList(), setOf(work.id), "metadata", 10)

        index.indexWorks(listOf(work), sourceId = "catalog-union")

        assertEquals("О’Коннор", index.authorForWork(work.id)?.displayName)
    }

    @Test
    fun `derived equal freshness cannot replace an existing canonical author`() = runBlocking {
        val original = WorkEntity("equal-zero", "equal-zero", "Книга", "Автор Перший", addedAt = 1)
        db.audiobookDao().upsertWork(original)
        index.indexWorks(listOf(original), sourceId = "first-source")
        val originalId = AuthorIdentity.fromWorkName(original.author).id

        index.indexWorks(listOf(original.copy(author = "Автор Другий")), sourceId = "second-source")

        assertEquals(originalId, index.authorForWork(original.id)?.id)
    }

    @Test
    fun `missing canonical author is filled even when genre facet timestamp is newer`() = runBlocking {
        val work = WorkEntity("genre-only", "genre-only", "Книга", "Автор Жанру", addedAt = 1)
        db.audiobookDao().upsertWork(work)
        db.audiobookDao().mergeWorkFacet(work.id, authorId = null, updatedAt = 100)

        index.indexWorks(listOf(work), sourceId = "catalog-union")

        assertEquals(AuthorIdentity.fromWorkName(work.author).id, index.authorForWork(work.id)?.id)
    }

    @Test
    fun `stored Work mapping wins when two canonical identities share an alias claim`() = runBlocking {
        val works = listOf(
            WorkEntity("collision-a", "collision-a", "Книга А", "Автор А", addedAt = 2),
            WorkEntity("collision-b", "collision-b", "Книга Б", "Автор Б", addedAt = 1)
        )
        works.forEach { db.audiobookDao().upsertWork(it) }
        index.indexWorks(works, sourceId = "catalog-union")
        index.applyAssertion("canonical-a", "Автор А", listOf("Спільне Ім’я"), setOf("collision-a"), "metadata-a", 10)
        index.applyAssertion("canonical-b", "Автор Б", listOf("Спільне Ім’я"), setOf("collision-b"), "metadata-b", 10)

        assertEquals(2, index.search("спільне", limit = 10).size)
        assertEquals("canonical-b", index.authorForWork("collision-b")?.id)
    }

    @Test
    fun `ten thousand authors use bounded indexed lookup and indexed author to Work join`() = runBlocking {
        val sqlite = db.openHelper.writableDatabase
        db.runInTransaction {
            val authorInsert = sqlite.compileStatement(
                "INSERT INTO author_facets(id, displayName, normalizedName, updatedAt) VALUES(?, ?, ?, 0)"
            )
            val aliasInsert = sqlite.compileStatement(
                "INSERT INTO author_aliases(authorId, normalizedAlias, rawAlias, sourceId, observedAt) VALUES(?, ?, ?, 'catalog-union', 0)"
            )
            val workInsert = sqlite.compileStatement(
                "INSERT INTO works(id, mergeKey, title, author, addedAt) VALUES(?, ?, ?, ?, 0)"
            )
            val facetInsert = sqlite.compileStatement(
                "INSERT INTO work_facets(workId, canonicalAuthorId, updatedAt) VALUES(?, ?, 0)"
            )
            repeat(10_000) { number ->
                val id = "author-$number"
                val display = if (number == 9_999) "Цільовий Автор" else "Автор $number"
                val normalized = AuthorIdentity.normalizedName(display)
                authorInsert.bindString(1, id)
                authorInsert.bindString(2, display)
                authorInsert.bindString(3, normalized)
                authorInsert.executeInsert()
                authorInsert.clearBindings()

                aliasInsert.bindString(1, id)
                aliasInsert.bindString(2, normalized)
                aliasInsert.bindString(3, display)
                aliasInsert.executeInsert()
                aliasInsert.clearBindings()

                val workId = "work-$number"
                workInsert.bindString(1, workId)
                workInsert.bindString(2, workId)
                workInsert.bindString(3, "Книга $number")
                workInsert.bindString(4, display)
                workInsert.executeInsert()
                workInsert.clearBindings()

                facetInsert.bindString(1, workId)
                facetInsert.bindString(2, id)
                facetInsert.executeInsert()
                facetInsert.clearBindings()
            }
        }

        assertEquals(listOf("Цільовий Автор"), index.search("цільовий").map(AuthorSummary::displayName))
        assertEquals(listOf("Книга 9999"), index.works("author-9999").map(WorkEntity::title))

        val searchPlan = sqlite.query(
            "EXPLAIN QUERY PLAN SELECT a.id, a.displayName, a.normalizedName, COUNT(DISTINCT wf.workId) " +
                "FROM author_aliases aa INDEXED BY index_author_aliases_normalizedAlias " +
                "JOIN author_facets a ON a.id=aa.authorId " +
                "JOIN work_facets wf ON wf.canonicalAuthorId=a.id " +
                "WHERE aa.normalizedAlias >= 'цільовий' AND aa.normalizedAlias < 'цільовий￿' " +
                "GROUP BY a.id, a.displayName, a.normalizedName LIMIT 6"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(3))
            }.joinToString("\n")
        }
        assertTrue(searchPlan, searchPlan.contains("index_author_aliases_normalizedAlias"))
        assertTrue(searchPlan, searchPlan.contains("index_work_facets_canonicalAuthorId"))
        assertFalse(searchPlan, searchPlan.contains("SCAN works"))
    }
}
