package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.slukhayka.audiobooks.data.collections.PublishedCollectionCodec
import com.slukhayka.audiobooks.ui.screens.collections.RenamePseudonymDialog
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-51 (#691) — changing the public name after publishing.
 *
 * `renameAuthor` had no caller at all before this: a listener chose a pseudonym
 * when they first published and was then stuck with it. The dialog is the seam
 * that reaches it, so what it reports — and what it refuses to report — is the
 * whole point.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA", sdk = [36])
class RenamePseudonymDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setDialog(
        current: String = "Слухач",
        onConfirm: (String) -> Unit = {},
        onDismiss: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                RenamePseudonymDialog(
                    current = current,
                    onConfirm = onConfirm,
                    onDismiss = onDismiss
                )
            }
        }
    }

    @Test
    fun `the dialog opens on the name the store already holds`() {
        // Renaming is an EDIT: an empty box would make the listener retype what
        // they already have, and an empty confirm would wipe their public name.
        setDialog(current = "Слухач")

        composeTestRule.onNodeWithTag("rename_pseudonym_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("rename_pseudonym_confirm").assertIsEnabled()
    }

    @Test
    fun `confirming unchanged reports the same name`() {
        var confirmed: String? = null
        setDialog(current = "Слухач", onConfirm = { confirmed = it })

        composeTestRule.onNodeWithTag("rename_pseudonym_confirm").performClick()

        assertEquals("Слухач", confirmed)
    }

    @Test
    fun `a new name is reported as typed`() {
        var confirmed: String? = null
        setDialog(current = "Слухач", onConfirm = { confirmed = it })

        composeTestRule.onNodeWithTag("rename_pseudonym_field").performTextClearance()
        composeTestRule.onNodeWithTag("rename_pseudonym_field").performTextInput("Мандрівник")
        composeTestRule.onNodeWithTag("rename_pseudonym_confirm").performClick()

        assertEquals("Мандрівник", confirmed)
    }

    @Test
    fun `a blank name cannot be confirmed`() {
        var confirmed: String? = null
        setDialog(current = "Слухач", onConfirm = { confirmed = it })

        composeTestRule.onNodeWithTag("rename_pseudonym_field").performTextClearance()

        // A blank public name is not a name. The store would refuse it too, so
        // the dialog says so here rather than spending a round trip on it.
        composeTestRule.onNodeWithTag("rename_pseudonym_confirm").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("rename_pseudonym_confirm").performClick()
        assertNull(confirmed)
    }

    @Test
    fun `whitespace only is blank too`() {
        var confirmed: String? = null
        setDialog(current = "Слухач", onConfirm = { confirmed = it })

        composeTestRule.onNodeWithTag("rename_pseudonym_field").performTextClearance()
        composeTestRule.onNodeWithTag("rename_pseudonym_field").performTextInput("   ")

        composeTestRule.onNodeWithTag("rename_pseudonym_confirm").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("rename_pseudonym_confirm").performClick()
        assertNull(confirmed)
    }

    @Test
    fun `the name is bounded at the published limit while typing`() {
        var confirmed: String? = null
        setDialog(current = "Слухач", onConfirm = { confirmed = it })

        composeTestRule.onNodeWithTag("rename_pseudonym_field").performTextClearance()
        composeTestRule.onNodeWithTag("rename_pseudonym_field")
            .performTextInput("п".repeat(PublishedCollectionCodec.MAX_PSEUDONYM_LEN + 25))
        composeTestRule.onNodeWithTag("rename_pseudonym_confirm").performClick()

        assertEquals("п".repeat(PublishedCollectionCodec.MAX_PSEUDONYM_LEN), confirmed)
    }

    @Test
    fun `cancelling changes nothing`() {
        var confirmed: String? = null
        var dismissed = false
        setDialog(current = "Слухач", onConfirm = { confirmed = it }, onDismiss = { dismissed = true })

        composeTestRule.onNodeWithTag("rename_pseudonym_field").performTextClearance()
        composeTestRule.onNodeWithTag("rename_pseudonym_field").performTextInput("Мандрівник")
        composeTestRule.onNodeWithTag("rename_pseudonym_cancel").performClick()

        assertNull("nothing is renamed on cancel", confirmed)
        assertEquals(true, dismissed)
    }
}
