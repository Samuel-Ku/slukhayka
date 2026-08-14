package com.example.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.data.catalog.CatalogBook
import com.example.data.collection.MatchedCollection
import com.example.ui.screens.CollectionsSection
import com.example.ui.theme.AudiobookTheme
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
 * spec-16 T3 (#109) — the Огляд «Колекції» block UI pin (prior art:
 * DurationSectionSnapshotTest): one row per non-empty collection with the
 * uniform cover cards, the whole block hidden when nothing matched, and a
 * card tap opening the book by its id. The matching itself is pinned by the
 * pure SmartCollectionMatcherTest (T1).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class CollectionsSectionSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun card(id: String) = CatalogBook(
        id = id,
        title = "Книга $id",
        author = "Автор",
        url = "https://4read.org/$id.html",
        coverImageUrl = null
    )

    private fun collection(id: String, name: String, books: List<CatalogBook>) =
        MatchedCollection(id = id, displayName = name, books = books)

    @Test
    fun rows_render_for_each_collection() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    Column(modifier = Modifier.padding(16.dp)) {
                        CollectionsSection(
                            collections = listOf(
                                collection("nobel", "Нобелівські лауреати", listOf(card("n1"), card("n2"))),
                                collection("shevchenko", "Шевченківська премія", listOf(card("s1")))
                            ),
                            onBookClick = {}
                        )
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("collections_section").assertExists()
        composeTestRule.onNodeWithTag("collection_row_nobel").assertExists()
        composeTestRule.onNodeWithTag("collection_row_shevchenko").assertExists()
        composeTestRule.onNodeWithTag("collection_row_booker").assertDoesNotExist()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/collections_two_rows.png"
        )
    }

    @Test
    fun block_hides_when_nothing_matched() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    CollectionsSection(collections = emptyList(), onBookClick = {})
                }
            }
        }
        composeTestRule.onNodeWithTag("collections_section").assertDoesNotExist()
    }

    @Test
    fun tapping_a_card_opens_that_book() {
        var clickedId: String? = null
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface {
                    Column(modifier = Modifier.padding(16.dp)) {
                        CollectionsSection(
                            collections = listOf(
                                collection("nobel", "Нобелівські лауреати", listOf(card("n1")))
                            ),
                            onBookClick = { clickedId = it }
                        )
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("catalog_book_n1").performClick()
        assertEquals("n1", clickedId)
    }
}