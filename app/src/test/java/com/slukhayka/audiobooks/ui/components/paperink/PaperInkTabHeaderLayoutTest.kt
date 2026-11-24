package com.slukhayka.audiobooks.ui.components.paperink

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F25 of the 2026-10-28 device audit — the header's trailing/leading controls
 * were centred on the BRAND+TITLE block, so on a root they sat ~8 dp above the
 * title's centre line, and the title itself looked detached from the gear.
 *
 * The contract these two tests pin: the gear (root) and the back arrow
 * (pushed screen) share a centre line with the TITLE, and the wordmark stays
 * above it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-w411dp-h891dp", sdk = [35])
class PaperInkTabHeaderLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the root gear centres on the title line, not on the brand block`() {
        compose.setContent {
            AudiobookTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        PaperInkTabHeader(
                            title = "Слухати",
                            onSettingsClick = {},
                            headingTestTag = "header_title",
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        val title = compose.onNodeWithTag("header_title", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val gear = compose.onNodeWithTag("settings_gear")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "the gear must share the title's centre line: title=${title.center.y} gear=${gear.center.y}",
            title.center.y,
            gear.center.y,
            1f,
        )
        // The wordmark is a separate line ABOVE the title.
        val brand = compose.onNodeWithTag("tab_header_brand", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the brand line must sit above the title: brand=${brand.bottom} title=${title.top}",
            brand.bottom <= title.top + 1f,
        )
    }

    @Test
    fun `the pushed back arrow centres on the pushed title line`() {
        compose.setContent {
            AudiobookTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        PaperInkTabHeader(
                            title = "Завантаження та пам'ять",
                            compact = true,
                            showBrandMark = false,
                            onBackClick = {},
                            headingTestTag = "pushed_title",
                            maxTitleLines = 3,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        val title = compose.onNodeWithTag("pushed_title", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val back = compose.onNodeWithTag("settings_back")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "the back arrow must share the title's centre line: title=${title.center.y} back=${back.center.y}",
            title.center.y,
            back.center.y,
            1f,
        )
    }

    // F25 review blocker: the Library ROOT has brand + title + SUBTITLE, and
    // the trailing actions were centring on the title+subtitle block — the
    // gear/+/overflow landed ~9.5 dp BELOW the title's centre (bbox moved
    // +20 px in `library_redesign_*`). The subtitle now sits under the title
    // line, so the actions stay on the title's centre.
    @Test
    fun `a root with a subtitle keeps the actions on the title line`() {
        compose.setContent {
            AudiobookTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        PaperInkTabHeader(
                            title = "Медіатека",
                            subtitle = "12 книг · 3 год 20 хв",
                            onSettingsClick = {},
                            headingTestTag = "header_title",
                            actions = {
                                Box(Modifier.size(48.dp).testTag("header_plus_action"))
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        val title = compose.onNodeWithTag("header_title", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val gear = compose.onNodeWithTag("settings_gear")
            .fetchSemanticsNode().boundsInRoot
        val plus = compose.onNodeWithTag("header_plus_action")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "the gear must stay on the title's centre: title=${title.center.y} gear=${gear.center.y}",
            title.center.y,
            gear.center.y,
            1f,
        )
        assertEquals(
            "the + action must stay on the title's centre: title=${title.center.y} plus=${plus.center.y}",
            title.center.y,
            plus.center.y,
            1f,
        )
        val subtitle = compose.onNodeWithTag("tab_header_subtitle", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the actions must sit ABOVE the subtitle, not centred on it: " +
                "gear=${gear.center.y} subtitle=${subtitle.center.y}",
            gear.center.y < subtitle.center.y,
        )
    }

    // F25 review blocker 2: a NO-BRAND non-compact header (Library section
    // view) has less content than the 104 dp floor and was top-aligned — its
    // centre sat 8 dp above the header's centre. No golden covered it.
    @Test
    fun `a no-brand non-compact header keeps its content centred`() {
        compose.setContent {
            AudiobookTheme(darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        PaperInkTabHeader(
                            title = "Слухати",
                            compact = false,
                            showBrandMark = false,
                            onSettingsClick = {},
                            headingTestTag = "header_title",
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        val root = compose.onNodeWithTag("tab_header_root").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("header_title", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "the title must sit on the header's centre line: header=${root.center.y} title=${title.center.y}",
            root.center.y,
            title.center.y,
            1f,
        )
    }

}
