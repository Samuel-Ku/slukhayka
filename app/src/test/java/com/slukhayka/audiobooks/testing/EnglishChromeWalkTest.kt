package com.slukhayka.audiobooks.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.window.Dialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #980 — the walk itself is the thing being fixed, so it gets its own proof.
 *
 * The three EN slices trusted a walk that never read PaneTitle. These tests
 * pin exactly the properties that were missing, so a future "simplification"
 * that drops one of them fails here instead of quietly turning the EN checks
 * back into a half-blind walk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS", sdk = [36])
class EnglishChromeWalkTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the walk reads a pane title`() {
        composeTestRule.setContent {
            Box(Modifier.semantics { paneTitle = "Додати до добірки" })
        }

        assertEquals(
            "a Cyrillic pane title must surface as a leak",
            listOf("Додати до добірки"),
            EnglishChromeWalk.leakedCyrillic(composeTestRule)
        )
    }

    @Test
    fun `the walk keeps a container's own description and state`() {
        composeTestRule.setContent {
            Box(
                Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "Container"
                    stateDescription = "Expanded"
                }
            ) {
                Text("Child")
            }
        }

        val texts = EnglishChromeWalk.collectTexts(composeTestRule)
        assertTrue("the container's own description was folded away: $texts", texts.contains("Container"))
        assertTrue("the container's own state was folded away: $texts", texts.contains("Expanded"))
        assertTrue("the merged child text was lost: $texts", texts.contains("Child"))
    }

    @Test
    fun `the walk reaches chrome in a second root`() {
        composeTestRule.setContent {
            Text("Screen")
            Dialog(onDismissRequest = {}) {
                Box(Modifier.semantics { paneTitle = "Аркуш" })
            }
        }

        assertTrue(
            "a dialog's pane title was missed by the walk",
            EnglishChromeWalk.collectTexts(composeTestRule).contains("Аркуш")
        )
    }

    @Test
    fun `a clean English tree reports no leak`() {
        composeTestRule.setContent {
            Box(Modifier.semantics { paneTitle = "Add to collection" }) { Text("Add") }
        }

        assertTrue(
            "English chrome must not be reported as a leak",
            EnglishChromeWalk.leakedCyrillic(composeTestRule).isEmpty()
        )
    }
}
