package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.entries.LibraryNewArrival
import com.slukhayka.audiobooks.ui.screens.NewArrivalsRail
import com.slukhayka.audiobooks.ui.theme.PaperInkTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.unit.width
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ADR-0041 / #733 — snapshot pin for the library-first «Новинки» rail: one
 * card per recently imported Work with the badge of the source it was
 * imported from. Pure `@Composable` inputs — no `MainViewModel`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// uk-rUA: explicit qualifiers replace robolectric.properties' default `uk`,
// and the rail header is resource-backed since v1.4 (E3).
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class NewArrivalsRailSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val arrivals = listOf(
        arrival("a1", "Вкради мене... Зараз!", "Сергій Оріанець", "4read", 3_000L),
        arrival("a2", "Темна матерія", "Блейк Крауч", "Sound-Books", 2_000L),
        arrival("a3", "Неостанній бій", "Костянтин Шелест", "Локальна", 1_000L)
    )

    private fun arrival(
        id: String,
        title: String,
        author: String,
        sourceName: String,
        addedAt: Long
    ): LibraryNewArrival = LibraryNewArrival(
        workKey = "work-$id",
        book = AudiobookEntity(
            id = id,
            title = title,
            author = author,
            narrator = "",
            description = "",
            coverDrawableRes = 0,
            genre = "",
            sourceUrl = ""
        ).also { it.createdAt = addedAt },
        sourceName = sourceName,
        addedAt = addedAt
    )

    @Test
    fun new_arrivals_rail_with_source_badges() {
        composeTestRule.setContent {
            PaperInkTheme(darkTheme = true) {
                RailSurface {
                    NewArrivalsRail(arrivals = arrivals, onBookClick = {})
                }
            }
        }

        // Self-verifying on top of the image: the header renders (uppercased
        // by the canonical section header), and each card carries exactly one badge — the
        // source its Library Entry was imported from.
        composeTestRule.onNodeWithText("Новинки", ignoreCase = true).assertExists()
        composeTestRule.onNodeWithText("4read").assertExists()
        composeTestRule.onNodeWithText("Sound-Books").assertExists()
        composeTestRule.onNodeWithText("Локальна").assertExists()
        // Three distinct Work keys = three cards = three badges, no duplicates.
        assertEquals(
            3,
            composeTestRule.onAllNodesWithText("4read").fetchSemanticsNodes().size +
                composeTestRule.onAllNodesWithText("Sound-Books").fetchSemanticsNodes().size +
                composeTestRule.onAllNodesWithText("Локальна").fetchSemanticsNodes().size
        )
        // #1546 — page grid and card width. The rail lives on the Огляд paper
        // root, so it takes the direction's 20 dp page padding, and the shelf
        // card is the paper 132 dp (2:3), NOT the legacy 120 dp wrapper that
        // squeezed the cover to 120×198 and stretched its ratio.
        val firstCard = composeTestRule.onNodeWithTag("compact_book_a1")
            .getUnclippedBoundsInRoot()
        assertTrue(
            "the first shelf card starts at ${firstCard.left}, expected 20 dp",
            abs(firstCard.left.value - 20f) <= 0.5f
        )
        assertTrue(
            "the paper shelf card is ${firstCard.width} wide, expected 132 dp",
            abs(firstCard.width.value - 132f) <= 0.5f
        )

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/new_arrivals_rail.png"
        )
    }
}

/** Same chrome as the other snapshot seams: scheme background, full size. */
@Composable
private fun RailSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        content()
    }
}
