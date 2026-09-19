package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.4 C4 (ADR-0033, #564) — the chip's honest-absence boundary.
 *
 * The language slot renders NOTHING for an unknown/blank claim (`uk`-only
 * fixtures, so the assertions read the real Ukrainian resource). The happy
 * path is one test; the boundary — unknown, blank, and an unknown language
 * that must NOT fall through to the source/text slots — is the rest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA", sdk = [36])
class MetadataChipTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun known_language_renders_the_two_letter_chip() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Box(modifier = Modifier.testTag("chip_host")) {
                    MetadataChip(language = "uk")
                }
            }
        }
        composeTestRule.onNodeWithText("UA").assertIsDisplayed()
        // TalkBack announces the full language name, not «UA».
        composeTestRule.onNodeWithContentDescription("Українська").assertIsDisplayed()
    }

    @Test
    fun known_language_full_name_renders_english_chip() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Box(modifier = Modifier.testTag("chip_host")) {
                    MetadataChip(language = "English")
                }
            }
        }
        composeTestRule.onNodeWithText("EN").assertIsDisplayed()
    }

    @Test
    fun unknown_or_blank_language_renders_no_chip() {
        val raws = listOf("", "   ", "Klingon", "xx-unknown")
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Column {
                    raws.forEachIndexed { index, raw ->
                        Box(modifier = Modifier.testTag("no_chip_$index")) {
                            MetadataChip(language = raw)
                        }
                    }
                }
            }
        }
        raws.indices.forEach { index ->
            composeTestRule.onNodeWithTag("no_chip_$index").onChildren().assertCountEquals(0)
        }
    }

    @Test
    fun unknown_language_does_not_fall_through_to_the_other_slots() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Box(modifier = Modifier.testTag("chip_host")) {
                    MetadataChip(language = "Klingon", source = "4read", text = "3 год 20 хв")
                }
            }
        }
        // First non-null slot wins: an unknown language must not silently
        // render a source or plain chip instead — the honest absence.
        composeTestRule.onNodeWithTag("chip_host").onChildren().assertCountEquals(0)
    }

    @Test
    fun source_slot_still_renders_when_language_is_absent() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Box(modifier = Modifier.testTag("chip_host")) {
                    MetadataChip(source = "4read")
                }
            }
        }
        composeTestRule.onNodeWithTag("chip_host").onChildren().assertCountEquals(1)
        composeTestRule.onNodeWithText("4read").assertIsDisplayed()
    }
}
