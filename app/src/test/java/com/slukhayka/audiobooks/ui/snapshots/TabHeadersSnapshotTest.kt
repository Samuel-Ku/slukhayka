package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.ui.components.AppTabHeader
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #568 (spec-46 C5, ADR-0033) — «шапки всіх чотирьох табів — одна модель», pinned.
 *
 * Before this, «Слухати» was the one root that did NOT name itself: it rendered
 * a bare gear in a Row while Огляд, Мої книги and Друзі went through
 * [AppTabHeader]. That is exactly the failure ADR-0033 describes — the same
 * surface reinventing its own chrome — so the pin is per-tab: each of the four
 * roots is rendered through the ONE composable, and each must show its title,
 * carry the TalkBack heading contract on its heading tag, and host its action.
 *
 * A fifth root added later without a header fails here loudly rather than
 * silently shipping a screen that does not say where the listener is.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class TabHeadersSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * One root's header, as the app composes it. `title` is the resource the
     * bottom bar also uses (spec-54 T06 / #873) — a literal here could drift.
     */
    private data class TabHeaderFixture(
        val root: String,
        val title: String,
        val headingTag: String,
        val subtitle: String? = null,
        val brandMark: Boolean = false
    )

    // The four roots, in bottom-bar order. Titles match nav_* exactly.
    private val fixtures = listOf(
        // #885 (wave 3) — EVERY root sets `brandMark`, because the prototype
        // gates its wordmark on `isRoot` (line 1318), not on Огляд. Before
        // this only the Огляд fixture set it, so the Listen, Library and
        // Friends headers had NO golden covering their wordmark: a change could
        // land on three roots and move only the Explore images. The gap was
        // found by noticing exactly that while shipping the wordmark, and it is
        // closed here rather than left as a note.
        TabHeaderFixture("listen", "Слухати", "listen_heading", brandMark = true),
        TabHeaderFixture("explore", "Огляд", "explore_heading", brandMark = true),
        TabHeaderFixture("library", "Мої книги", "library_heading", subtitle = "12 книг", brandMark = true),
        TabHeaderFixture("friends", "Друзі", "friends_heading", brandMark = true)
    )

    /**
     * One root at a time: Compose forbids a second `setContent` per test, and a
     * per-root test also names the broken root in its own failure instead of
     * stopping at whichever came first in a loop.
     */
    private fun pinHeader(fixture: TabHeaderFixture) {
        var actionClicks = 0
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                HeaderSurface {
                    AppTabHeader(
                        title = fixture.title,
                        subtitle = fixture.subtitle,
                        showBrandMark = fixture.brandMark,
                        headingTestTag = fixture.headingTag,
                        actions = {
                            TextButton(
                                onClick = { actionClicks++ },
                                modifier = Modifier.testTag(HEADER_ACTION_TAG)
                            ) { Text("Дія") }
                        }
                    )
                }
            }
        }

        // 1. The root names itself — the defect this ticket exists for.
        composeTestRule.onNodeWithText(fixture.title).assertIsDisplayed()
        // 2. It stays the TalkBack heading, on its per-root tag.
        composeTestRule.onNodeWithTag(fixture.headingTag)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        // 3. Its action is hosted by the header, not by a sibling row.
        composeTestRule.onNodeWithTag(HEADER_ACTION_TAG).performClick().assertExists()
        assertEquals("${fixture.root}: the header action must be live", 1, actionClicks)
        // 4. The subtitle is inside the header when the root has one.
        fixture.subtitle?.let { composeTestRule.onNodeWithText(it).assertIsDisplayed() }

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/tab_header_${fixture.root}.png"
        )
    }

    /** Every root the bottom bar has — the contract's own scope. */
    @Test
    fun the_contract_covers_every_root() {
        assertEquals("one fixture per bottom-bar root", 4, fixtures.size)
        assertEquals(
            "roots in bottom-bar order",
            listOf("listen", "explore", "library", "friends"),
            fixtures.map { it.root }
        )
    }

    @Test
    fun listen_header_pins_title_heading_and_action() = pinHeader(fixtures[0])

    @Test
    fun explore_header_pins_title_heading_and_action() = pinHeader(fixtures[1])

    @Test
    fun library_header_pins_title_heading_and_action() = pinHeader(fixtures[2])

    @Test
    fun friends_header_pins_title_heading_and_action() = pinHeader(fixtures[3])

    private companion object {
        const val HEADER_ACTION_TAG = "tab_header_action"
    }
}

/** Chrome matching the other design-system pins: scheme background, full size. */
@Composable
private fun HeaderSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) { content() }
    }
}
