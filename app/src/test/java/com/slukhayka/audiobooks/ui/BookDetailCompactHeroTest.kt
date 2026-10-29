package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
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

    private fun showHero(
        narrationAverage: Double? = null,
        narrationVoteCount: Int = 0,
        canRateNarration: Boolean = false,
        ownNarrationRating: Int? = null,
        width: Int? = null,
        fontScale: Float = 1f,
    ) {
        val presentation = bookDetailPresentation(book, emptyList(), emptyList())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = fontScale)
            ) {
                AudiobookTheme(darkTheme = true) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(
                            modifier = if (width != null) Modifier.width(width.dp) else Modifier.fillMaxSize()
                        ) {
                            BookDetailIdentityHeader(
                                book = book,
                                presentation = presentation,
                                narrationAverage = narrationAverage,
                                narrationVoteCount = narrationVoteCount,
                                canRateNarration = canRateNarration,
                                ownNarrationRating = ownNarrationRating,
                            )
                        }
                    }
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

    // F31 review blocker: the rating line lives in the hero's ~182 dp column
    // on a 360 dp phone. With real narration data («Оцінка начитки + stars +
    // count» ≈220 dp) the centred row clipped; the compact variant wraps and
    // starts at the column's start edge. The previous tests passed only
    // because they passed no narration data at all.
    @Test
    fun `the narration rating fits the hero column at 360 dp and two hundred percent text`() {
        showHero(
            narrationAverage = 4.6,
            narrationVoteCount = 128,
            canRateNarration = true,
            width = 360,
            fontScale = 2f,
        )
        val row = compose.onNodeWithTag("narration_rating_row").fetchSemanticsNode().boundsInRoot
        val average = compose.onNodeWithTag("narration_rating_average", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("book_detail_cover").fetchSemanticsNode().boundsInRoot
        val cover = compose.onNodeWithTag("book_detail_cover_art").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the rating row must stay inside the page: row.right=${row.right} page.right=${root.right}",
            row.right <= root.right + 1f,
        )
        assertTrue(
            "the average label must stay inside the hero column: average.right=${average.right} row.right=${row.right}",
            average.right <= row.right + 1f,
        )
        // Start-aligned: the row begins at the summary column, right of the cover.
        assertTrue(
            "the rating row must start after the cover: row.left=${row.left} cover.right=${cover.right}",
            row.left >= cover.right,
        )
    }

    @Test
    fun `the narration rating stays start aligned at normal text size`() {
        showHero(narrationAverage = 4.6, narrationVoteCount = 12, canRateNarration = true, width = 411)
        val row = compose.onNodeWithTag("narration_rating_row").fetchSemanticsNode().boundsInRoot
        val cover = compose.onNodeWithTag("book_detail_cover_art").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the rating row must sit to the right of the cover: row.left=${row.left} cover.right=${cover.right}",
            row.left >= cover.right,
        )
    }


    // F31 review blocker (round 2): five interactive stars are five 48 dp
    // boxes = 240 dp, a single FlowRow item that never wrapped — in the hero's
    // 182 dp column the 4th star clamped and the 5th disappeared. The compact
    // row now lays them out 3+2, keeping every 48 dp target.
    @Test
    fun `all five interactive stars stay on the page at 360 dp and two hundred percent text`() {
        showHero(
            narrationAverage = 4.6,
            narrationVoteCount = 128,
            canRateNarration = true,
            ownNarrationRating = 4,
            width = 360,
            fontScale = 2f,
        )
        val page = compose.onNodeWithTag("book_detail_cover").fetchSemanticsNode().boundsInRoot
        val star5 = compose.onNodeWithTag("rating_star_5").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("rating_star_5").assertIsDisplayed()
        assertTrue(
            "the fifth star must stay inside the page: star5.right=${star5.right} page.right=${page.right}",
            star5.right <= page.right + 1f,
        )
        // The 48 dp touch target is preserved even though the glyph is 12 dp.
        val density = compose.density
        assertTrue(
            "the star keeps its 48 dp target, was ${star5.height / density.density} dp",
            star5.height >= with(density) { 48.dp.toPx() } - 1f,
        )
    }

    @Test
    fun `an own rating state still renders all five interactive stars`() {
        showHero(
            narrationAverage = 4.6,
            narrationVoteCount = 12,
            canRateNarration = true,
            ownNarrationRating = 4,
            width = 411,
        )
        (1..5).forEach { position ->
            compose.onNodeWithTag("rating_star_$position").assertIsDisplayed()
        }
    }

}
