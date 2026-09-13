package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.ui.screens.collections.SaveCollectionForYouButton
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec-51 (#695) — saving is offered only when the original is really here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SaveCollectionForYouButtonTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setButton(available: Boolean, onSave: () -> Unit) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                SaveCollectionForYouButton(
                    originalAvailableLocally = available,
                    onSave = onSave
                )
            }
        }
    }

    @Test
    fun `a locally available original can be saved`() {
        var saved = 0
        setButton(available = true) { saved++ }

        composeTestRule.onNodeWithTag("save_collection_for_you")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, saved)
    }

    @Test
    fun `an original that is not here is visible but not actionable`() {
        var saved = 0
        setButton(available = false) { saved++ }

        composeTestRule.onNodeWithTag("save_collection_for_you")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .performClick()
        assertEquals("nothing must be saved without a local original", 0, saved)
    }
}
