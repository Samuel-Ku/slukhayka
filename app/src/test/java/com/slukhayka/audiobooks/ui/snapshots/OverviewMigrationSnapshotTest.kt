package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.catalog.CatalogSection
import com.slukhayka.audiobooks.data.catalog.CatalogSectionId
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.entries.LibraryNewArrival
import com.slukhayka.audiobooks.data.recommend.RecommendationEngine
import com.slukhayka.audiobooks.data.update.AvailableAppRelease
import com.slukhayka.audiobooks.ui.components.UpdateBanner
import com.slukhayka.audiobooks.ui.screens.HomeHeader
import com.slukhayka.audiobooks.ui.screens.homeFeedContent
import com.slukhayka.audiobooks.ui.theme.PaperInkTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F5/F6/F19/F24/F26 acceptance for the «Огляд» → «Папір і голос» migration.
 *
 * The audit measured ≈703 dp from the top of the screen to the first poster:
 * a legacy M3 search field (≈69 dp), a legacy update `Card` (≈90 dp), two
 * group headings in a row and the legacy 16 dp grid stood between the header
 * and the content. This class pins the replacement on the same phone viewport
 * the device audit used (411×914 dp):
 *
 *  - [search_field_is_a_fifty_two_dp_paper_row] — F24/F6: ONE 52 dp paper row;
 *  - [first_content_sits_within_350_dp] — F5/F19: the first curated card is
 *    within the spec's ≤350 dp instead of ≈703;
 *  - [group_headings_are_gone] — F19: screen → section → card, no GROUP level;
 *  - [chips_row_has_a_scroll_affordance] — F26: the right edge says the row
 *    scrolls instead of cutting the last chip with no hint;
 *  - [update_banner_is_one_compact_paper_row] — F20/F5: ≤56 dp, localised,
 *    one action, and BELOW the first curated shelf;
 *  - [typical_query…]-guards: the TYPED query must stay readable in both
 *    materials — `BasicTextField` does not read `LocalContentColor`, so the
 *    pre-review version rendered it in platform black on the ink surface
 *    (≈1.2:1). The golden is re-recorded here and the rendered pixels are
 *    measured against WCAG AA, so a blind re-record cannot mask it again;
 *  - two goldens (`overview_light` / `overview_dark`) — the recorded first
 *    screen of the migrated tab, both materials (the dark one carries the
 *    typed query, so it is a distinct frame rather than a copy of
 *    `home_feed_phone_fold`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class OverviewMigrationSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** F5 target: the spec allows the first shelf heading at ≤345–350 dp. */
    private val firstContentCeiling = 350f

    private val firstContentTag = "recommended_r1"

    /** A real query, not a placeholder — the typed state is what the review broke. */
    private val typedQuery = "Шевченко"

    private val recommendations = listOf(
        RecommendationEngine.Recommendation(
            candidate = RecommendationEngine.Candidate(
                id = "r1", title = "Хроніки Нарнії", author = "Клайв Стейплз Льюїс",
                genre = "Фантастика"
            ),
            score = 0.8,
            reasonTitle = "Старий і море"
        ),
        RecommendationEngine.Recommendation(
            candidate = RecommendationEngine.Candidate(
                id = "r2", title = "451° за Фаренгейтом", author = "Рей Бредбері",
                genre = "Антиутопія"
            ),
            score = 0.7,
            reasonTitle = "Темна матерія"
        )
    )

    private val libraryArrivals = listOf(
        libraryArrival("a1", "Вкради мене... Зараз!", "Сергій Оріанець", "4read", 2_000L),
        libraryArrival("a2", "Темна матерія", "Блейк Крауч", "Sound-Books", 1_000L)
    )

    private fun libraryArrival(
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

    private val books = listOf(
        CatalogBook(
            id = "b1", title = "Старий і море", author = "Ернест Гемінґвей",
            url = "https://4read.org/b1", coverImageUrl = null
        ),
        CatalogBook(
            id = "b2", title = "Сто років самотності", author = "Габрієль Гарсія Маркес",
            url = "https://4read.org/b2", coverImageUrl = null
        )
    )

    private val sections = listOf(
        CatalogSection(title = "Новинки", books = books, id = CatalogSectionId.NEW_ARRIVALS),
        CatalogSection(title = "Популярне", books = books, id = CatalogSectionId.POPULAR)
    )

    private val feedRows = listOf(
        WorkFeedRow(
            workId = "w1", mergeKey = "mk1", title = "Місто", author = "Валер'ян Підмогильний",
            coverImageUrl = null, addedAt = 1L, sourceCount = 1
        )
    )

    @Test
    fun search_field_is_a_fifty_two_dp_paper_row() {
        renderOverview(dark = false)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("home_search_input").assertIsDisplayed()
        val surface = composeTestRule.onNodeWithTag("home_search_surface")
            .getUnclippedBoundsInRoot()
        // The PR quotes this line as the measured 52 dp (see the test XML).
        println("overview-migration: search surface = ${surface.height}")
        assertTrue(
            "the paper search row must be 52 dp tall, was ${surface.height}",
            surface.height >= 51.5.dp && surface.height <= 52.5.dp
        )
        // One hint, no floating label: the old field carried «Пошук» AND
        // «Пошук книги або автора…» on two lines of the same 69 dp control.
        composeTestRule.onNodeWithText("Пошук книги або автора").assertExists()
        composeTestRule.onNodeWithText("Пошук").assertDoesNotExist()
    }

    @Test
    fun first_content_sits_within_350_dp() {
        renderOverview(dark = false)
        composeTestRule.waitForIdle()

        val chips = composeTestRule.onNodeWithTag("navigation_chip_Рейтинг")
        chips.assertIsDisplayed()
        val firstContentTop = composeTestRule.onNodeWithTag(firstContentTag)
            .getUnclippedBoundsInRoot().top
        println("overview-migration: first content top = ${firstContentTop.value} dp")
        assertTrue(
            "the first curated card sits at ${firstContentTop.value} dp from the top; " +
                "the migration target is ≤ $firstContentCeiling dp (it was ≈703 dp before)",
            firstContentTop.value <= firstContentCeiling
        )
        assertTrue(
            "the chips row must be above the first content",
            chips.getUnclippedBoundsInRoot().top < firstContentTop
        )
    }

    @Test
    fun group_headings_are_gone() {
        renderOverview(dark = false)
        composeTestRule.waitForIdle()

        // F19: the two GROUP captions are no longer rendered — the screen has
        // one heading level (screen 34 → section 21 → card), and «Відкрити
        // нове» no longer stands right above «Новинки» with nothing between.
        composeTestRule.onNodeWithText("Для вас").assertDoesNotExist()
        composeTestRule.onNodeWithText("Відкрити нове").assertDoesNotExist()
        composeTestRule.onNodeWithText("Швидкі переходи").assertDoesNotExist()
        composeTestRule.onNodeWithText("Рекомендовано для вас").assertExists()
        composeTestRule.onNodeWithText("Новинки").assertExists()
    }

    @Test
    fun chips_row_has_a_scroll_affordance() {
        renderOverview(dark = false)
        composeTestRule.waitForIdle()

        // F26: on a phone the navigation row is wider than the screen, so the
        // right-edge fade must be present — the last chip is never silently
        // cut off. The row-level test below pins the other half: at the end of
        // the scroll the fade is GONE, so it really is an affordance.
        composeTestRule.onNodeWithTag("home_chips_scroll_hint").assertExists()
    }

    @Test
    fun chips_scroll_hint_disappears_at_the_end_of_the_row() {
        renderOverview(dark = false)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("home_chips_row")
            .performScrollToNode(hasTestTag("navigation_chip_Колекції"))
        // `performScrollToNode` stops as soon as the item is visible; the hint
        // only disappears at the END of the row, so settle it there explicitly.
        repeat(3) {
            composeTestRule.onNodeWithTag("home_chips_row").performTouchInput { swipeLeft() }
            composeTestRule.waitForIdle()
        }

        composeTestRule.onNodeWithTag("home_chips_scroll_hint").assertDoesNotExist()
    }

    @Test
    fun update_banner_is_one_compact_paper_row() {
        renderOverview(
            dark = false,
            banner = {
                UpdateBanner(
                    update = AvailableAppRelease("1.2.3", "https://example.invalid/app.apk"),
                    onDownload = {},
                    onDismiss = {},
                )
            },
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Доступна версія 1.2.3").assertExists()
        composeTestRule.onNodeWithText("Оновити").assertExists()

        val banner = composeTestRule.onNodeWithTag("app_update_banner")
            .getUnclippedBoundsInRoot()
        println("overview-migration: update banner top = ${banner.top.value} dp, height = ${banner.height}")
        assertTrue(
            "the update row must stay ≤56 dp tall, was ${banner.height}",
            banner.height <= 56.dp
        )
        // F5: the rare action rides BELOW the first curated shelf — it must
        // never be the first thing between the search row and the content.
        val firstContentTop = composeTestRule.onNodeWithTag(firstContentTag)
            .getUnclippedBoundsInRoot().top
        assertTrue(
            "the update banner must sit below the first curated shelf",
            banner.top > firstContentTop
        )
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun update_banner_speaks_english() {
        renderBannerOnly()
        composeTestRule.onNodeWithText("Version 1.2.3 is available").assertExists()
        composeTestRule.onNodeWithText("Update").assertExists()
    }

    @Test
    @Config(qualifiers = "pl-rPL")
    fun update_banner_speaks_polish() {
        renderBannerOnly()
        composeTestRule.onNodeWithText("Dostępna wersja 1.2.3").assertExists()
        composeTestRule.onNodeWithText("Zaktualizuj").assertExists()
    }

    /**
     * Review blocker (#1542) — the TYPED query was rendered in platform black
     * on the ink surface (≈1.2:1): `BasicTextField` paints with the style it
     * gets and does not fall back to `LocalContentColor`. A re-recorded golden
     * cannot notice a colour that the whole suite already accepted, so this
     * test reads the rendered pixels of the field itself and measures WCAG
     * contrast between the background and the most contrasting glyph pixel.
     *
     * The captures are also the committed typed-state goldens
     * (`home_search_typed_light.png` / `_dark.png`); in record mode the file
     * is written before it is read, so a bad re-record fails HERE instead of
     * being laundered into the baseline.
     */
    @Test
    fun typed_query_text_is_readable_in_paper_theme() {
        assertTypedQueryContrast(dark = false, golden = "home_search_typed_light.png")
    }

    @Test
    fun typed_query_text_is_readable_in_ink_theme() {
        assertTypedQueryContrast(dark = true, golden = "home_search_typed_dark.png")
    }

    private fun assertTypedQueryContrast(dark: Boolean, golden: String) {
        renderOverview(dark = dark, searchQuery = typedQuery)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(typedQuery).assertExists()

        val file = File("src/test/snapshots/$golden")
        composeTestRule.onNodeWithTag("home_search_input")
            .captureRoboImage(filePath = file.path)
        val image = requireNotNull(ImageIO.read(file)) {
            "the typed-query golden $golden was neither recorded nor committed"
        }

        val occurrences = HashMap<Int, Int>()
        var darkest = 1.0
        var lightest = 0.0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y) or (0xFF shl 24)
                occurrences.merge(argb, 1, Int::plus)
                val luminance = relativeLuminance(argb)
                darkest = min(darkest, luminance)
                lightest = max(lightest, luminance)
            }
        }
        val background = occurrences.maxByOrNull { it.value }!!.key
        val backgroundLuminance = relativeLuminance(background)
        // The glyph pixel is whichever extreme is further from the background.
        val textLuminance =
            if (abs(backgroundLuminance - darkest) > abs(backgroundLuminance - lightest)) {
                darkest
            } else {
                lightest
            }
        val contrast = (max(backgroundLuminance, textLuminance) + 0.05) /
            (min(backgroundLuminance, textLuminance) + 0.05)
        println(
            "overview-migration: typed query contrast = ${"%.2f".format(contrast)}:1 " +
                "(${if (dark) "ink" else "paper"})"
        )
        assertTrue(
            "the typed search query is only ${"%.2f".format(contrast)}:1 against its field " +
                "in the ${if (dark) "ink" else "paper"} theme — WCAG AA needs ≥4.5:1 " +
                "(the pre-review bug rendered platform black on the ink surface)",
            contrast >= 4.5
        )
    }

    /** WCAG 2.x relative luminance of an opaque ARGB colour. */
    private fun relativeLuminance(argb: Int): Double {
        fun channel(value: Int): Double {
            val s = value / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel((argb shr 16) and 0xFF) +
            0.7152 * channel((argb shr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)
    }

    @Test
    fun overview_light_golden() {
        renderOverview(dark = false)
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/overview_light.png"
        )
    }

    @Test
    fun overview_dark_golden() {
        // The dark frame carries the typed query on purpose: it is the ink
        // version of the review blocker, and it keeps this golden distinct
        // from `home_feed_phone_fold.png` (same feed, no header).
        renderOverview(dark = true, searchQuery = typedQuery)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(typedQuery).assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/overview_dark.png"
        )
    }

    private fun renderBannerOnly() {
        composeTestRule.setContent {
            PaperInkTheme(darkTheme = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    UpdateBanner(
                        update = AvailableAppRelease("1.2.3", "https://example.invalid/app.apk"),
                        onDownload = {},
                        onDismiss = {},
                    )
                }
            }
        }
    }

    /** The real «Огляд» first screen: the header row + the migrated feed body. */
    private fun renderOverview(
        dark: Boolean,
        banner: (@Composable () -> Unit)? = null,
        searchQuery: String = "",
    ) {
        composeTestRule.setContent {
            PaperInkTheme(darkTheme = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val feedFlow = remember { MutableStateFlow(PagingData.from(feedRows)) }
                    val feedItems = feedFlow.collectAsLazyPagingItems()
                    LazyColumn(modifier = Modifier.fillMaxSize().testTag("home_screen")) {
                        item {
                            HomeHeader(
                                searchQuery = searchQuery,
                                onRefresh = {},
                                onSearchQueryChange = {}
                            )
                        }
                        homeFeedContent(
                            isCatalogLoading = false,
                            hasLibraryBooks = true,
                            sections = sections,
                            genreFacetOptions = emptyList(),
                            collections = emptyList(),
                            newArrivals = libraryArrivals,
                            recommendedBooks = recommendations,
                            recommendationsReady = true,
                            personalCycles = emptyList(),
                            shortBooks = emptyList(),
                            longBooks = emptyList(),
                            workFeedItems = feedItems,
                            feedGenreFilters = emptySet(),
                            feedSortByTitle = false,
                            onRefreshCatalog = {},
                            onGoToLibrary = {},
                            onOpenTop100 = {},
                            onOpenPeople = {},
                            onOpenSeriesIndex = {},
                            onOpenCollectionsIndex = {},
                            onOpenSeries = { _, _ -> },
                            onOpenRecommendedBook = {},
                            onOpenWorkFeedRow = {},
                            onBookClick = {},
                            onSetFeedGenreFilters = {},
                            onSetFeedSortByTitle = {},
                            updateBanner = banner,
                        )
                    }
                }
            }
        }
    }
}
