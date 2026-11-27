package com.slukhayka.audiobooks.ui

import android.content.Context
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.testing.assertNamedCheckboxRow
import com.slukhayka.audiobooks.ui.screens.ManualBookAddSheet
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1809 — the hand-add sheet's two choices were unnamed checkboxes with a
 * separate, dead label. Each choice is now one named checkbox row; the label
 * itself toggles it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ManualBookAddSheetRowsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun sheet() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                ManualBookAddSheet(
                    onAdd = {},
                    onDismiss = {},
                    onAddTracked = { _, _, _ -> }
                )
            }
        }
    }

    @Test
    fun `both choices are named checkboxes and the label toggles them`() {
        sheet()

        val wantsLabel = context.getString(R.string.manual_add_wants_to_read)
        compose.assertNamedCheckboxRow("manual_add_wants", wantsLabel)
        compose.onNodeWithTag("manual_add_wants").assertIsOff()

        // The choices sit at the bottom of a 420 dp scroll viewport.
        compose.onNodeWithTag("manual_add_wants").performScrollTo()
        compose.onNodeWithText(wantsLabel).performClick()

        compose.onNodeWithTag("manual_add_wants").assertIsOn()
        // The sibling choice is a row of the same shape, not a second control
        // inside the first one.
        compose.assertNamedCheckboxRow(
            "manual_add_own_file",
            context.getString(R.string.manual_add_own_file)
        )
    }
}
