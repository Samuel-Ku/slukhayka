package com.slukhayka.audiobooks.data.personbookmarks

import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkKey
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * #400 — production test for direct Flow wiring: the PersonBookmarks module's
 * Flows (bookmarkedAuthors, bookmarkedNarrators, counts) update synchronously
 * after toggle() and setNotifyEnabled() — the same contract the screens rely
 * on when collecting directly (ADR-0008, no forwarding StateFlow).
 *
 * Uses [FakeAudiobookDao] — no Robolectric, no Room, no Android dependencies.
 */
class PersonBookmarksFlowWiringTest {

    private lateinit var dao: FakeAudiobookDao
    private lateinit var module: PersonBookmarks

    @Before
    fun setUp() {
        dao = FakeAudiobookDao()
        module = PersonBookmarks(dao)
    }

    @Test
    fun toggleAddReflectsInBookmarkedAuthorsFlow() = runBlocking {
        module.toggleAuthor("Леся Українка")

        val authors = module.bookmarkedAuthors().first()
        assertEquals(1, authors.size)
        assertEquals("Леся Українка", authors[0].displayName)
    }

    @Test
    fun toggleRemoveReflectsInBookmarkedAuthorsFlow() = runBlocking {
        module.toggleAuthor("Леся Українка")
        module.toggleAuthor("Леся Українка")

        val authors = module.bookmarkedAuthors().first()
        assertTrue(authors.isEmpty())
    }

    @Test
    fun toggleNarratorReflectsInBookmarkedNarratorsFlow() = runBlocking {
        module.toggleNarrator("Ада Роговцева")

        val narrators = module.bookmarkedNarrators().first()
        assertEquals(1, narrators.size)
        assertEquals("Ада Роговцева", narrators[0].displayName)
    }

    @Test
    fun mixedAuthorsAndNarratorsReflectInCountsFlow() = runBlocking {
        module.toggleAuthor("Автор 1")
        module.toggleAuthor("Автор 2")
        module.toggleNarrator("Наратор 1")

        val counts = module.counts().first()
        assertEquals(2, counts[PersonRole.AUTHOR])
        assertEquals(1, counts[PersonRole.NARRATOR])
    }

    @Test
    fun setNotifyEnabledReflectsInAuthorsFlow() = runBlocking {
        module.toggleAuthor("Олесь Гончар")
        val id = module.authorId("Олесь Гончар")
        val key = PersonBookmarkKey(PersonRole.AUTHOR, id)

        module.setNotifyEnabled(key, enabled = false)

        val authors = module.bookmarkedAuthors().first()
        assertEquals(1, authors.size)
        assertFalse("notifyEnabled should be false after toggling off", authors[0].notifyEnabled)
    }

    @Test
    fun setNotifyEnabledReEnableReflectsInAuthorsFlow() = runBlocking {
        module.toggleAuthor("Олесь Гончар")
        val id = module.authorId("Олесь Гончар")
        val key = PersonBookmarkKey(PersonRole.AUTHOR, id)

        module.setNotifyEnabled(key, enabled = false)
        module.setNotifyEnabled(key, enabled = true)

        val authors = module.bookmarkedAuthors().first()
        assertEquals(1, authors.size)
        assertTrue("notifyEnabled should be true after re-enabling", authors[0].notifyEnabled)
    }

    @Test
    fun toggleDoesNotRemoveBookmarkWhenOnlyNotifyEnabledChanges() = runBlocking {
        // This is the key test: changing notifyEnabled must NOT delete the bookmark.
        module.toggleAuthor("Тарас Шевченко")
        val id = module.authorId("Тарас Шевченко")
        val key = PersonBookmarkKey(PersonRole.AUTHOR, id)

        // Toggle notify off
        module.setNotifyEnabled(key, enabled = false)

        // Bookmark still exists
        val authors = module.bookmarkedAuthors().first()
        assertEquals("Bookmark must not be deleted when notifyEnabled changes", 1, authors.size)

        // Toggle notify back on
        module.setNotifyEnabled(key, enabled = true)

        // Bookmark still exists
        val authorsAfter = module.bookmarkedAuthors().first()
        assertEquals("Bookmark must not be deleted when notifyEnabled toggles back", 1, authorsAfter.size)
    }

    @Test
    fun markSeenReflectsInAuthorsFlow() = runBlocking {
        module.toggleAuthor("Панас Мирний", nowMs = 1000L)
        val id = module.authorId("Панас Мирний")

        module.markSeen(PersonBookmarkKey(PersonRole.AUTHOR, id), nowMs = 5000L)

        val authors = module.bookmarkedAuthors().first()
        assertEquals(1, authors.size)
        assertEquals(5000L, authors[0].lastSeenAt)
    }
}
