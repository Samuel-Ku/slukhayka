package com.slukhayka.audiobooks.ui.snapshots

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.ui.catalog.CatalogCardActionState
import com.slukhayka.audiobooks.ui.components.MetadataChip
import com.slukhayka.audiobooks.ui.screens.searchResultsContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.slukhayka.audiobooks.ui.theme.PaperInkThemedContent
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Snapshot tests for the spec-10 T4 global-search result row: one Work with a
 * badge per matching source.
 *
 * #567: the search surface renders the canonical `BookRow` inline at the
 * `searchResultsContent` call site — the named `GlobalSearchResultCard`
 * wrapper is gone — so these snapshots drive that seam with the uk-rUA
 * qualifier the fixtures live in. Pure inputs: no `MainViewModel`, no network.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GlobalSearchSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val singleSource = GlobalSearchResult(
        title = "Темна матерія",
        author = "Блейк Крауч",
        narrator = "",
        mergeKey = "темна матерія|блейк крауч",
        sources = listOf(GlobalSearchSource("soundbooks", "Sound-Books", "https://sound-books.net/x.html"))
    )

    private val multiSource = GlobalSearchResult(
        title = "Кобзар",
        author = "Тарас Шевченко",
        narrator = "Валерій Завалко",
        mergeKey = "кобзар|тарас шевченко|валерій завалко",
        sources = listOf(
            GlobalSearchSource("4read", "4read", "https://4read.org/kobzar.html"),
            GlobalSearchSource("soundbooks", "Sound-Books", "https://sound-books.net/kobzar.html"),
            GlobalSearchSource("audiobookmp3", "audiobook-mp3", "https://audiobook-mp3.com/uk-audio-kobzar")
        )
    )

    /** Legacy live «Усі джерела» lane; keep its existing goldens unchanged. */
    private fun setSearchContent(result: GlobalSearchResult) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                GlobalSearchSurface {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        searchResultsContent(
                            localBooks = emptyList(),
                            globalResults = listOf(result),
                            liveSearchActive = true,
                            isGlobalLoading = false,
                            globalError = false,
                            onOpenLocalBook = {},
                            onPlayLocalBook = {},
                            onOpenGlobalResult = {},
                            catalogCardActionState = CatalogCardActionState.Idle,
                            onOpenCatalogBrowser = {},
                            onPreflightGlobalResult = {}
                        )
                    }
                }
            }
        }
    }

    // Spec-45 (#405) T7 (#495): the row's rendition language renders the
    // EN badge; unknown languages render nothing.
    @Test
    fun result_card_known_language_renders_badge() {
        setSearchContent(singleSource.copy(language = "en"))

        composeTestRule.onNodeWithText("EN").assertExists()
        composeTestRule.onNodeWithContentDescription("English").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/global_search_result_en_badge.png"
        )
    }

    @Test
    fun result_card_unknown_language_renders_no_badge() {
        setSearchContent(singleSource)

        composeTestRule.onNodeWithText("EN").assertDoesNotExist()
        composeTestRule.onNodeWithText("UA").assertDoesNotExist()
    }

    @Test
    fun result_card_single_source() {
        setSearchContent(singleSource)

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/global_search_result_single.png"
        )
    }

    @Test
    fun result_card_multi_source_badges() {
        setSearchContent(multiSource)

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/global_search_result_multi.png"
        )
    }

    @Test
    fun paperink_result_multi_source_ink() {
        setPaperInkSearchContent(darkTheme = true)

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/global_search_result_multi_ink.png"
        )
        assertPaperInkMetadataLayout(1f)
    }

    @Test
    fun paperink_result_multi_source_paper() {
        setPaperInkSearchContent(darkTheme = false)

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/global_search_result_multi_paper.png"
        )
        assertPaperInkMetadataLayout(1f)
    }

    @Test
    fun paperink_result_multi_source_ink_large_text() {
        setPaperInkSearchContent(darkTheme = true, fontScale = 2f)
        composeTestRule.onRoot().captureRoboImage("src/test/snapshots/global_search_result_multi_ink_200.png")
        assertPaperInkMetadataLayout(2f)
    }

    @Test
    fun paperink_result_multi_source_paper_large_text() {
        setPaperInkSearchContent(darkTheme = false, fontScale = 2f)
        composeTestRule.onRoot().captureRoboImage("src/test/snapshots/global_search_result_multi_paper_200.png")
        assertPaperInkMetadataLayout(2f)
    }

    @Test
    @Config(qualifiers = "en-rUS-" + RobolectricDeviceQualifiers.Pixel8)
    fun paperink_result_multi_source_ink_english_large_text() {
        setPaperInkSearchContent(darkTheme = true, fontScale = 2f)
        composeTestRule.onRoot().captureRoboImage("src/test/snapshots/global_search_result_multi_ink_en_200.png")
        assertPaperInkMetadataLayout(2f)
    }

    @Test
    @Config(qualifiers = "pl-rPL-" + RobolectricDeviceQualifiers.Pixel8)
    fun paperink_result_multi_source_paper_polish_large_text() {
        setPaperInkSearchContent(darkTheme = false, fontScale = 2f)
        composeTestRule.onRoot().captureRoboImage("src/test/snapshots/global_search_result_multi_paper_pl_200.png")
        assertPaperInkMetadataLayout(2f)
    }

    private fun assertPaperInkMetadataLayout(fontScale: Float) {
        val row = composeTestRule.onNodeWithTag("global_search_result_${multiSource.key}").getBoundsInRoot()
        val narratorLabel = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.book_detail_narrator_label, multiSource.narrator)
        val narrator = composeTestRule.onNodeWithText(narratorLabel, useUnmergedTree = true).getBoundsInRoot()
        val sourceBounds = multiSource.sources.map { source ->
            val node = composeTestRule.onNodeWithText(source.sourceName, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            // FlowRow can retain a wider paragraph from intrinsic measurement.
            // Check the painted line and ellipsis, not unused paragraph width.
            assertFalse("Source height is clipped: ${source.sourceName}", layout.didOverflowHeight)
            for (line in 0 until layout.lineCount) {
                assertFalse("Source is ellipsized: ${source.sourceName}", layout.isLineEllipsized(line))
                assertTrue("Source text exceeds its bounds: ${source.sourceName}",
                    layout.getLineRight(line) <= layout.size.width + 1f)
            }
            node.getBoundsInRoot().also { bounds ->
                assertTrue("Source must follow narrator", bounds.top >= narrator.bottom)
                assertTrue("Source must fit row width", bounds.left >= row.left && bounds.right <= row.right)
            }
        }
        val titleLayouts = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithText(multiSource.title, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        assertFalse("Sources must not squeeze the title", titleLayouts.single().hasVisualOverflow)
        if (fontScale == 2f) {
            assertTrue("Source chips must wrap at 200%", sourceBounds.last().top > sourceBounds.first().top)
        }
    }

    /** Current search emitter under the same migration seam as the app roots. */
    private fun setPaperInkSearchContent(darkTheme: Boolean, fontScale: Float = 1f) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = darkTheme) {
                PaperInkThemedContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    GlobalSearchSurface {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            searchResultsContent(
                                localBooks = emptyList(),
                                globalResults = listOf(multiSource),
                                liveSearchActive = true,
                                isGlobalLoading = false,
                                globalError = false,
                                onOpenLocalBook = {},
                                onPlayLocalBook = {},
                                onOpenGlobalResult = {},
                                catalogCardActionState = CatalogCardActionState.Idle,
                                onOpenCatalogBrowser = {},
                                onPreflightGlobalResult = {}
                            )
                        }
                    }
                    }
                }
            }
        }
        composeTestRule.onNodeWithTag("search_library_header").assertDoesNotExist()
        composeTestRule.onNodeWithTag("search_library_empty").assertExists()
        composeTestRule.onNodeWithTag("global_search_result_${multiSource.key}").assertExists()
        composeTestRule.onNodeWithTag("search_sources_header").assertExists()
        composeTestRule.onNodeWithTag("search_no_results").assertDoesNotExist()
    }

    @Test
    fun result_card_with_duration() {
        // Spec-30 T2 (#217): the row renders the resolved duration (from the
        // local database or the shared metadata cache) under the author.
        setSearchContent(singleSource.copy(durationSeconds = 8_100L))

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/global_search_result_duration.png"
        )
    }

    @Test
    fun source_badge_pill() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                GlobalSearchSurface {
                    Column {
                        MetadataChip(source = "4read")
                        Spacer(modifier = Modifier.padding(4.dp))
                        MetadataChip(source = "Локальна")
                    }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/global_search_source_badge.png"
        )
    }

    // F33 of the 2026-10-28 device audit — the empty search used to draw SIX
    // blocks: «У вашій медіатеці (0)» + «Знайдені книги (0)» headers, the live
    // status, the partial-catalogue note and an M3 empty state with a
    // `SearchOff` placeholder glyph. The canonical state is ONE statement in
    // type (Literata 20 + Meta) with no glyph.
    @Test
    fun empty_search_is_one_statement_without_counted_headers() {
        composeTestRule.setContent {
            // The promise of F33 is the DIRECTION's empty state (Literata 20 +
            // Meta). Rendering under the legacy theme would show Roboto and
            // make the golden lie about the very thing this test exists for.
            AudiobookTheme(darkTheme = true) {
                PaperInkThemedContent {
                GlobalSearchSurface {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        searchResultsContent(
                            localBooks = emptyList(),
                            globalResults = emptyList(),
                            liveSearchActive = true,
                            isGlobalLoading = false,
                            globalError = false,
                            onOpenLocalBook = {},
                            onPlayLocalBook = {},
                            onOpenGlobalResult = {},
                            catalogCardActionState = CatalogCardActionState.Idle,
                            onOpenCatalogBrowser = {},
                            onPreflightGlobalResult = {}
                        )
                    }
                }
                }
            }
        }
        composeTestRule.onNodeWithText("Нічого не знайдено").assertExists()
        composeTestRule.onNodeWithTag("search_no_results").assertExists()
        composeTestRule.onNodeWithTag("search_library_header").assertDoesNotExist()
        composeTestRule.onNodeWithTag("search_sources_header").assertDoesNotExist()
        composeTestRule.onNodeWithTag("global_search_status").assertDoesNotExist()
        composeTestRule.onNodeWithTag("empty_state_icon", useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/search_empty_state.png"
        )
    }
}

/** Same chrome as the other snapshot tests: scheme background, full size. */
@Composable
private fun GlobalSearchSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        content()
    }
}
