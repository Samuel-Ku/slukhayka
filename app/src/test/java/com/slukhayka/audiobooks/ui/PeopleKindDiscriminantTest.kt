package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.ui.screens.CatalogNavRow
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec-46 T16 (#577) — the Огляд people chips carry a stable kind.
 *
 * The chip labels moved into the resources ([OverviewEnglishChromeTest] pins
 * that), so the old `kind.title == "Автори"` discriminant would have silently
 * opened the narrators index in English. This test presses the English chips
 * and asserts the callback still receives the right [PeopleKindType]
 * regardless of the interface language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS", sdk = [36])
class PeopleKindDiscriminantTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the english people chips carry the stable kind, not the label`() {
        val opened = mutableListOf<PeopleKind>()
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CatalogNavRow(
                    onTop100Click = {},
                    onPeopleClick = { opened += it },
                    onSeriesClick = {},
                    onCollectionsClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Narrators").performClick()
        composeTestRule.onNodeWithText("Authors").performClick()

        assertEquals(
            listOf(PeopleKindType.NARRATORS, PeopleKindType.AUTHORS),
            opened.map { it.type }
        )
    }
}
