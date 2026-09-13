package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.data.collections.ListenerCollection
import com.slukhayka.audiobooks.data.collections.ListenerCollectionItem
import com.slukhayka.audiobooks.data.collections.PublicationPreviewFactory
import com.slukhayka.audiobooks.ui.screens.collections.PublishCollectionSheet
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec-51 (#691) — nothing leaves the device without this screen's consent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PublishCollectionSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val preview = PublicationPreviewFactory.of(
        ListenerCollection(
            id = "c1",
            title = "Магія",
            description = "про зорі",
            createdAt = 1L,
            items = listOf(ListenerCollectionItem("a", "", 1L))
        ),
        "Слухач"
    )!!

    private fun setContent(onConfirm: () -> Unit, onDismiss: () -> Unit = {}) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                PublishCollectionSheet(preview = preview, onConfirm = onConfirm, onDismiss = onDismiss)
            }
        }
    }

    @Test
    fun `the sheet lists every line that will be published`() {
        setContent(onConfirm = {})

        composeTestRule.onNodeWithTag("publish_collection_sheet").assertIsDisplayed()
        preview.lines.forEachIndexed { index, line ->
            composeTestRule.onNodeWithTag("publish_collection_line_$index").assertIsDisplayed()
            composeTestRule.onNodeWithText(line).assertExists()
        }
    }

    @Test
    fun `cancelling publishes nothing`() {
        var confirmed = false
        var dismissed = false
        setContent(onConfirm = { confirmed = true }, onDismiss = { dismissed = true })

        composeTestRule.onNodeWithTag("publish_collection_cancel").performClick()
        assertFalse("nothing leaves the device on cancel", confirmed)
        assertEquals(true, dismissed)
    }

    @Test
    fun `only the explicit confirm publishes`() {
        var confirmed = false
        setContent(onConfirm = { confirmed = true })

        composeTestRule.onNodeWithTag("publish_collection_confirm").performClick()
        assertEquals(true, confirmed)
    }
}
