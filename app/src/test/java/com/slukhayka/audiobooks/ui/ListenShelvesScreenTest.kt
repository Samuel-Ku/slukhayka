package com.slukhayka.audiobooks.ui

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.library.LibraryBook
import com.slukhayka.audiobooks.ui.library.ListenPrefsStore
import com.slukhayka.audiobooks.ui.library.ListenShelvesModule
import com.slukhayka.audiobooks.ui.library.ResourceListenCopy
import com.slukhayka.audiobooks.ui.library.buildLibraryBooks
import com.slukhayka.audiobooks.ui.screens.ListenScreen
import com.slukhayka.audiobooks.ui.theme.PaperInkTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class ListenShelvesScreenTest {
    @get:Rule val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val books = MutableSharedFlow<List<LibraryBook>>(replay = 1)
    private val book = TestDataFactory.dataBooks().first().copy(isDownloaded = true, totalDurationSeconds = 3600L).apply {
        isFavorite = false
        createdAt = TestDataFactory.FIXED_CLOCK_MS
    }
    private val now = book.createdAt + 30L * 24 * 3600 * 1000
    private val heroIds = mutableListOf<String?>()

    private fun showScreen() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val shelves = remember(scope) {
                ListenShelvesModule(
                    scope = scope,
                    composedBooks = books,
                    nextInSeries = flowOf(null),
                    finishedWorkIds = flowOf(emptySet()),
                    prefs = ListenPrefsStore(app),
                    copyForApp = { ResourceListenCopy.forApp(app) },
                    now = { now },
                )
            }
            PaperInkTheme(darkTheme = true) {
                ListenScreen(
                    shelves = shelves,
                    onHeroChanged = { heroIds.add(it?.id) },
                    onBookClick = {},
                    onPlayClick = {},
                    onBrowseClick = {},
                    onImportClick = {},
                    onDismissBook = shelves::dismissBook,
                )
            }
        }
    }

    private fun publish(library: List<LibraryBook>) = compose.runOnIdle { check(books.tryEmit(library)) }
    private fun click(tag: String) = compose.onNodeWithTag(tag).performClick()
    private fun openSheet() = click("listen_manage_shelves")
    private fun closeSheet() = compose.onNodeWithContentDescription(app.getString(R.string.a11y_listen_shelves_close)).performClick()

    @Test
    fun `shelf controls change the rendered winner while both raw rows remain manageable`() {
        showScreen()
        publish(buildLibraryBooks(listOf(book), emptyList(), emptyMap()))

        fun assertWinner(visible: String, absent: String) {
            compose.onNodeWithTag("listen_block_heading_$visible").assertIsDisplayed()
            compose.onNodeWithTag("listen_block_heading_$absent").assertDoesNotExist()
            compose.onNodeWithTag("compact_book_${book.id}", useUnmergedTree = true).assertIsDisplayed()
        }
        assertWinner("TRAVEL", "SHORT")
        openSheet()
        compose.onNodeWithTag("listen_shelf_row_TRAVEL").assertIsDisplayed()
        compose.onNodeWithTag("listen_shelf_row_SHORT").assertIsDisplayed()
        click("listen_shelf_toggle_TRAVEL")
        closeSheet()
        assertWinner("SHORT", "TRAVEL")

        openSheet()
        click("listen_shelves_restore_all")
        closeSheet()
        assertWinner("TRAVEL", "SHORT")
        openSheet()
        click("listen_shelf_up_SHORT")
        closeSheet()
        assertWinner("SHORT", "TRAVEL")

        openSheet()
        click("listen_shelf_toggle_SHORT")
        click("listen_shelf_toggle_TRAVEL")
        closeSheet()
        compose.onNodeWithText(app.getString(R.string.listen_all_shelves_hidden_title)).assertIsDisplayed()
        compose.onNodeWithTag("compact_book_${book.id}", useUnmergedTree = true).assertDoesNotExist()
        openSheet()
        click("listen_shelves_restore_all")
        closeSheet()
        assertWinner("SHORT", "TRAVEL")
    }

    @Test
    fun `series lookup follows the raw hero id across progress and visibility changes`() {
        showScreen()
        fun assertLookups(vararg ids: String?) = compose.runOnIdle { assertEquals(ids.toList(), heroIds) }
        assertLookups(null)
        val progress = TestDataFactory.seedPlaybackProgress(listOf(book)).single().copy(lastListenedAt = now)
        val hero = LibraryBook(book, progress, cumulativePositionSeconds = 30L, totalDurationSeconds = 3600L)
        publish(listOf(hero))
        compose.onNodeWithTag("listen_hero_panel", useUnmergedTree = true).assertIsDisplayed()
        assertLookups(null, book.id)

        openSheet()
        click("listen_shelf_toggle_HERO")
        closeSheet()
        compose.onNodeWithTag("listen_hero_panel", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("compact_book_${book.id}", useUnmergedTree = true).assertIsDisplayed()
        publish(listOf(hero.copy(cumulativePositionSeconds = 60L)))
        assertLookups(null, book.id)

        publish(emptyList())
        compose.onNodeWithText(app.getString(R.string.listen_empty_title)).assertIsDisplayed()
        assertLookups(null, book.id, null)
    }
}
