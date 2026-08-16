package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.data.collections.CollectionMatcher
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.ui.screens.CollectionsIndexContent
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
 * spec-28 (#190) — snapshot pins for the «Колекції» index screen: the
 * per-collection header + cover rows and the no-collections placeholder.
 * Pure `@Composable` inputs ([CollectionsIndexContent] is stateless) — no
 * `MainViewModel`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class CollectionsIndexSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val oldManAndTheSea = GlobalSearchResult(
        title = "Старий і море",
        author = "Ернест Гемінґвей",
        mergeKey = "старий-і-море|ернест-гемінґвей",
        coverImageUrl = null,
        sources = listOf(GlobalSearchSource("sluhay", "Sluhay", "https://sluhay.com/staryi"))
    )

    private val hundredYears = GlobalSearchResult(
        title = "Сто років самотності",
        author = "Габрієль Гарсія Маркес",
        mergeKey = "сто-років-самотності|габрієль-гарсія-маркес",
        coverImageUrl = null,
        sources = listOf(GlobalSearchSource("soundbooks", "Sound-Books", "https://sound-books.net/cien"))
    )

    private val kobzar = GlobalSearchResult(
        title = "Кобзар",
        author = "Тарас Шевченко",
        mergeKey = "кобзар|тарас-шевченко",
        coverImageUrl = null,
        sources = listOf(GlobalSearchSource("4read", "4read", "https://4read.org/kobzar.html"))
    )

    private val collections = listOf(
        CollectionMatcher.MatchedCollection(
            id = "nobel",
            name = "Нобелівські лауреати",
            sourceNote = "assets",
            books = listOf(oldManAndTheSea, hundredYears)
        ),
        CollectionMatcher.MatchedCollection(
            id = "shevchenko",
            name = "Шевченківська премія",
            sourceNote = "assets",
            books = listOf(kobzar)
        )
    )

    @Test
    fun collections_index_populated() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CollectionsIndexSurface {
                    CollectionsIndexContent(collections = collections, onBookClick = {})
                }
            }
        }

        // Self-verifying on top of the image: both collection headers (which
        // [CatalogRowHeader] renders uppercased) and a book title render.
        composeTestRule.onNodeWithText("Нобелівські лауреати", ignoreCase = true).assertExists()
        composeTestRule.onNodeWithText("Шевченківська премія", ignoreCase = true).assertExists()
        composeTestRule.onNodeWithText("Старий і море").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/collections_index_populated.png"
        )
    }

    @Test
    fun collections_index_empty_state() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CollectionsIndexSurface {
                    CollectionsIndexContent(collections = emptyList(), onBookClick = {})
                }
            }
        }

        // The no-collections placeholder renders a sensible message, not a crash.
        composeTestRule.onNodeWithText("Колекції з'являться після завантаження каталогу.").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/collections_index_empty.png"
        )
    }

    @Test
    fun collection_book_tap_forwards_the_result() {
        var opened: GlobalSearchResult? = null
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CollectionsIndexSurface {
                    CollectionsIndexContent(collections = collections, onBookClick = { opened = it })
                }
            }
        }

        composeTestRule.onNodeWithText("Кобзар").performClick()
        assertEquals("Кобзар", opened?.title)
        assertEquals("https://4read.org/kobzar.html", opened?.sources?.firstOrNull()?.url)
    }
}

/** Same chrome as the other snapshot seams: scheme background, full size. */
@Composable
private fun CollectionsIndexSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        content()
    }
}
