package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.ui.library.LibraryFilter
import com.slukhayka.audiobooks.ui.library.LibrarySort
import com.slukhayka.audiobooks.ui.library.buildLibraryBooks
import com.slukhayka.audiobooks.ui.screens.LibraryDenseRow
import com.slukhayka.audiobooks.ui.screens.LibraryEmptyState
import com.slukhayka.audiobooks.ui.screens.LibraryFilterSheetContent
import com.slukhayka.audiobooks.ui.screens.LibraryImportSheetContent
import com.slukhayka.audiobooks.ui.screens.LibraryStatusRow
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec-46 T08 (#569) — the EN run of the Медіатека chrome must be clean.
 *
 * The ticket's third acceptance criterion is «UK-хром Медіатеки повністю з
 * ресурсів; EN-прогін чистий». Asserting a handful of English labels would
 * still pass with one hardcoded Ukrainian string left in a corner, so the test
 * walks the WHOLE semantics tree and fails on any Cyrillic in the chrome. The
 * row fixture uses Latin-only data, so a failure can only come from chrome the
 * app itself produced, never from a title.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS", sdk = [36])
class LibraryEnglishChromeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val cyrillic = Regex("[А-Яа-яІіЇїЄєҐґ]")

    @Test
    fun filterSheetStatusRowAndImportSheetSpeakEnglish() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column {
                        LibraryStatusRow(selected = LibraryFilter.ALL, onSelect = {})
                        LibraryFilterSheetContent(
                            filter = LibraryFilter.LOCAL,
                            sort = LibrarySort.TITLE,
                            gridMode = false,
                            onFilterChange = {},
                            onSortChange = {},
                            onGridModeChange = {}
                        )
                        LibraryImportSheetContent(onImportFile = {}, onImportFolder = {})
                        LibraryEmptyState(onImportClick = {}, onBrowseClick = {})
                    }
                }
            }
        }

        assertChromeHasNoCyrillic()
        // The key labels really resolve to EN resources. `assertExists`, not
        // `assertIsDisplayed`: the sheet content is taller than the test
        // window, and a node scrolled past the fold is still EN chrome.
        composeTestRule.onNodeWithText("Filter").assertExists()
        composeTestRule.onNodeWithText("Sorting").assertExists()
        composeTestRule.onNodeWithText("List").assertExists()
        composeTestRule.onNodeWithText("Grid").assertExists()
        composeTestRule.onNodeWithText("Add audio").assertExists()
        composeTestRule.onNodeWithText("All").assertExists()
    }

    @Test
    fun theDenseRowChromeSpeaksEnglish() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    LibraryDenseRow(
                        book = englishRow,
                        availability = null,
                        onRecheck = {},
                        downloadCount = null,
                        onOpen = {}
                    )
                }
            }
        }

        assertChromeHasNoCyrillic()
        // The series volume word is chrome: «Книга 2» must read «Book 2» in EN.
        // The row's text column clears its own semantics, so the label only
        // exists in the unmerged tree.
        composeTestRule
            .onNodeWithText("Saga · Book 2", substring = true, useUnmergedTree = true)
            .assertExists()
    }

    private fun assertChromeHasNoCyrillic() {
        val texts = collectTexts(composeTestRule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        val leaked = texts.filter { cyrillic.containsMatchIn(it) }
        assertTrue("Ukrainian chrome leaked into the EN run: $leaked", leaked.isEmpty())
    }

    private fun collectTexts(node: SemanticsNode): List<String> {
        val out = mutableListOf<String>()
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
        node.config.getOrNull(SemanticsProperties.StateDescription)?.let { out += it }
        node.children.forEach { out += collectTexts(it) }
        return out
    }

    /** Latin-only data: any Cyrillic left in the tree is chrome, not content. */
    private val englishRow = run {
        val entity = AudiobookEntity(
            id = "en-row",
            title = "Dune",
            author = "Frank Herbert",
            narrator = "A Narrator",
            description = "",
            coverDrawableRes = 0,
            genre = "fiction",
            sourceUrl = "",
            totalDurationSeconds = 36_000L,
            totalChapters = 0
        ).apply {
            seriesTitle = "Saga"
            seriesIndex = 2
        }
        buildLibraryBooks(
            books = listOf(entity),
            progressList = listOf(
                PlaybackProgressEntity(
                    editionId = "edition-en-row",
                    bookId = "en-row",
                    currentChapterIndex = 0,
                    currentPositionSeconds = 1_800L,
                    lastListenedAt = 1L,
                    isCompleted = false
                )
            ),
            chaptersByBook = emptyMap()
        ).single()
    }
}
