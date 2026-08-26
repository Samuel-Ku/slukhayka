package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.paging.PagingConfig
import androidx.paging.Pager
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.slukhayka.audiobooks.data.catalog.CatalogGenre
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.ui.screens.homeFeedContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider

/**
 * Feedback loop for «фільтри над безкінечним фідом не працюють»:
 * drives the SAME reactive chain as MainViewModel.workFeed
 * (combine → flatMapLatest → Pager over the real Room DAO → cachedIn)
 * through the real [homeFeedContent] emitter, taps the filter chips by their
 * test tags, and asserts the user's exact symptom — «тисну чіп і фід зникає».
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h4000dp-normal-long-notround-any-420dpi-keyshidden-nonav", sdk = [36])
class WorkFeedFilterWiringTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao
    private lateinit var catalog: SourceCatalog

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
        catalog = SourceCatalog(
            dao, emptyList(), LibraryImport(dao, context, emptyList()),
            // The composition root's real runner — the regression must prove
            // the BATCHED-write path, not the identity default.
            writeBatchRunner = { block -> db.withTransaction { block() } }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun feedChain(
        sourceFilter: MutableStateFlow<String?>,
        genreFilter: MutableStateFlow<String?>,
        sortByTitle: MutableStateFlow<Boolean>,
        scope: CoroutineScope
    ) = combine(sourceFilter, genreFilter, sortByTitle) { s, g, t -> Triple(s, g, t) }
        .flatMapLatest { (s, g, t) ->
            Pager(config = PagingConfig(pageSize = 30, prefetchDistance = 15, enablePlaceholders = false)) {
                if (t) catalog.pagedWorkFeedByTitle(s, g) else catalog.pagedWorkFeedRecent(s, g)
            }.flow
        }.cachedIn(scope)

    @Test
    fun tapping_a_source_chip_keeps_that_sources_books_visible() = runBlocking {
        repeat(6) { i ->
            catalog.writeWorkEdition("4read", "Чотири $i", "Автор А", "", "https://4read.org/r$i.html")
        }
        repeat(6) { i ->
            catalog.writeWorkEdition("sluhay", "Двічі $i", "Автор Б", "", "https://sluhay.com/s$i.html")
        }

        val sourceFilter = MutableStateFlow<String?>(null)
        val genreFilter = MutableStateFlow<String?>(null)
        val sortByTitle = MutableStateFlow(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var feed: LazyPagingItems<WorkFeedRow>

        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                feed = feedChain(sourceFilter, genreFilter, sortByTitle, scope).collectAsLazyPagingItems()
                val fs by sourceFilter.collectAsState()
                val fg by genreFilter.collectAsState()
                val st by sortByTitle.collectAsState()
                LazyColumn {
                    homeFeedContent(
                        isCatalogLoading = false,
                        hasLibraryBooks = false,
                        sections = emptyList(),
                        catalogGenres = listOf(CatalogGenre("Фантастика", "https://4read.org/fant")),
                        collections = emptyList(),
                        newArrivals = emptyList(),
                        recommendedBooks = emptyList(),
                        personalCycles = emptyList(),
                        shortBooks = emptyList(),
                        longBooks = emptyList(),
                        workFeedItems = feed,
                        feedSourceFilter = fs,
                        feedGenreFilter = fg,
                        feedSortByTitle = st,
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
                        onSetFeedSourceFilter = { sourceFilter.value = it },
                        onSetFeedGenreFilter = { genreFilter.value = it },
                        onSetFeedSortByTitle = { sortByTitle.value = it }
                    )
                }
            }
        }

        compose.waitUntil(20_000) {
            val n = feed.itemCount
            n >= 12
        }
        assertTrue(compose.onAllNodesWithText("Чотири 0").fetchSemanticsNodes().size == 1)
        assertTrue(compose.onAllNodesWithText("Двічі 0").fetchSemanticsNodes().size == 1)

        // THE SYMPTOM UNDER TEST: tap the «4read» chip — the feed must show
        // exactly the 4read books, never disappear.
        compose.onNodeWithTag("feed_source_4read").performClick()
        compose.waitUntil(20_000) {
            feed.loadState.refresh !is androidx.paging.LoadState.Loading &&
                compose.onAllNodesWithText("Чотири 0").fetchSemanticsNodes().size == 1
        }
        assertTrue("4read книги зникли після тапу на чіп", compose.onAllNodesWithText("Чотири 0").fetchSemanticsNodes().size == 1)
        assertTrue("sluhay книги лишились після фільтра", compose.onAllNodesWithText("Двічі 0").fetchSemanticsNodes().size == 0)
    }

    @Test
    fun tapping_a_chip_mid_sync_recovers_the_filtered_feed_once_sync_settles() = runBlocking {
        // Device-shaped scenario: Огляд keeps writing the union
        // catalogue (refreshUnifiedCatalog / deepening crawl) into
        // works/work_sources WHILE the listener taps filters. Every write
        // invalidates the feed's PagingSource; the freshly-switched Pager
        // generation must still complete a page and render.
        repeat(6) { i ->
            catalog.writeWorkEdition("4read", "Чотири $i", "Автор А", "", "https://4read.org/r$i.html")
        }
        repeat(6) { i ->
            catalog.writeWorkEdition("sluhay", "Двічі $i", "Автор Б", "", "https://sluhay.com/s$i.html")
        }

        val sourceFilter = MutableStateFlow<String?>(null)
        val genreFilter = MutableStateFlow<String?>(null)
        val sortByTitle = MutableStateFlow(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var feed: LazyPagingItems<WorkFeedRow>

        val writer = Thread {
            var batch = 0
            while (!Thread.currentThread().isInterrupted) {
                try {
                    kotlinx.coroutines.runBlocking {
                        // One crawled page's worth of merge-on-write rows —
                        // the burst shape the catalogue sync actually
                        // produces (batched into one transaction, exactly as
                        // the composition root's writeBatchRunner does).
                        db.withTransaction {
                            repeat(10) { k ->
                                catalog.writeWorkEdition(
                                    "soundbooks", "Фонова ${batch}_$k", "Автор В", "",
                                    "https://sound-books.net/bg${batch}_$k.html"
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                    return@Thread
                }
                batch++
                Thread.sleep(600)
            }
        }.apply { isDaemon = true; start() }

        try {
            compose.setContent {
                AudiobookTheme(darkTheme = true) {
                    feed = feedChain(sourceFilter, genreFilter, sortByTitle, scope).collectAsLazyPagingItems()
                    val fs by sourceFilter.collectAsState()
                    val fg by genreFilter.collectAsState()
                    val st by sortByTitle.collectAsState()
                    LazyColumn {
                        homeFeedContent(
                            isCatalogLoading = false,
                            hasLibraryBooks = false,
                            sections = emptyList(),
                            catalogGenres = listOf(CatalogGenre("Фантастика", "https://4read.org/fant")),
                            collections = emptyList(),
                            newArrivals = emptyList(),
                            recommendedBooks = emptyList(),
                            personalCycles = emptyList(),
                            shortBooks = emptyList(),
                            longBooks = emptyList(),
                            workFeedItems = feed,
                            feedSourceFilter = fs,
                            feedGenreFilter = fg,
                            feedSortByTitle = st,
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
                            onSetFeedSourceFilter = { sourceFilter.value = it },
                            onSetFeedGenreFilter = { genreFilter.value = it },
                            onSetFeedSortByTitle = { sortByTitle.value = it }
                        )
                    }
                }
            }

            compose.waitUntil(30_000) { feed.itemCount >= 12 }

            compose.onNodeWithTag("feed_source_4read").performClick()
            // The tap lands MID-STORM (the user's exact moment); then the
            // sync settles and the switched generation MUST present exactly
            // the filtered rows — never a permanently blank feed.
            kotlinx.coroutines.delay(1_500)
            writer.interrupt()
            writer.join(3_000)
            compose.waitUntil(30_000) {
                feed.loadState.refresh !is androidx.paging.LoadState.Loading &&
                    compose.onAllNodesWithText("Чотири 0").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("Двічі 0").fetchSemanticsNodes().isEmpty()
            }
            assertTrue(
                "фід не відновився під фоновими записами: count=${feed.itemCount} state=${feed.loadState.refresh}",
                compose.onAllNodesWithText("Чотири 0").fetchSemanticsNodes().isNotEmpty()
            )
        } finally {
            writer.interrupt()
        }
    }
}
