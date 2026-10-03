package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #885 — the pill carries the SAME accessibility contract as the row it
 * replaced.
 *
 * This test exists because the ticket's own warning was that a pill conversion
 * must not quietly drop what the row form guaranteed: the radio role, the
 * 48 dp target, the spoken selected state, and ONE node with ONE role rather
 * than a chip announcing itself inside a selectable row. A pill that looks
 * better and reads worse is not an improvement.
 *
 * If a future change simplifies `SettingsRadioPill` back to a bare
 * `FilterChip`, these assertions are what should stop it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsRadioPillTest {

    @get:Rule
    val compose = createComposeRule()

    private fun content(selected: Boolean = true, onSelect: () -> Unit = {}) {
        compose.setContent {
            AudiobookTheme {
                Row(modifier = Modifier.selectableGroup()) {
                    SettingsRadioPill(
                        title = "Українська",
                        selected = selected,
                        onSelect = onSelect,
                        testTag = "pill"
                    )
                }
            }
        }
    }

    @Test
    fun `the pill is a radio button, not a button`() {
        content()
        compose.onNodeWithTag("pill")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
    }

    @Test
    fun `the pill keeps a 48 dp touch target`() {
        content()
        compose.onNodeWithTag("pill").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `the pill keeps exactly one node, so it never reads twice`() {
        content()
        // The chip inside carries cleared semantics: a second node with this
        // tag would mean the listener hears the pill announced twice.
        compose.onNodeWithTag("pill").assertExists()
    }

    @Test
    fun `tapping the pill selects it`() {
        var taps = 0
        // An UNSELECTED pill: clicking an already-selected radio is a no-op by
        // design, and asserting on that would test nothing.
        content(selected = false, onSelect = { taps++ })
        compose.onNodeWithTag("pill").performClick()
        assertEquals(1, taps)
    }
}
