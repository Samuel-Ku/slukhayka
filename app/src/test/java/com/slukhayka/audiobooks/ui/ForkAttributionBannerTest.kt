package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.data.collections.ForkAttribution
import com.slukhayka.audiobooks.ui.screens.collections.ForkAttributionBanner
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec-51 (#695) — text always, link only while the original is visible. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ForkAttributionBannerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val attribution = ForkAttribution(
        sourceTitle = "Магія",
        sourcePseudonym = "Слухач",
        sourceDocumentId = "doc-1",
        snapshotAt = 100L
    )

    private fun setBanner(originalVisible: Boolean, onOpen: (String) -> Unit = {}) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ForkAttributionBanner(
                    attribution = attribution,
                    originalVisible = originalVisible,
                    onOpenOriginal = onOpen
                )
            }
        }
    }

    @Test
    fun `the text is shown and the link is live while the original is visible`() {
        var opened: String? = null
        setBanner(originalVisible = true, onOpen = { opened = it })

        composeTestRule.onNodeWithText("на основі «Магія» від Слухач").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fork_attribution_link").performClick()
        assertEquals("doc-1", opened)
    }

    @Test
    fun `when the original is gone the text stays but there is no link`() {
        var opened: String? = null
        setBanner(originalVisible = false, onOpen = { opened = it })

        // The snapshot does not disappear with the original…
        composeTestRule.onNodeWithText("на основі «Магія» від Слухач").assertIsDisplayed()
        // …but it is no longer a door to it.
        composeTestRule.onNodeWithTag("fork_attribution_plain").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fork_attribution_link").assertDoesNotExist()
        assertNull(opened)
    }
}
