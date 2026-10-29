package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.screens.bookDetailPresentation
import com.slukhayka.audiobooks.ui.screens.bookdetail.BookDetailIdentityHeader
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F31 of the 2026-10-28 device audit — the book page still drew the legacy
 * vertical hero: a centred ~130×202 poster ABOVE the title. The prototype's
 * compact hero puts a 118×177 cover BESIDE the title, and the chapter chip
 * repeated the count («15 15 розділів»).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-w411dp-h891dp", sdk = [35])
class BookDetailCompactHeroTest {

    @get:Rule
    val compose = createComposeRule()

    private val book = AudiobookEntity(
        id = "book-detail-f31",
        title = "Трохи ненависті",
        author = "Джо Аберкромбі",
        narrator = "Олександр Завальський",
        description = "Опис",
        coverDrawableRes = 0,
        coverImageUrl = null,
        genre = "Фентезі",
        sourceUrl = "https://4read.org/trohy-nenavysti",
        totalChapters = 15,
        totalDurationSeconds = 3_600,
    )

    private fun showHero() {
        val presentation = bookDetailPresentation(book, emptyList(), emptyList())
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BookDetailIdentityHeader(book = book, presentation = presentation)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the cover is 118 by 177 and the title sits beside it`() {
        showHero()
        val cover = compose.onNodeWithTag("book_detail_cover_art").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("book_detail_title", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val density = compose.density
        assertEquals("cover width", 118f, cover.width / density.density, 1f)
        assertEquals("cover height", 177f, cover.height / density.density, 1f)
        assertTrue(
            "the title must start to the RIGHT of the cover: title.left=${title.left} cover.right=${cover.right}",
            title.left >= cover.right,
        )
        assertTrue(
            "the title must overlap the cover's vertical band, not sit below it",
            title.top < cover.bottom,
        )
    }

    @Test
    fun `the chapter chip carries the count once`() {
        showHero()
        compose.onNodeWithText("15 15 розділів", substring = true).assertDoesNotExist()
        compose.onNodeWithText("15 розділів", substring = true).assertExists()
    }
}
