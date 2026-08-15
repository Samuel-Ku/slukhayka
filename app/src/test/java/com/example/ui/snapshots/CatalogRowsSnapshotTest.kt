package com.example.ui.snapshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.data.catalog.CatalogBook
import com.example.data.catalog.CatalogSeries
import com.example.ui.screens.CatalogBookCard
import com.example.ui.screens.CatalogRowHeader
import com.example.ui.screens.CatalogSeriesCard
import com.example.ui.screens.EmptyCatalogState
import com.example.ui.screens.HomeHeader
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
 * Snapshot tests for the Netflix-style Explore rows (spec #8 ticket T6):
 * row headers, the cover-first book card, the series chip and the actionable
 * first-run empty state. Pure-`@Composable` inputs — no `MainViewModel`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class CatalogRowsSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val book = CatalogBook(
        id = "4read-7611-vkradi-mene-zaraz",
        title = "Вкради мене... Зараз!",
        author = "Сергій Оріанець",
        url = "https://4read.org/7611-vkradi-mene-zaraz.html",
        coverImageUrl = null
    )

    private val series = CatalogSeries(
        title = "Максим Темний",
        url = "https://4read.org/xfsearch/cikl/maksym-temnyj/",
        coverImageUrl = null
    )

    @Test
    fun row_header() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) { CatalogSurface { CatalogRowHeader(title = "Новинки") } }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/catalog_row_header.png"
        )
    }

    @Test
    fun book_card_without_cover() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CatalogSurface {
                    CatalogBookCard(book = book, onClick = {})
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/catalog_book_card.png"
        )
    }

    @Test
    fun series_chip_without_cover() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CatalogSurface {
                    CatalogSeriesCard(series = series, onClick = {})
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/catalog_series_card.png"
        )
    }

    @Test
    fun empty_catalog_state() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CatalogSurface {
                    EmptyCatalogState(onRefreshClick = {}, onImportClick = {})
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/catalog_empty_state.png"
        )
    }

    // Spec-22 T3: the Explore header is collapsible — brand + [🔍] + [🔄]
    // when collapsed, search field + chips when expanded. Both states are
    // pinned so the redesign cannot drift.
    @Test
    fun explore_header_collapsed() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CatalogSurface {
                    HomeHeader(
                        searchExpanded = false,
                        searchQuery = "",
                        selectedGenre = "Усі",
                        genres = listOf("Усі", "Фантастика", "Короткі", "Завантажені"),
                        onToggleSearch = {},
                        onRefresh = {},
                        onSearchQueryChange = {},
                        onCloseSearch = {},
                        onSelectGenre = {}
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/explore_header_collapsed.png"
        )
    }

    @Test
    fun explore_header_expanded() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CatalogSurface {
                    HomeHeader(
                        searchExpanded = true,
                        searchQuery = "Шевченко",
                        selectedGenre = "Класика",
                        genres = listOf("Усі", "Фантастика", "Короткі", "Завантажені"),
                        onToggleSearch = {},
                        onRefresh = {},
                        onSearchQueryChange = {},
                        onCloseSearch = {},
                        onSelectGenre = {}
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/explore_header_expanded.png"
        )
    }
}

/** Same chrome as the Library snapshot tests: scheme background, full size. */
@Composable
private fun CatalogSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) { content() }
    }
}
