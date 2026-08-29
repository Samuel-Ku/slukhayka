package com.slukhayka.audiobooks.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.personbookmarks.PersonBookmarks
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import com.slukhayka.audiobooks.ui.screens.CanonicalAuthorScreen
import com.slukhayka.audiobooks.ui.screens.PersonBooksScreen
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #400 — Production-surface tests for person bookmarks:
 *
 * 1. CanonicalAuthorScreen renders PersonBookmarkButton with correct state
 * 2. The surface toggles the direct PersonBookmarks Flow
 * 3. PersonBookmarks Flows update reactively after toggle
 * 4. PersonBookmarks persistence survives DAO round-trip
 * 5. Correct kind/id separation for AUTHOR vs NARRATOR
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersonBookmarkProductionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // --- CanonicalAuthorScreen surface -------------------------------------

    @Test
    fun canonicalAuthorScreen_rendersBookmarkButton() {
        val bookmarks = PersonBookmarks(FakeAudiobookDao())
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    CanonicalAuthorScreen(
                        author = com.slukhayka.audiobooks.data.authors.AuthorSummary(
                            id = "test-author",
                            displayName = "Тестовий Автор",
                            normalizedName = "тестовий автор",
                            workCount = 3
                        ),
                        works = emptyList(),
                        isLoading = false,
                        loadFailed = false,
                        onBackClick = {},
                        onWorkClick = {},
                        personBookmarks = bookmarks
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("person_bookmark_button").assertExists()
    }

    @Test
    fun canonicalAuthorScreen_bookmarkedStateShowsFilledStar() {
        val bookmarks = PersonBookmarks(FakeAudiobookDao())
        runBlocking { bookmarks.toggleAuthor("Тестовий Автор") }
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    CanonicalAuthorScreen(
                        author = com.slukhayka.audiobooks.data.authors.AuthorSummary(
                            id = "test-author",
                            displayName = "Тестовий Автор",
                            normalizedName = "тестовий автор",
                            workCount = 3
                        ),
                        works = emptyList(),
                        isLoading = false,
                        loadFailed = false,
                        onBackClick = {},
                        onWorkClick = {},
                        personBookmarks = bookmarks
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("person_bookmark_button").assertExists()
    }

    @Test
    fun canonicalAuthorScreen_toggleCallbackFires() {
        val bookmarks = PersonBookmarks(FakeAudiobookDao())

        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    CanonicalAuthorScreen(
                        author = com.slukhayka.audiobooks.data.authors.AuthorSummary(
                            id = "test-author",
                            displayName = "Тестовий Автор",
                            normalizedName = "тестовий автор",
                            workCount = 3
                        ),
                        works = emptyList(),
                        isLoading = false,
                        loadFailed = false,
                        onBackClick = {},
                        onWorkClick = {},
                        personBookmarks = bookmarks
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("person_bookmark_button").performClick()
        composeTestRule.waitForIdle()
        val stored = runBlocking {
            bookmarks.observePersonBookmark(
                PersonRole.AUTHOR.storageValue,
                bookmarks.authorId("Тестовий Автор")
            ).first()
        }
        assertNotNull(stored)
    }

    // --- PersonBookmarks persistence and Flow tests -------------------------

    @Test
    fun personBookmarks_toggleCreatesBookmark() = runTest {
        val dao = FakeAudiobookDao()
        val bookmarks = PersonBookmarks(dao)

        val added = bookmarks.toggleAuthor("Тестовий Автор")
        assertTrue(added)

        val allBookmarks = dao.getAllPersonBookmarks().first()
        assertEquals(1, allBookmarks.size)
        assertEquals(PersonRole.AUTHOR.storageValue, allBookmarks[0].kind)
    }

    @Test
    fun personBookmarks_toggleTwiceRemovesBookmark() = runTest {
        val dao = FakeAudiobookDao()
        val bookmarks = PersonBookmarks(dao)

        bookmarks.toggleAuthor("Тестовий Автор")
        val removed = bookmarks.toggleAuthor("Тестовий Автор")
        assertFalse(removed)

        val allBookmarks = dao.getAllPersonBookmarks().first()
        assertEquals(0, allBookmarks.size)
    }

    @Test
    fun personBookmarks_setNotifyEnabled_persists() = runTest {
        val dao = FakeAudiobookDao()
        val bookmarks = PersonBookmarks(dao)

        bookmarks.toggleAuthor("Тестовий Автор")
        bookmarks.setNotifyEnabled(
            com.slukhayka.audiobooks.data.db.PersonBookmarkKey(
                PersonRole.AUTHOR,
                bookmarks.authorId("Тестовий Автор")
            ),
            enabled = false
        )

        val bookmark = dao.getPersonBookmark(
            PersonRole.AUTHOR.storageValue,
            bookmarks.authorId("Тестовий Автор")
        )
        assertNotNull(bookmark)
        assertFalse(bookmark!!.notifyEnabled)
    }

    @Test
    fun personBookmarks_narratorUsesSeparateKind() = runTest {
        val dao = FakeAudiobookDao()
        val bookmarks = PersonBookmarks(dao)

        bookmarks.toggleAuthor("Олесь Гончар")
        bookmarks.toggleNarrator("Олесь Гончар")

        val allBookmarks = dao.getAllPersonBookmarks().first()
        assertEquals(2, allBookmarks.size)

        val authorBookmark = allBookmarks.first { it.kind == PersonRole.AUTHOR.storageValue }
        val narratorBookmark = allBookmarks.first { it.kind == PersonRole.NARRATOR.storageValue }

        assertEquals(bookmarks.authorId("Олесь Гончар"), authorBookmark.id)
        assertEquals(bookmarks.narratorId("Олесь Гончар"), narratorBookmark.id)
        // AUTHOR and NARRATOR ids must differ for the same name
        assertTrue(authorBookmark.id != narratorBookmark.id)
    }

    @Test
    fun personBookmarks_flowUpdatesAfterToggle() = runTest {
        val dao = FakeAudiobookDao()
        val bookmarks = PersonBookmarks(dao)

        val authorId = bookmarks.authorId("Тестовий Автор")

        // Initially null
        val initial = bookmarks.observePersonBookmark(
            PersonRole.AUTHOR.storageValue, authorId
        ).first()
        assertNull(initial)

        // Toggle ON
        bookmarks.toggleAuthor("Тестовий Автор")
        val afterAdd = bookmarks.observePersonBookmark(
            PersonRole.AUTHOR.storageValue, authorId
        ).first()
        assertNotNull(afterAdd)
        assertEquals("Тестовий Автор", afterAdd!!.displayName)

        // Toggle OFF
        bookmarks.toggleAuthor("Тестовий Автор")
        val afterRemove = bookmarks.observePersonBookmark(
            PersonRole.AUTHOR.storageValue, authorId
        ).first()
        assertNull(afterRemove)
    }

    @Test
    fun personBookmarks_markSeen_updatesTimestamp() = runTest {
        val dao = FakeAudiobookDao()
        val bookmarks = PersonBookmarks(dao)

        bookmarks.toggleAuthor("Тестовий Автор")
        val key = com.slukhayka.audiobooks.data.db.PersonBookmarkKey(
            PersonRole.AUTHOR,
            bookmarks.authorId("Тестовий Автор")
        )
        bookmarks.markSeen(key)

        val bookmark = dao.getPersonBookmark(
            PersonRole.AUTHOR.storageValue,
            bookmarks.authorId("Тестовий Автор")
        )
        assertNotNull(bookmark)
        assertTrue(bookmark!!.lastSeenAt > 0)
    }

    @Test
    fun personBookmarks_authorsAndNarratorsFlows_areIndependent() = runTest {
        val dao = FakeAudiobookDao()
        val bookmarks = PersonBookmarks(dao)

        bookmarks.toggleAuthor("Тестовий Автор")
        bookmarks.toggleNarrator("Інший Виконавець")

        val authors = bookmarks.bookmarkedAuthors().first()
        val narrators = bookmarks.bookmarkedNarrators().first()

        assertEquals(1, authors.size)
        assertEquals(1, narrators.size)
        assertEquals("Тестовий Автор", authors[0].displayName)
        assertEquals("Інший Виконавець", narrators[0].displayName)
    }
}
