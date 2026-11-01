package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.ui.components.EmptyStatePanel
import com.slukhayka.audiobooks.ui.theme.PaperInkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * #1766 (audit U9/U13) — the ONE shared empty state.
 *
 * The six migrated surfaces all render through this component, so its four
 * promises are the whole anatomy: title + body in paper type, NO placeholder
 * glyph, ONE primary action with the second path demoted to a quiet link, and
 * a column that is vertically centred inside whatever height the host hands it
 * (the anchoring half of the ticket — a panel pinned to the top is the defect
 * wearing a component's clothes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "uk-rUA-w411dp-h891dp")
class EmptyStatePanelTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            PaperInkTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) { content() }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the panel renders its title and body`() {
        show {
            EmptyStatePanel(
                title = "Тут поки порожньо",
                body = "Ваша книга з’явиться, щойно ви почнете слухати."
            )
        }

        compose.onNodeWithText("Тут поки порожньо").assertIsDisplayed()
        compose.onNodeWithText("Ваша книга з’явиться, щойно ви почнете слухати.")
            .assertIsDisplayed()
    }

    @Test
    fun `the title still announces itself politely`() {
        show {
            EmptyStatePanel(title = "Тут поки порожньо", body = "Один короткий рядок.")
        }

        // Preserved from EmptyState: an empty state is transient, so TalkBack
        // announces it the moment it appears.
        compose.onNodeWithText("Тут поки порожньо").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Polite
            )
        )
    }

    @Test
    fun `the panel draws no placeholder glyph`() {
        show {
            EmptyStatePanel(title = "Тут поки порожньо", body = "Один короткий рядок.")
        }

        compose.onNodeWithTag("empty_state_panel").assertIsDisplayed()
        // The seam EmptyState itself exposes for a placeholder glyph; the panel
        // must never render it, and the component declares no icon slot at all.
        compose.onNodeWithTag("empty_state_icon").assertDoesNotExist()
    }

    @Test
    fun `one primary action and one quiet link, the link below the button`() {
        var primaryClicks = 0
        var quietClicks = 0
        show {
            EmptyStatePanel(
                title = "Тут поки порожньо",
                body = "Один короткий рядок.",
                action = {
                    Button(
                        onClick = { primaryClicks++ },
                        modifier = Modifier.testTag("panel_primary_action")
                    ) { Text("Оновити") }
                },
                quietAction = {
                    TextButton(
                        onClick = { quietClicks++ },
                        modifier = Modifier.testTag("panel_quiet_action")
                    ) { Text("Імпортувати файл") }
                }
            )
        }

        compose.onNodeWithTag("panel_primary_action").assertIsDisplayed().performClick()
        compose.onNodeWithTag("panel_quiet_action").assertIsDisplayed().performClick()
        assertEquals("the primary action is one clickable button", 1, primaryClicks)
        assertEquals("the quiet link is a real second path", 1, quietClicks)

        // Exactly the two paths the slot contract allows: no third CTA.
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)

        val primary = compose.onNodeWithTag("panel_primary_action").getUnclippedBoundsInRoot()
        val quiet = compose.onNodeWithTag("panel_quiet_action").getUnclippedBoundsInRoot()
        assertTrue(
            "the quiet link must sit under the primary action " +
                "(primary bottom=${primary.bottom}, quiet top=${quiet.top})",
            quiet.top.value >= primary.bottom.value
        )
    }

    @Test
    fun `the panel is centred inside the height it is given`() {
        show {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("panel_host")
            ) {
                // The host gives the panel height: filling the box is the same
                // shape of grant as fillParentMaxSize()/weight(1f) on the real
                // surfaces.
                EmptyStatePanel(
                    title = "Тут поки порожньо",
                    body = "Один короткий рядок.",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        val host = compose.onNodeWithTag("panel_host").getUnclippedBoundsInRoot()
        val panel = compose.onNodeWithTag("empty_state_panel").getUnclippedBoundsInRoot()
        val hostCentre = (host.top.value + host.bottom.value) / 2f
        val panelCentre = (panel.top.value + panel.bottom.value) / 2f

        assertTrue(
            "the panel column must be optically anchored: host centre=$hostCentre dp, " +
                "panel centre=$panelCentre dp, panel=$panel",
            abs(hostCentre - panelCentre) < 2f
        )
    }

    @Test
    fun `a host that gives no height gets a wrapped panel, not invented dead space`() {
        show {
            Box(modifier = Modifier.fillMaxWidth()) {
                EmptyStatePanel(title = "Тут поки порожньо", body = "Один короткий рядок.")
            }
        }

        val panel = compose.onNodeWithTag("empty_state_panel").getUnclippedBoundsInRoot()
        // Wrap-content: the padding is the component's own, not a viewport guess.
        assertTrue(
            "an unconstrained host must not stretch the panel " +
                "(top=${panel.top}, bottom=${panel.bottom})",
            panel.bottom.value - panel.top.value < 200f
        )
    }
}
