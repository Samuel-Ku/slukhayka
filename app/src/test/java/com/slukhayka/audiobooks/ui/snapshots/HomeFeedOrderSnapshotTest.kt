package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.catalog.CatalogGenre
import com.slukhayka.audiobooks.data.catalog.CatalogSection
import com.slukhayka.audiobooks.data.catalog.CatalogSectionId
import com.slukhayka.audiobooks.data.catalog.CatalogSeries
import com.slukhayka.audiobooks.data.collections.CollectionMatcher
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.recommend.RecommendationEngine
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.ui.screens.homeFeedContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * spec-28 (#203) — snapshot + order pin for the Огляд feed body
 * ([homeFeedContent]): curated content sits ABOVE the endless «Весь каталог»
 * feed, which is always the screen's last element. A tall viewport
 * (Pixel8's density, 4000dp tall) composes every block, so the order is
 * asserted by y-position across the whole screen and the capture shows the
 * final layout end to end.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h4000dp-normal-long-notround-any-420dpi-keyshidden-nonav", sdk = [36])
class HomeFeedOrderSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val results = listOf(
        GlobalSearchResult(
            title = "Вкради мене... Зараз!",
            author = "Сергій Оріанець",
            mergeKey = "вкради-мене-зараз|сергій-оріанець",
            coverImageUrl = null,
            sources = listOf(GlobalSearchSource("4read", "4read", "https://4read.org/7611.html"))
        ),
        GlobalSearchResult(
            title = "Темна матерія",
            author = "Блейк Крауч",
            mergeKey = "темна-матерія|блейк-крауч",
            coverImageUrl = null,
            sources = listOf(GlobalSearchSource("soundbooks", "Sound-Books", "https://sound-books.net/temna"))
        )
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
        // A «Новинки»-titled section is SKIPPED by typed id (#197) — the
        // cross-source rail is the only place 4read's new arrivals render.
        CatalogSection(title = "Новинки", books = books, id = CatalogSectionId.NEW_ARRIVALS),
        CatalogSection(title = "Популярне", books = books, id = CatalogSectionId.POPULAR),
        CatalogSection(
            title = "Цикли",
            series = listOf(CatalogSeries(title = "Кіберпанк", url = "https://4read.org/cyber", coverImageUrl = null)),
            id = CatalogSectionId.SERIES
        )
    )

    private val collections = listOf(
        CollectionMatcher.MatchedCollection(id = "c1", name = "Нобелівські лауреати", sourceNote = "", books = results),
        CollectionMatcher.MatchedCollection(id = "c2", name = "Букер", sourceNote = "", books = listOf(results.first()))
    )

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

    private val feedRows = listOf(
        WorkFeedRow(
            workId = "w1", mergeKey = "mk1", title = "Місто", author = "Валер'ян Підмогильний",
            coverImageUrl = null, addedAt = 1L, sourceCount = 1
        ),
        WorkFeedRow(
            workId = "w2", mergeKey = "mk2", title = "Тигролови", author = "Іван Багряний",
            coverImageUrl = null, addedAt = 2L, sourceCount = 2
        )
    )

    @Test
    fun curated_content_sits_above_the_endless_feed() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val feedFlow = remember { MutableStateFlow(PagingData.from(feedRows)) }
                    val feedItems = feedFlow.collectAsLazyPagingItems()
                    LazyColumn(modifier = Modifier.fillMaxSize().testTag("home_feed")) {
                        homeFeedContent(
                            isCatalogLoading = false,
                            hasLibraryBooks = true,
                            sections = sections,
                            catalogGenres = listOf(
                                CatalogGenre("Фантастика", "https://4read.org/fant"),
                                CatalogGenre("Детективи", "https://4read.org/det")
                            ),
                            collections = collections,
                            newArrivals = results,
                            recommendedBooks = recommendations,
                            shortBooks = books,
                            longBooks = books,
                            workFeedItems = feedItems,
                            feedSourceFilter = null,
                            feedGenreFilter = null,
                            feedSortByTitle = false,
                            onRefreshCatalog = {},
                            onGoToLibrary = {},
                            onOpenTop100 = {},
                            onOpenPeople = {},
                            onOpenSeriesIndex = {},
                            onOpenCollectionsIndex = {},
                            onOpenGenre = { _, _ -> },
                            onOpenSeries = { _, _ -> },
                            onPlayGlobalSearchResult = {},
                            onOpenRecommendedBook = {},
                            onOpenWorkFeedRow = {},
                            onBookClick = {},
                            onSetFeedSourceFilter = {},
                            onSetFeedGenreFilter = {},
                            onSetFeedSortByTitle = {},
                            onOpenWebSource = {}
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        // Every block composes on the tall viewport — assert the spec-28
        // order by y-position: nav → genres → recommended → rail → duration
        // → 4read sections → collections → CTA → «Весь каталог» (last). The
        // inline «Колекції» blocks sit AFTER the 4read sections (ADR-0017
        // closing pass): «Рекомендовано для вас» is the first curated shelf
        // per the spec order line (spec-28 lines 150-153).
        fun topOf(text: String): Dp =
            composeTestRule.onNodeWithText(text, ignoreCase = true).getBoundsInRoot().top

        assertTrue(topOf("Каталог") < topOf("Жанри"))
        assertTrue(topOf("Жанри") < topOf("Рекомендовано для вас"))
        assertTrue(topOf("Рекомендовано для вас") < topOf("Новинки"))
        assertTrue(topOf("Новинки") < topOf("Короткі"))
        assertTrue(topOf("Короткі") < topOf("Довгі"))
        assertTrue(topOf("Довгі") < topOf("Популярне"))
        assertTrue(topOf("Популярне") < topOf("Цикли"))
        assertTrue(topOf("Цикли") < topOf("Нобелівські лауреати"))
        assertTrue(topOf("Нобелівські лауреати") < topOf("Букер"))
        assertTrue(topOf("Букер") < topOf("Більше книг на Sluhay"))
        assertTrue(topOf("Більше книг на Sluhay") < topOf("Весь каталог"))
        // «Весь каталог» is the last element: its feed cards render below it.
        assertTrue(topOf("Весь каталог") < topOf("Місто"))
        assertTrue(topOf("Місто") < topOf("Тигролови"))

        // The «Новинки»-titled catalogue section is skipped by typed id — the
        // rail is the only place the label renders (exactly-once, no dupes).
        assertEquals(1, composeTestRule.onAllNodesWithText("Новинки", ignoreCase = true).fetchSemanticsNodes().size)

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/home_feed_order.png"
        )
    }
}
