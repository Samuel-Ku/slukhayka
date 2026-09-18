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
import com.slukhayka.audiobooks.ui.components.AppSectionHeader
import com.slukhayka.audiobooks.ui.components.SectionHeaderLevel
import com.slukhayka.audiobooks.ui.components.SectionHeaderTags
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #562 (spec-46 C1, ADR-0033) — the two-level canonical section header pinned
 * on fixture data. Each pin fixes BOTH mandatory affordances of the component:
 * the counter as the header's subtitle line (never a free-standing row) and
 * the action slot on the trailing edge. The title keeps its TalkBack heading
 * contract and carries the level's [SectionHeaderTags] tag, so the group pin
 * and the section pin can never silently collapse into one another.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class SectionHeadersSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Fixture row: only the four fields the canonical header consumes. */
    private data class HeaderFixture(
        val title: String,
        val count: String,
        val actionLabel: String
    )

    private val groupFixture = HeaderFixture(
        title = "Для вас",
        count = "3 нові добірки",
        actionLabel = "Оновити"
    )

    private val sectionFixture = HeaderFixture(
        title = "Популярне",
        count = "12 книг у жанрі",
        actionLabel = "Усі"
    )

    @Test
    fun group_header_pins_count_and_action_slot() {
        var actionClicks = 0
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                HeaderSurface {
                    AppSectionHeader(
                        title = groupFixture.title,
                        level = SectionHeaderLevel.GROUP,
                        count = groupFixture.count,
                        action = {
                            TextButton(
                                onClick = { actionClicks++ },
                                modifier = Modifier.testTag(HEADER_ACTION_TAG)
                            ) { Text(groupFixture.actionLabel) }
                        }
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(SectionHeaderTags.GROUP)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        composeTestRule.onNodeWithText(groupFixture.title).assertIsDisplayed()
        // The counter is a subtitle line inside the header, not a sibling row.
        composeTestRule.onNodeWithText(groupFixture.count).assertIsDisplayed()
        composeTestRule.onNodeWithTag(HEADER_ACTION_TAG).performClick().assertExists()
        assertEquals(1, actionClicks)
        // The two levels are genuinely distinct seams, never one shared tag.
        assertNotEquals(SectionHeaderTags.GROUP, SectionHeaderTags.SECTION)

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/section_header_group.png"
        )
    }

    @Test
    fun section_header_pins_count_and_action_slot() {
        var actionClicks = 0
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                HeaderSurface {
                    AppSectionHeader(
                        title = sectionFixture.title,
                        level = SectionHeaderLevel.SECTION,
                        count = sectionFixture.count,
                        action = {
                            TextButton(
                                onClick = { actionClicks++ },
                                modifier = Modifier.testTag(HEADER_ACTION_TAG)
                            ) { Text(sectionFixture.actionLabel) }
                        }
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(SectionHeaderTags.SECTION)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        composeTestRule.onNodeWithText(sectionFixture.title).assertIsDisplayed()
        composeTestRule.onNodeWithText(sectionFixture.count).assertIsDisplayed()
        composeTestRule.onNodeWithTag(HEADER_ACTION_TAG).performClick().assertExists()
        assertEquals(1, actionClicks)

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/section_header_section.png"
        )
    }

    private companion object {
        const val HEADER_ACTION_TAG = "section_header_action"
    }
}

/** Chrome matching the other design-system pins: scheme background, full size. */
@Composable
private fun HeaderSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) { content() }
    }
}
