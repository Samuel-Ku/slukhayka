package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.components.paperink.PaperInkPosterCard
import com.slukhayka.audiobooks.ui.theme.PaperInkThemedContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Unslop audit 2026-11-01 (U16) — the «Коротке» shelf drew the paper letter while
 * the same Work had real art on every other surface: `PaperInkPosterCard` had no
 * cover parameter at all, so the placeholder was not a fallback, it was the only
 * branch.
 *
 * This pins the two branches and the thing that makes the fallback honest: the
 * art slot and the letter slot occupy the SAME 132×198 box, so a shelf does not
 * jump when artwork arrives.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-w360dp-h800dp-xhdpi", sdk = [35])
class ListenShelfCoverTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showShelf(withCover: Boolean) {
        compose.setContent {
            PaperInkThemedContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    PaperInkPosterCard(
                        title = "Левеня, яке навчилося ричати",
                        author = "Костянтин Бакаєвич",
                        meta = "01:44",
                        cover = if (withCover) {
                            { Box(modifier = Modifier.fillMaxSize()) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a Work with art renders the artwork, not the letter`() {
        showShelf(withCover = true)

        compose.onNodeWithTag("shelf_cover_art", useUnmergedTree = true).assertExists()
        assertEquals(
            "the letter placeholder must not be drawn when art exists",
            0,
            compose.onAllNodesWithTag("shelf_cover_placeholder", useUnmergedTree = true).fetchSemanticsNodes().size
        )
    }

    @Test
    fun `a Work without art keeps the paper letter`() {
        showShelf(withCover = false)

        compose.onNodeWithTag("shelf_cover_placeholder", useUnmergedTree = true).assertExists()
        assertEquals(
            "nothing may claim art when there is none",
            0,
            compose.onAllNodesWithTag("shelf_cover_art", useUnmergedTree = true).fetchSemanticsNodes().size
        )
    }

    @Test
    fun `art and letter occupy the same slot`() {
        val bounds = mutableListOf<Pair<Float, Float>>()
        showShelf(withCover = true)
        val art = compose.onNodeWithTag("shelf_cover_art", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        bounds += (art.right - art.left).value to (art.bottom - art.top).value
        assertTrue("the art slot is the shelf cover: ${bounds.first()}", bounds.first() == 132f to 198f)

        compose.setContent {
            PaperInkThemedContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    PaperInkPosterCard(
                        title = "Левеня, яке навчилося ричати",
                        author = "Костянтин Бакаєвич",
                        meta = "01:44",
                        cover = null,
                    )
                }
            }
        }
        compose.waitForIdle()
        val letter = compose.onNodeWithTag("shelf_cover_placeholder", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertEquals(
            "the letter slot must match the art slot, or the shelf jumps",
            bounds.first(),
            (letter.right - letter.left).value to (letter.bottom - letter.top).value
        )
        assertTrue("132×198 dp is the shelf cover size", bounds.first() == 132.dp.value to 198.dp.value)
    }
}
