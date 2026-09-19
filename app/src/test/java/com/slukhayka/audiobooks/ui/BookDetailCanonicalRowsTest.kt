package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.screens.BookDetailSourcePresentation
import com.slukhayka.audiobooks.ui.screens.bookdetail.NarrationRowCard
import com.slukhayka.audiobooks.ui.screens.bookdetail.WorkSourceRowCard
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec-46 T10 (#571, E4) — «рядки джерел/начиток → уніфікований рядок на
 * базі BookRow-слотів (інформаційні, без дій)»; «Поточна»/«Тільки стрімінг» —
 * чіпові слоти.
 *
 * These are behavior pins, not look pins:
 * - the row is the canonical [com.slukhayka.audiobooks.ui.components.BookRow]
 *   geometry (76 dp min height), not the 48 dp bordered `Surface` it replaced;
 * - the source row stays informational — no click action anywhere on it;
 * - each state marker renders as the canonical non-interactive
 *   [com.slukhayka.audiobooks.ui.components.MetadataChip] and only on the row
 *   that owns the state (exists/absent boundaries);
 * - the narration row keeps its one user action — opening the rendition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BookDetailCanonicalRowsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val workTitle = "Трохи ненависті"

    private fun source(
        id: String,
        name: String = id,
        streamOnly: Boolean = false,
        isCurrent: Boolean = false,
        selectable: Boolean = false
    ) = BookDetailSourcePresentation(
        sourceId = id,
        name = name,
        url = "https://$id.example/book",
        streamOnly = streamOnly,
        rating = null,
        isCurrent = isCurrent,
        selectable = selectable,
        differingDescription = null,
        differingNarrator = null,
        differingGenres = emptyList()
    )

    private fun renderSources(vararg sources: BookDetailSourcePresentation) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column {
                        sources.forEach { source ->
                            WorkSourceRowCard(source = source, workTitle = workTitle)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun every_source_row_is_the_canonical_informational_row() {
        renderSources(
            source(id = "4read", isCurrent = true),
            source(id = "sluhay", streamOnly = true)
        )

        listOf("work_source_4read", "work_source_sluhay").forEach { tag ->
            // 76 dp is BookRow's documented minimum; the replaced bordered
            // Surface carried `defaultMinSize(48.dp)`.
            composeTestRule.onNodeWithTag(tag).assertHeightIsAtLeast(76.dp)
            // «Інформаційний, без дій»: the source block never becomes an
            // implicit source switch (BookDetailPresentation.selectable=false).
            composeTestRule.onNodeWithTag(tag)
                .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        }
    }

    @Test
    fun state_markers_are_chips_on_the_row_that_owns_them() {
        renderSources(
            source(id = "4read", isCurrent = true),
            source(id = "sluhay", streamOnly = true)
        )

        // Exactly one of each across two rows — the marker never leaks onto
        // the neighbouring row.
        composeTestRule.onAllNodesWithTag(CURRENT_CHIP, useUnmergedTree = true)
            .assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(STREAM_ONLY_CHIP, useUnmergedTree = true)
            .assertCountEquals(1)

        composeTestRule.onNode(
            hasTestTag(CURRENT_CHIP) and hasAnyAncestor(hasTestTag("work_source_4read")),
            useUnmergedTree = true
        ).assertExists()
        composeTestRule.onNode(
            hasTestTag(STREAM_ONLY_CHIP) and hasAnyAncestor(hasTestTag("work_source_sluhay")),
            useUnmergedTree = true
        ).assertExists()

        // MetadataChip is non-interactive by contract (C4): the fact never
        // becomes a second control inside the row.
        composeTestRule.onNodeWithTag(CURRENT_CHIP, useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        composeTestRule.onNodeWithTag(STREAM_ONLY_CHIP, useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun a_plain_other_source_renders_neither_marker() {
        renderSources(source(id = "plain"))

        composeTestRule.onNodeWithTag(CURRENT_CHIP, useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag(STREAM_ONLY_CHIP, useUnmergedTree = true).assertDoesNotExist()
        // The honest-absence boundary still renders the canonical row.
        composeTestRule.onNodeWithTag("work_source_plain").assertHeightIsAtLeast(76.dp)
    }

    @Test
    fun a_current_stream_only_source_renders_both_markers() {
        renderSources(source(id = "4read", isCurrent = true, streamOnly = true))

        composeTestRule.onAllNodesWithTag(CURRENT_CHIP, useUnmergedTree = true)
            .assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(STREAM_ONLY_CHIP, useUnmergedTree = true)
            .assertCountEquals(1)
    }

    @Test
    fun narration_row_is_the_canonical_row_and_keeps_its_one_action() {
        var opened = 0
        val sibling = AudiobookEntity(
            id = "sibling-book",
            title = "Кобзар",
            author = "Тарас Шевченко",
            narrator = "Інший читач",
            description = "",
            coverDrawableRes = 0,
            genre = "",
            sourceUrl = "https://example.invalid/sibling"
        )
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    NarrationRowCard(
                        sibling = sibling,
                        average = 4.5,
                        voteCount = 2,
                        onClick = { opened++ }
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("narration_sibling-book")
            .assertHeightIsAtLeast(76.dp)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .performClick()
        composeTestRule.runOnIdle { assertEquals(1, opened) }
    }

    private companion object {
        const val CURRENT_CHIP = "source_state_current_chip"
        const val STREAM_ONLY_CHIP = "source_state_stream_only_chip"
    }
}
