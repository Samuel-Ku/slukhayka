package com.example.ui.snapshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.data.source.GlobalSearchResult
import com.example.data.source.GlobalSearchSource
import com.example.ui.screens.CatalogRowHeader
import com.example.ui.screens.CollectionBookCard
import com.example.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Snapshot pins for the spec-16 T3 «Колекції» block: the row header and the
 * cover-first collection card, uniform with the other Огляд rows. The block's
 * hiding rule (a collection with no matches is absent; an all-empty block
 * disappears) lives in the flow ([SourceCatalog.smartCollections] drops empty
 * collections) and the HomeScreen `if (collections.isNotEmpty())` gate — the
 * pinned components are the row header + card it renders.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class CollectionsBlockSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val results = listOf(
        GlobalSearchResult(
            title = "Старий і море",
            author = "Ернест Гемінґвей",
            mergeKey = "старий-і-море|ернест-гемінґвей",
            coverImageUrl = null,
            sources = listOf(GlobalSearchSource("sluhay", "Sluhay", "https://sluhay.com/staryi"))
        ),
        GlobalSearchResult(
            title = "Сто років самотності",
            author = "Габрієль Гарсія Маркес",
            mergeKey = "сто-років-самотності|габрієль-гарсія-маркес",
            coverImageUrl = null,
            sources = listOf(GlobalSearchSource("soundbooks", "Sound-Books", "https://sound-books.net/cien"))
        )
    )

    @Test
    fun collection_row_header_and_cards() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column {
                        CatalogRowHeader(title = "Нобелівські лауреати")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(results, key = { it.key }) { result ->
                                CollectionBookCard(result = result, onClick = {})
                            }
                        }
                    }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/collections_block_row.png"
        )
    }

    @Test
    fun collection_card_without_cover() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column {
                        CatalogRowHeader(title = "Букер")
                        CollectionBookCard(result = results.first(), onClick = {})
                    }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/collection_book_card.png"
        )
    }
}
