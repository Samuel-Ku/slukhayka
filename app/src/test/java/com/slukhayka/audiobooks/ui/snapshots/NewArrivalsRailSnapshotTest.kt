package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.ui.screens.NewArrivalsRail
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
 * spec-28 (#192) — snapshot pin for the cross-source «Новинки» rail: one
 * horizontal row of cover cards, each carrying a badge per source that
 * carries the Work (4read + Sound-Books on the merged card). Pure
 * `@Composable` inputs — no `MainViewModel`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class NewArrivalsRailSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val results = listOf(
        // The same Work on two sources — one card, two badges.
        GlobalSearchResult(
            title = "Вкради мене... Зараз!",
            author = "Сергій Оріанець",
            mergeKey = "вкради-мене-зараз|сергій-оріанець",
            coverImageUrl = null,
            sources = listOf(
                GlobalSearchSource("4read", "4read", "https://4read.org/7611-vkradi-mene-zaraz.html"),
                GlobalSearchSource("soundbooks", "Sound-Books", "https://sound-books.net/vkrady")
            )
        ),
        GlobalSearchResult(
            title = "Темна матерія",
            author = "Блейк Крауч",
            mergeKey = "темна-матерія|блейк-крауч",
            coverImageUrl = null,
            sources = listOf(GlobalSearchSource("soundbooks", "Sound-Books", "https://sound-books.net/temna"))
        ),
        GlobalSearchResult(
            title = "Неостанній бій",
            author = "Костянтин Шелест",
            mergeKey = "неостанній-бій|костянтин-шелест",
            coverImageUrl = null,
            sources = listOf(GlobalSearchSource("4read", "4read", "https://4read.org/7589-neostannij-bij.html"))
        )
    )

    @Test
    fun new_arrivals_rail_with_source_badges() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                RailSurface {
                    NewArrivalsRail(results = results, onBookClick = {})
                }
            }
        }

        // Self-verifying on top of the image: the header renders (uppercased
        // by CatalogRowHeader), and the badges appear exactly once per card
        // that carries the source — the merged card shows both.
        composeTestRule.onNodeWithText("Новинки", ignoreCase = true).assertExists()
        // The merged card carries both badges; the single-source cards carry
        // one each — so each badge label appears exactly twice.
        assertEquals(2, composeTestRule.onAllNodesWithText("4read").fetchSemanticsNodes().size)
        assertEquals(2, composeTestRule.onAllNodesWithText("Sound-Books").fetchSemanticsNodes().size)
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
