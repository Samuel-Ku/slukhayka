package com.slukhayka.audiobooks.data.personbookmarks

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkKey
import com.slukhayka.audiobooks.data.db.PersonBookmarkKind
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #399 — JVM tests for [PersonBookmarks]: narratorId deterministic identity,
 * toggle, setNotifyEnabled, markSeen, and Flows.
 *
 * Uses [FakeAudiobookDao] — no Robolectric, no Room, no Android dependencies.
 */
class PersonBookmarksTest {

    private lateinit var dao: AudiobookDao
    private lateinit var module: PersonBookmarks

    @Before
    fun setUp() {
        dao = FakeAudiobookDao()
        module = PersonBookmarks(dao)
    }

    // --- narratorId deterministic identity --------------------------------

    @Test
    fun `narratorId is deterministic for the same name`() {
        val id1 = module.narratorId("  Ігор Петренко  ")
        val id2 = module.narratorId("ігор петренко")
        assertEquals(id1, id2)
    }

    @Test
    fun `narratorId normalises Ukrainian case whitespace and apostrophe variants`() {
        val straight = module.narratorId("  О'ГЕНРІ  ")
        val curly = module.narratorId("О\u2019Генрі")
        val modifier = module.narratorId("О\u02BCГенрі")
        assertEquals(straight, curly)
        assertEquals(straight, modifier)
    }

    @Test
    fun `narratorId differs from authorId for the same name`() {
        val authorId = module.authorId("Шевченко")
        val narratorId = module.narratorId("Шевченко")
        assertNotEquals(
            "author:шевченко must not equal narrator:шевченко",
            authorId,
            narratorId
        )
    }

    @Test
    fun `narratorId keeps Cyrillic diacritics case folded but not discarded`() {
        val upper = module.narratorId("ҐАБРІЄЛЬ ГАРСІЯ МАРКЕС")
        val lower = module.narratorId("ґабрієль гарсія маркес")
        assertEquals(upper, lower)
    }

    @Test
    fun `narratorId never merges Latin and Cyrillic lookalikes`() {
        val cyrillic = module.narratorId("Андрій Кокотюха")
        val latinLookalike = module.narratorId("Aндрій Кокотюха")
        assertNotEquals(cyrillic, latinLookalike)
    }

    // --- toggle ------------------------------------------------------------

    @Test
    fun `toggle adds a new bookmark and returns true`() = runBlocking {
        val added = module.toggleAuthor("Тарас Шевченко")
        assertTrue(added)
        val bookmarks = dao.getAllPersonBookmarks().first()
        assertEquals(1, bookmarks.size)
        assertEquals(PersonBookmarkKind.AUTHOR, bookmarks[0].kind)
        assertEquals("Тарас Шевченко", bookmarks[0].displayName)
        assertTrue(bookmarks[0].notifyEnabled)
    }

    @Test
    fun `toggle removes an existing bookmark and returns false`() = runBlocking {
        module.toggleAuthor("Тарас Шевченко")
        val removed = module.toggleAuthor("Тарас Шевченко")
        assertFalse(removed)
        val bookmarks = dao.getAllPersonBookmarks().first()
        assertTrue(bookmarks.isEmpty())
    }

    @Test
    fun `toggle narrator uses a different id than author with the same name`() = runBlocking {
        module.toggleAuthor("Шевченко")
        module.toggleNarrator("Шевченко")
        val bookmarks = dao.getAllPersonBookmarks().first()
        assertEquals(2, bookmarks.size)
        val ids = bookmarks.map { it.id }.toSet()
        assertEquals("author and narrator ids must differ", 2, ids.size)
    }

    // --- setNotifyEnabled --------------------------------------------------

    @Test
    fun `setNotifyEnabled disables notifications for a bookmarked person`() = runBlocking {
        module.toggleAuthor("Олесь Гончар")
        module.setNotifyEnabled(
            PersonBookmarkKey(PersonRole.AUTHOR, module.authorId("Олесь Гончар")),
            enabled = false
        )
        val bookmark = dao.getPersonBookmark(
            PersonBookmarkKind.AUTHOR,
            module.authorId("Олесь Гончар")
        )
        assertNotNull(bookmark)
        assertFalse(bookmark!!.notifyEnabled)
    }

    @Test
    fun `setNotifyEnabled re-enables after disabling`() = runBlocking {
        module.toggleAuthor("Олесь Гончар")
        val id = module.authorId("Олесь Гончар")
        val key = PersonBookmarkKey(PersonRole.AUTHOR, id)
        module.setNotifyEnabled(key, enabled = false)
        module.setNotifyEnabled(key, enabled = true)
        val bookmark = dao.getPersonBookmark(PersonBookmarkKind.AUTHOR, id)
        assertNotNull(bookmark)
        assertTrue(bookmark!!.notifyEnabled)
    }

    @Test
    fun `setNotifyEnabled is a no-op when person is not bookmarked`() = runBlocking {
        // Should not throw
        module.setNotifyEnabled(
            PersonBookmarkKey(PersonRole.AUTHOR, "nonexistent-id"),
            enabled = false
        )
        // Still empty
        val bookmarks = dao.getAllPersonBookmarks().first()
        assertTrue(bookmarks.isEmpty())
    }

    // --- markSeen ----------------------------------------------------------

    @Test
    fun `markSeen updates lastSeenAt`() = runBlocking {
        module.toggleAuthor("Панас Мирний", nowMs = 1000L)
        val id = module.authorId("Панас Мирний")
        module.markSeen(PersonBookmarkKey(PersonRole.AUTHOR, id), nowMs = 5000L)
        val bookmark = dao.getPersonBookmark(PersonBookmarkKind.AUTHOR, id)
        assertNotNull(bookmark)
        assertEquals(5000L, bookmark!!.lastSeenAt)
    }

    @Test
    fun `markSeen is a no-op when person is not bookmarked`() = runBlocking {
        module.markSeen(PersonBookmarkKey(PersonRole.AUTHOR, "nonexistent"), nowMs = 5000L)
        // Should not throw
    }

    // --- Flows -------------------------------------------------------------

    @Test
    fun `bookmarkedAuthors returns only authors`() = runBlocking {
        module.toggleAuthor("Автор 1")
        module.toggleNarrator("Виконавець 1")
        val authors = module.bookmarkedAuthors().first()
        assertEquals(1, authors.size)
        assertEquals(PersonBookmarkKind.AUTHOR, authors[0].kind)
    }

    @Test
    fun `bookmarkedNarrators returns only narrators`() = runBlocking {
        module.toggleAuthor("Автор 1")
        module.toggleNarrator("Виконавець 1")
        val narrators = module.bookmarkedNarrators().first()
        assertEquals(1, narrators.size)
        assertEquals(PersonBookmarkKind.NARRATOR, narrators[0].kind)
    }

    @Test
    fun `counts returns correct per-kind counts`() = runBlocking {
        module.toggleAuthor("Автор 1")
        module.toggleAuthor("Автор 2")
        module.toggleNarrator("Виконавець 1")
        val counts = module.counts().first()
        assertEquals(2, counts[PersonRole.AUTHOR])
        assertEquals(1, counts[PersonRole.NARRATOR])
    }

    @Test
    fun `counts returns empty map when no bookmarks exist`() = runBlocking {
        val counts = module.counts().first()
        assertTrue(counts.isEmpty())
    }
}
