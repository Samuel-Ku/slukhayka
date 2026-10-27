package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.ui.screens.bookdetail.ChapterRowItem
import com.slukhayka.audiobooks.ui.theme.PaperInkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Plan 2026-2027 — the book page's chapter row on the direction's paper: a flat
 * row with a rule, a quiet number, and the lamp marking the chapter being
 * heard. The interaction/a11y contract is pinned separately by
 * [com.slukhayka.audiobooks.ui.BookDetailChapterBookmarkAccessibilityTest].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class BookDetailChapterRowSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val current = ChapterEntity(
        id = "ch-5",
        bookId = "book",
        chapterIndex = 4,
        title = "Розділ 5. Сходами вниз",
        durationSeconds = 2_940L,
    )
    private val next = ChapterEntity(
        id = "ch-6",
        bookId = "book",
        chapterIndex = 5,
        title = "Розділ 6. Довга назва, яку видно повністю",
        durationSeconds = 3_180L,
    )

    @Test
    fun chapter_rows_on_paper() {
        composeTestRule.setContent {
            PaperInkTheme(darkTheme = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(modifier = Modifier.padding(vertical = 12.dp)) {
                        ChapterRowItem(
                            chapter = current,
                            index = 4,
                            isCurrent = true,
                            isPlaying = true,
                            onPlayClick = {},
                            onPauseClick = {},
                        )
                        ChapterRowItem(
                            chapter = next,
                            index = 5,
                            isCurrent = false,
                            isPlaying = false,
                            onPlayClick = {},
                            onPauseClick = {},
                        )
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("book_detail_chapter_ch-5").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/book_detail_chapter_rows_paper.png"
        )
    }
}
