package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.components.paperink.PaperInkDimens
import com.slukhayka.audiobooks.ui.components.paperink.PaperInkHeroCard
import com.slukhayka.audiobooks.ui.theme.PaperInkTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * #1675 — QA on device (Ink, font_scale 2.0): the «Продовжити слухати» hero was
 * a FIXED-height card, so the growing title/author/narrator ran past the card's
 * right edge, got clipped and slid under the play button.
 *
 * The card now grows from the same minimum height and reserves the play corner
 * for text at large type. This test pins the visual facts instead of a
 * screenshot: every text node stays INSIDE the card and the play button does
 * not intersect any of them; the interactive target keeps its 48 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListenHeroFontScaleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun render(fontScale: Float) {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale)
            ) {
                // The hero is a «Папір і голос» block, so it renders under its
                // own theme (same as on the Listen tab).
                PaperInkTheme(darkTheme = true) {
                    Box(modifier = Modifier.width(360.dp)) {
                        PaperInkHeroCard(
                            title = "Ім'я вітру",
                            author = "Патрік Ротфус",
                            narrator = "Наратор: Олександр Бордун",
                            chapterLabel = "Розділ 1 з 24",
                            remainingLabel = "Залишилось 3 год 12 хв",
                            progress = 0.4f,
                            progressLabel = "40 %",
                            onPlayClick = {},
                            modifier = Modifier.testTag("hero_card"),
                            eyebrow = "Продовжити слухати",
                            eyebrowHeading = true,
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun DpRect.intersects(other: DpRect, tolerance: Dp = 1.dp): Boolean =
        left < other.right - tolerance &&
            other.left < right - tolerance &&
            top < other.bottom - tolerance &&
            other.top < bottom - tolerance

    private fun assertInside(inner: DpRect, outer: DpRect, name: String) {
        assertTrue("$name is clipped at the left: $inner vs $outer", inner.left >= outer.left - 1.dp)
        assertTrue("$name is clipped at the top: $inner vs $outer", inner.top >= outer.top - 1.dp)
        assertTrue("$name overflows the right edge: $inner vs $outer", inner.right <= outer.right + 1.dp)
        assertTrue("$name overflows the bottom edge: $inner vs $outer", inner.bottom <= outer.bottom + 1.dp)
    }

    @Test
    fun `hero at 200 percent text keeps every line inside the card and off the play button`() {
        render(fontScale = 2.0f)

        val card = composeTestRule.onNodeWithTag("hero_card").getUnclippedBoundsInRoot()
        val play = composeTestRule.onNodeWithTag("hero_play_button").getUnclippedBoundsInRoot()
        val playHeight = play.bottom - play.top
        assertTrue("the play target lost its 48 dp floor: $playHeight", playHeight >= 48.dp)

        for (tag in listOf("hero_title", "hero_author", "hero_narrator", "hero_chapter")) {
            val node = composeTestRule.onNodeWithTag(tag)
            node.assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertInside(bounds, card, tag)
            assertTrue(
                "$tag renders under the play button: $bounds vs $play",
                !bounds.intersects(play),
            )
        }
    }

    @Test
    fun `normal text keeps the same one-line meta rhythm`() {
        render(fontScale = 1.0f)

        val card = composeTestRule.onNodeWithTag("hero_card").getUnclippedBoundsInRoot()
        // #1675 regression guard: normal type must keep EXACTLY the fixed hero
        // height the golden was recorded with (the growth branch must not leak
        // into the default typography).
        val cardHeight = card.bottom - card.top
        assertTrue(
            "normal-type hero height changed: $cardHeight != ${PaperInkDimens.HeroHeight}",
            cardHeight >= PaperInkDimens.HeroHeight - 1.dp &&
                cardHeight <= PaperInkDimens.HeroHeight + 1.dp,
        )
        for (tag in listOf("hero_title", "hero_author", "hero_narrator", "hero_chapter")) {
            val node = composeTestRule.onNodeWithTag(tag)
            node.assertIsDisplayed()
            assertInside(node.getUnclippedBoundsInRoot(), card, tag)
        }
    }
}
