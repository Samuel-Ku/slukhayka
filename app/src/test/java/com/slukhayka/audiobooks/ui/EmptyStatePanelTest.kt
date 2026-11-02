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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.ui.components.EmptyStatePanel
import com.slukhayka.audiobooks.ui.components.EmptyStatePrimaryAction
import com.slukhayka.audiobooks.ui.components.EmptyStateQuietAction
import com.slukhayka.audiobooks.ui.icons.PaperInkIcons
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
 * The six migrated surfaces all render through this component, so its promises
 * are the whole anatomy: title + body in paper type, ONE primary action with the
 * second path demoted to a quiet link (or nothing to press when the state has
 * no honest action), the polite live-region announcement preserved from
 * `EmptyState`, and a column that is vertically centred inside whatever height
 * the host hands it (the anchoring half of the ticket — a panel pinned to the
 * top is the defect wearing a component's clothes).
 *
 * The "no placeholder glyph" rule is NOT asserted here: Compose semantics cannot
 * see `Icon`, so the check would be vacuous. `EmptyStatePanelGuardTest` proves
 * it at the source — no icon parameter, no `Icon(...)` in the panel body.
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
    fun `a plain absence offers no control at all`() {
        show {
            EmptyStatePanel(title = "Тут поки порожньо", body = "Один короткий рядок.")
        }

        compose.onNodeWithTag("empty_state_panel").assertIsDisplayed()
        // No action slot passed -> no clickable node: the panel never invents a
        // dead CTA, and there is no icon node it could draw (the glyph rule is
        // pinned at the source by EmptyStatePanelGuardTest — semantics cannot
        // see an Icon).
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun `a failed state can announce itself through stateDescription`() {
        show {
            EmptyStatePanel(
                title = "Не вдалося завантажити",
                body = "Спробуйте ще раз пізніше.",
                stateDescription = "Помилка"
            )
        }

        compose.onNodeWithText("Не вдалося завантажити")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Помилка"
                )
            )
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
    fun `the shared primary action is the canonical 52 dp button`() {
        // #1775 — the height is one half of the CTA's form; the radius is the
        // other, and a corner radius is not observable from Compose semantics,
        // so the source guard pins the token and the goldens pin the pixels.
        // The measured box is still worth a test: a stray `heightIn` at a call
        // site would change it without changing the token.
        show {
            EmptyStatePanel(
                title = "Тут поки порожньо",
                body = "Один короткий рядок.",
                action = {
                    EmptyStatePrimaryAction(
                        text = "Оновити каталог",
                        onClick = {},
                        icon = PaperInkIcons.Refresh,
                        testTag = "panel_primary_action"
                    )
                }
            )
        }

        val bounds = compose.onNodeWithTag("panel_primary_action").getUnclippedBoundsInRoot()
        assertEquals(
            "the shared primary CTA must stay the one 52 dp button",
            52f,
            bounds.bottom.value - bounds.top.value,
            1f
        )
    }

    @Test
    fun `the shared quiet action keeps the 48 dp target`() {
        show {
            EmptyStatePanel(
                title = "Тут поки порожньо",
                body = "Один короткий рядок.",
                quietAction = {
                    EmptyStateQuietAction(
                        text = "Імпортувати файл",
                        onClick = {},
                        testTag = "panel_quiet_action"
                    )
                }
            )
        }

        val bounds = compose.onNodeWithTag("panel_quiet_action").getUnclippedBoundsInRoot()
        assertTrue(
            "the quiet action is a real 48 dp target, not a bare clickable word " +
                "(height=${bounds.bottom.value - bounds.top.value})",
            bounds.bottom.value - bounds.top.value >= 48f - 1f
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
