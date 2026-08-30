package com.slukhayka.audiobooks.data.personbookmarks

import android.content.Context
import androidx.room.Room
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkKind
import com.slukhayka.audiobooks.data.db.PersonRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #399 — in-memory Room DAO tests for the `person_bookmarks` table.
 *
 * Exercises the real SQL queries (INSERT OR REPLACE, UPDATE, SELECT, DELETE)
 * against an in-memory database to validate that the DAO annotations and
 * schema are correct.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersonBookmarksDaoTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    @Before
    fun setUp() {
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun testBookmark(
        kind: String = PersonBookmarkKind.AUTHOR,
        id: String = "author-test-123",
        displayName: String = "Тестовий Автор",
        normalizedName: String = "тестовий автор",
        createdAt: Long = 1000L
    ) = PersonBookmarkEntity(
        kind = kind, id = id, displayName = displayName,
        normalizedName = normalizedName, createdAt = createdAt,
        updatedAt = createdAt
    )

    @Test
    fun `insert and retrieve a person bookmark by kind`() = runBlocking {
        dao.upsertPersonBookmark(testBookmark())
        val bookmarks = dao.getPersonBookmarksByKind(PersonBookmarkKind.AUTHOR).first()
        assertEquals(1, bookmarks.size)
        assertEquals("Тестовий Автор", bookmarks[0].displayName)
    }

    @Test
    fun `getPersonBookmark returns null when not found`() = runBlocking {
        assertNull(dao.getPersonBookmark(PersonBookmarkKind.AUTHOR, "nonexistent"))
    }

    @Test
    fun `getPersonBookmark returns the bookmark when found`() = runBlocking {
        dao.upsertPersonBookmark(testBookmark())
        val found = dao.getPersonBookmark(PersonBookmarkKind.AUTHOR, "author-test-123")
        assertNotNull(found)
        assertEquals("Тестовий Автор", found!!.displayName)
    }

    @Test
    fun `delete removes the bookmark`() = runBlocking {
        dao.upsertPersonBookmark(testBookmark())
        dao.deletePersonBookmark(PersonBookmarkKind.AUTHOR, "author-test-123")
        assertNull(dao.getPersonBookmark(PersonBookmarkKind.AUTHOR, "author-test-123"))
    }

    @Test
    fun `upsert replaces an existing bookmark (same kind+id)`() = runBlocking {
        dao.upsertPersonBookmark(testBookmark(displayName = "Старе Ім'я"))
        dao.upsertPersonBookmark(testBookmark(displayName = "Нове Ім'я"))
        val found = dao.getPersonBookmark(PersonBookmarkKind.AUTHOR, "author-test-123")
        assertEquals("Нове Ім'я", found!!.displayName)
    }

    @Test
    fun `getAllPersonBookmarks returns both kinds sorted by createdAt desc`() = runBlocking {
        dao.upsertPersonBookmark(testBookmark(
            kind = PersonBookmarkKind.AUTHOR, id = "a1", displayName = "Автор 1", createdAt = 1000L
        ))
        dao.upsertPersonBookmark(testBookmark(
            kind = PersonBookmarkKind.NARRATOR, id = "n1", displayName = "Наратор 1", createdAt = 2000L
        ))
        val all = dao.getAllPersonBookmarks().first()
        assertEquals(2, all.size)
        // Newest first
        assertEquals("Наратор 1", all[0].displayName)
        assertEquals("Автор 1", all[1].displayName)
    }

    @Test
    fun `getPersonBookmarksByKind filters correctly`() = runBlocking {
        dao.upsertPersonBookmark(testBookmark(kind = PersonBookmarkKind.AUTHOR, id = "a1", displayName = "Автор"))
        dao.upsertPersonBookmark(testBookmark(kind = PersonBookmarkKind.NARRATOR, id = "n1", displayName = "Наратор"))
        val authors = dao.getPersonBookmarksByKind(PersonBookmarkKind.AUTHOR).first()
        assertEquals(1, authors.size)
        assertEquals("Автор", authors[0].displayName)
    }

    @Test
    fun `updateNotifyEnabled changes the flag`() = runBlocking {
        dao.upsertPersonBookmark(testBookmark())
        dao.updatePersonBookmarkNotifyEnabled(PersonBookmarkKind.AUTHOR, "author-test-123", false, 5000L)
        val found = dao.getPersonBookmark(PersonBookmarkKind.AUTHOR, "author-test-123")
        assertNotNull(found)
        assertFalse(found!!.notifyEnabled)
        assertEquals(5000L, found.updatedAt)
    }

    @Test
    fun `updateLastSeen updates the timestamp`() = runBlocking {
        dao.upsertPersonBookmark(testBookmark())
        dao.updatePersonBookmarkLastSeen(PersonBookmarkKind.AUTHOR, "author-test-123", 5000L, 5000L)
        val found = dao.getPersonBookmark(PersonBookmarkKind.AUTHOR, "author-test-123")
        assertNotNull(found)
        assertEquals(5000L, found!!.lastSeenAt)
    }

    @Test
    fun `updateLastNotified updates timestamp and aggregate`() = runBlocking {
        dao.upsertPersonBookmark(testBookmark())
        dao.updatePersonBookmarkLastNotified(PersonBookmarkKind.AUTHOR, "author-test-123", 6000L, 3)
        val found = dao.getPersonBookmark(PersonBookmarkKind.AUTHOR, "author-test-123")
        assertNotNull(found)
        assertEquals(6000L, found!!.lastNotifiedAt)
        assertEquals(3, found.lastNotifiedCount)
    }

    @Test
    fun `observePersonBookmark emits the bookmark or null`() = runBlocking {
        val flow = dao.observePersonBookmark(PersonBookmarkKind.AUTHOR, "author-test-123")
        assertNull(flow.first())

        dao.upsertPersonBookmark(testBookmark())
        val found = flow.first()
        assertNotNull(found)
        assertEquals("Тестовий Автор", found!!.displayName)
    }

    @Test
    fun `notifyEnabled defaults to true on insert`() = runBlocking {
        dao.upsertPersonBookmark(testBookmark())
        val found = dao.getPersonBookmark(PersonBookmarkKind.AUTHOR, "author-test-123")
        assertTrue(found!!.notifyEnabled)
    }

    @Test
    fun `bookmark survives closing and reopening the database`() = runBlocking {
        val databaseName = "person-bookmarks-process-death.db"
        context.deleteDatabase(databaseName)
        try {
            val firstDatabase = Room.databaseBuilder(context, AudiobookDatabase::class.java, databaseName)
                .allowMainThreadQueries()
                .build()
            val person = PersonBookmarks(firstDatabase.audiobookDao())
            person.toggleAuthor("Леся Українка", nowMs = 1_000L)
            firstDatabase.close()

            val reopenedDatabase = Room.databaseBuilder(context, AudiobookDatabase::class.java, databaseName)
                .allowMainThreadQueries()
                .build()
            try {
                val reopenedModule = PersonBookmarks(reopenedDatabase.audiobookDao())
                val bookmarks = reopenedModule.bookmarkedAuthors().first()
                assertEquals(1, bookmarks.size)
                assertEquals("Леся Українка", bookmarks.single().displayName)
                assertEquals(PersonRole.AUTHOR.storageValue, bookmarks.single().kind)
            } finally {
                reopenedDatabase.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun `new edition gets discovery time and refresh preserves it`() = runBlocking {
        val beforeInsert = System.currentTimeMillis()
        dao.insertEdition(EditionEntity(id = "edition-new", workId = "work-new"))
        val discoveredAt = dao.getEditionById("edition-new")!!.addedAt
        assertTrue(discoveredAt >= beforeInsert)

        dao.insertEdition(
            EditionEntity(id = "edition-new", workId = "work-new", addedAt = discoveredAt + 10_000L)
        )
        assertEquals(discoveredAt, dao.getEditionById("edition-new")!!.addedAt)
    }

    @Test
    fun `two concurrent toggles leave bookmark removed`() = runBlocking {
        val person = PersonIdentity.from(PersonRole.AUTHOR, "Тарас Шевченко")
        coroutineScope {
            listOf(
                async { PersonBookmarks(dao).toggle(person, nowMs = 1_000L) },
                async { PersonBookmarks(dao).toggle(person, nowMs = 2_000L) }
            ).awaitAll()
        }
        assertNull(dao.getPersonBookmark(person.role.storageValue, person.id))
    }
}
