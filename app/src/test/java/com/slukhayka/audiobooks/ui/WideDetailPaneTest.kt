package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.slukhayka.audiobooks.ui.components.WideDetailPane
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #900 — «список ліворуч + сторінка вибраної книги праворуч» as geometry.
 *
 * The panes here are placeholders: this composable owns no screen content
 * (the real LibraryScreen and BookDetailScreen are wired into it by the
 * composition root). What is asserted is the LAYOUT contract the owner asked
 * for — both panes present, the list on the left, the page on the right, and
 * the list keeping the smaller share.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-w840dp-h1000dp-420dpi", sdk = [36])
class WideDetailPaneTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setPane() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                WideDetailPane(
                    list = {
                        Box(Modifier.fillMaxSize().testTag("fake_list")) {
                            Text("Список книг")
                        }
                    },
                    detail = {
                        Box(Modifier.fillMaxSize().testTag("fake_detail")) {
                            Text("Сторінка книги")
                        }
                    }
                )
            }
        }
    }

    @Test
    fun bothPanesAreRendered() {
        setPane()

        composeTestRule.onNodeWithTag("wide_detail_pane").assertExists()
        composeTestRule.onNodeWithTag("fake_list").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fake_detail").assertIsDisplayed()
    }

    @Test
    fun theListStaysLeftAndThePageTakesTheRest() {
        setPane()

        val list = composeTestRule.onNodeWithTag("wide_detail_list")
            .getUnclippedBoundsInRoot()
        val detail = composeTestRule.onNodeWithTag("wide_detail_detail")
            .getUnclippedBoundsInRoot()
        val listWidth = list.right.value - list.left.value
        val detailWidth = detail.right.value - detail.left.value

        assertTrue(
            "list (right=${list.right}) must end before the page starts " +
                "(left=${detail.left})",
            list.right.value <= detail.left.value
        )
        assertTrue(
            "the list ($listWidth dp) must keep less than half of an 840 dp window " +
                "(page=$detailWidth dp)",
            listWidth < detailWidth
        )
    }

    @Test
    fun thePaneFillsTheWindowItIsGiven() {
        setPane()

        val pane = composeTestRule.onNodeWithTag("wide_detail_pane")
            .getUnclippedBoundsInRoot()
        val paneWidth = pane.right.value - pane.left.value

        assertTrue("pane width=$paneWidth dp", paneWidth >= 840f)
    }

    @Test
    @Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
    fun phoneWidthStillRendersTheSameTwoScreensIfThePaneIsUsedThere() {
        // The pane is a layout, not a policy: it must not crash or drop a pane
        // when the window is narrow. The POLICY (showsWideDetailPane) is what
        // keeps it off the phone — and that is a pure-function test.
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize()) {
                    WideDetailPane(
                        list = { Box(Modifier.fillMaxSize().testTag("fake_list")) },
                        detail = { Box(Modifier.fillMaxSize().testTag("fake_detail")) }
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("fake_list").assertExists()
        composeTestRule.onNodeWithTag("fake_detail").assertExists()
    }
}
