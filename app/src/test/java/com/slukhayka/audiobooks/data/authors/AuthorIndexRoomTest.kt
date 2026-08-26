package com.slukhayka.audiobooks.data.authors

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.WorkEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
