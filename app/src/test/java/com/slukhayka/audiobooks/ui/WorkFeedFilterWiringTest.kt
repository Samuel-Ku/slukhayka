package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.paging.PagingConfig
import androidx.paging.Pager
import androidx.paging.cachedIn
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.GenreFacetOption
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.facets.WorkFacetFilter
import com.slukhayka.audiobooks.data.facets.InMemoryFacetSyncCursorStore
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.metadata.FacetAssertion
import com.slukhayka.audiobooks.data.metadata.FacetGenre
import com.slukhayka.audiobooks.testing.FakeSharedBookMetaStore
import com.slukhayka.audiobooks.ui.screens.homeFeedContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
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
 * through the real [homeFeedContent] emitter, taps the genre chips by their
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
        genreFilters: MutableStateFlow<Set<String>>,
        sortByTitle: MutableStateFlow<Boolean>,
        scope: CoroutineScope,
        // Spec-45 (#405) T6 (#494): the content-language dimension rides the
        // same chain as MainViewModel.workFeed (the persisted pref flow).
        languages: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
    ) = combine(genreFilters, sortByTitle, languages) { genres, byTitle, langs -> Triple(genres, byTitle, langs) }
        .flatMapLatest { (genres, byTitle, langs) ->
            val filter = WorkFacetFilter(genreIds = genres, languages = langs)
            Pager(config = PagingConfig(pageSize = 30, prefetchDistance = 15, enablePlaceholders = false)) {
                if (byTitle) catalog.pagedWorkFeedByTitle(filter) else catalog.pagedWorkFeedRecent(filter)
            }.flow
        }.cachedIn(scope)

    // #915 secondary hypothesis — RECORDED, not proven, and deliberately NOT
    // "fixed" here. Every test builds `CoroutineScope(SupervisorJob() +
    // Dispatchers.IO)` and passes it to `cachedIn` above, then never cancels
    // it; `@After` closes the database while those scopes are still alive, and
    // five tests share one JVM (one fork per class). A leaked scope can
    // therefore keep a Pager generation running against a closed DB. It is not
    // the root of the `:517` hang — the captured state below is what decides —
    // but if the artifact ever shows a generation that never completes while
    // the previous test ended mid-refresh, this is the next thing to check.
    @Test
    fun tapping_a_genre_chip_keeps_matching_books_visible() = runBlocking {
        repeat(6) { i ->
            catalog.writeWorkEdition(
                "4read", "Чотири $i", "Автор А", "", "https://4read.org/r$i.html",
                genreTexts = listOf("Фантастика")
            )
        }
        repeat(6) { i ->
            catalog.writeWorkEdition(
                "sluhay", "Двічі $i", "Автор Б", "", "https://sluhay.com/s$i.html",
                genreTexts = listOf("Детектив")
            )
        }

        val genreFilters = MutableStateFlow<Set<String>>(emptySet())
        val sortByTitle = MutableStateFlow(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var feed: LazyPagingItems<WorkFeedRow>

        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                feed = remember { feedChain(genreFilters, sortByTitle, scope) }.collectAsLazyPagingItems()
                val fg by genreFilters.collectAsState()
                val st by sortByTitle.collectAsState()
                LazyColumn {
                    homeFeedContent(
                        isCatalogLoading = false,
                        hasLibraryBooks = false,
                        sections = emptyList(),
                        genreFacetOptions = listOf(
                            GenreFacetOption("science-fiction", "Фантастика", 6),
                            GenreFacetOption("detective", "Детективи", 6)
                        ),
                        collections = emptyList(),
                        newArrivals = emptyList(),
                        recommendedBooks = emptyList(),
                        personalCycles = emptyList(),
                        shortBooks = emptyList(),
                        longBooks = emptyList(),
                        workFeedItems = feed,
                        feedGenreFilters = fg,
                        feedSortByTitle = st,
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
                        onSetFeedGenreFilters = { genreFilters.value = it },
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

        // THE SYMPTOM UNDER TEST: tap the genre chip — the feed must show
        // matching books and never disappear.
        compose.onNodeWithTag("feed_filters").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("feed_genre_science-fiction").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("feed_genre_science-fiction").performClick()
        compose.waitUntil(20_000) {
            feed.loadState.refresh !is androidx.paging.LoadState.Loading &&
                compose.onAllNodesWithText("Чотири 0").fetchSemanticsNodes().size == 1
        }
        assertTrue("книги жанру зникли після тапу на чіп", compose.onAllNodesWithText("Чотири 0").fetchSemanticsNodes().size == 1)
        assertTrue("інший жанр лишився після фільтра", compose.onAllNodesWithText("Двічі 0").fetchSemanticsNodes().size == 0)
    }

    @Test
    fun tapping_a_chip_mid_sync_recovers_the_filtered_feed_once_sync_settles() = runBlocking {
        // Device-shaped scenario: Огляд keeps writing the union
        // catalogue (refreshUnifiedCatalog / deepening crawl) into
        // works/work_sources WHILE the listener taps filters. Every write
        // invalidates the feed's PagingSource; the freshly-switched Pager
        // generation must still complete a page and render.
        repeat(6) { i ->
            catalog.writeWorkEdition(
                "4read", "Чотири $i", "Автор А", "", "https://4read.org/r$i.html",
                genreTexts = listOf("Фантастика")
            )
        }
        repeat(6) { i ->
            catalog.writeWorkEdition(
                "sluhay", "Двічі $i", "Автор Б", "", "https://sluhay.com/s$i.html",
                genreTexts = listOf("Детектив")
            )
        }

        val genreFilters = MutableStateFlow<Set<String>>(emptySet())
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
        }.apply { isDaemon = true }

        try {
            compose.setContent {
                AudiobookTheme(darkTheme = true) {
                    feed = remember { feedChain(genreFilters, sortByTitle, scope) }.collectAsLazyPagingItems()
                    val fg by genreFilters.collectAsState()
                    val st by sortByTitle.collectAsState()
                    LazyColumn {
                        homeFeedContent(
                            isCatalogLoading = false,
                            hasLibraryBooks = false,
                            sections = emptyList(),
                            genreFacetOptions = listOf(
                                GenreFacetOption("science-fiction", "Фантастика", 6),
                                GenreFacetOption("detective", "Детективи", 6)
                            ),
                            collections = emptyList(),
                            newArrivals = emptyList(),
                            recommendedBooks = emptyList(),
                            personalCycles = emptyList(),
                            shortBooks = emptyList(),
                            longBooks = emptyList(),
                            workFeedItems = feed,
                            feedGenreFilters = fg,
                            feedSortByTitle = st,
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
                            onSetFeedGenreFilters = { genreFilters.value = it },
                            onSetFeedSortByTitle = { sortByTitle.value = it }
                        )
                    }
                }
            }

            compose.waitUntil(30_000) { feed.itemCount >= 12 }

            writer.start()
            kotlinx.coroutines.delay(100)
            compose.onNodeWithTag("feed_filters").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("feed_genre_science-fiction").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("feed_genre_science-fiction").performClick()
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

    @Test
    fun landing_delta_keeps_the_selected_filter_and_refreshes_the_paged_feed() = runBlocking {
        catalog.writeWorkEdition("4read", "Дюна", "Френк Герберт", "", "https://4read.org/dune")
        catalog.writeWorkEdition("4read", "Відьмак", "Анджей Сапковський", "", "https://4read.org/witcher")
        val works = dao.observeWorks().first().associateBy { it.title }
        val shared = FakeSharedBookMetaStore()
        shared.putFacet(
            FacetAssertion.Work(
                workId = works.getValue("Дюна").id,
                sourceId = "community",
                genres = listOf(FacetGenre("science-fiction", "Фантастика")),
                observedAt = 10,
                updatedAt = 10
            )
        )
        catalog = SourceCatalog(
            dao,
            emptyList(),
            LibraryImport(dao, context, emptyList()),
            sharedFacetStore = shared,
            facetSyncCursorStore = InMemoryFacetSyncCursorStore(),
            facetSyncNowMillis = { 11 }
        )
        val genreFilters = MutableStateFlow(setOf("science-fiction"))
        val sortByTitle = MutableStateFlow(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var feed: LazyPagingItems<WorkFeedRow>

        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                feed = remember { feedChain(genreFilters, sortByTitle, scope) }.collectAsLazyPagingItems()
                val fg by genreFilters.collectAsState()
                val st by sortByTitle.collectAsState()
                LazyColumn {
                    homeFeedContent(
                        isCatalogLoading = false,
                        hasLibraryBooks = false,
                        sections = emptyList(),
                        genreFacetOptions = listOf(
                            GenreFacetOption("science-fiction", "Фантастика", 1)
                        ),
                        collections = emptyList(),
                        newArrivals = emptyList(),
                        recommendedBooks = emptyList(),
                        personalCycles = emptyList(),
                        shortBooks = emptyList(),
                        longBooks = emptyList(),
                        workFeedItems = feed,
                        feedGenreFilters = fg,
                        feedSortByTitle = st,
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
                        onSetFeedGenreFilters = { genreFilters.value = it },
                        onSetFeedSortByTitle = { sortByTitle.value = it }
                    )
                }
            }
        }

        compose.waitUntil(20_000) {
            feed.loadState.refresh !is androidx.paging.LoadState.Loading && feed.itemCount == 0
        }
        catalog.syncSharedFacets(pageSize = 10, maxPages = 2)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Дюна").fetchSemanticsNodes().size == 1
        }

        assertTrue(genreFilters.value == setOf("science-fiction"))
        assertTrue(compose.onAllNodesWithText("Дюна").fetchSemanticsNodes().size == 1)
        assertTrue(compose.onAllNodesWithText("Відьмак").fetchSemanticsNodes().isEmpty())
    }

    /** Applies one Edition language facet to a seeded Work (T4 helper shape). */
    private suspend fun languageOf(workId: String, editionId: String, language: String) {
        catalog.facetWriter.apply(
            listOf(
                com.slukhayka.audiobooks.data.facets.LocalFacetDelta(
                    work = com.slukhayka.audiobooks.data.facets.WorkFacetDelta(workId),
                    editions = listOf(
                        com.slukhayka.audiobooks.data.facets.EditionFacetDelta(
                            editionId = editionId,
                            workId = workId,
                            language = language,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                )
            )
        )
    }

    /**
     * #915 diagnostics: one instant of the feed state, formatted so it lands
     * verbatim in the JUnit XML failure message. That artifact
     * (`test-results-room-*`) is the only place the full `waitUntil` stack and
     * its message survive — the Gradle console prints just the first frame.
     */
    private fun snapshotFeedState(
        label: String,
        languages: MutableStateFlow<Set<String>>,
        feed: LazyPagingItems<WorkFeedRow>,
    ): String {
        val kobzar = compose.onAllNodesWithText("Кобзар").fetchSemanticsNodes().size
        val pride = compose.onAllNodesWithText("Pride and Prejudice").fetchSemanticsNodes().size
        return "$label: langs=${languages.value} itemCount=${feed.itemCount} " +
            "refresh=${feed.loadState.refresh} append=${feed.loadState.append} " +
            "prepend=${feed.loadState.prepend} kobzar=$kobzar pride=$pride"
    }

    // Spec-51 (#742): the SAME persisted content-language preference feeds the
    // Pager, so a change made on the «Мови контенту» screen re-filters the
    // endless feed live, no restart (US7/US8). The chip no longer cycles — it
    // opens that screen (pinned by the wiring test below).
    @Test
    fun changing_the_language_preference_re_filters_the_feed_without_restart() = runBlocking {
        catalog.writeWorkEdition("4read", "Pride and Prejudice", "Jane Austen", "", "https://4read.org/p.html")
        catalog.writeWorkEdition("4read", "Кобзар", "Тарас Шевченко", "", "https://4read.org/k.html")
        val works = dao.observeWorks().first().associateBy { it.title }
        languageOf(works.getValue("Pride and Prejudice").id, "pp-en", "en")
        languageOf(works.getValue("Кобзар").id, "kobzar-uk", "uk")

        val genreFilters = MutableStateFlow<Set<String>>(emptySet())
        val sortByTitle = MutableStateFlow(false)
        // «Усі» is the empty selection (spec-51): both books show at first.
        val languages = MutableStateFlow(emptySet<String>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var feed: LazyPagingItems<WorkFeedRow>

        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                // Match the ViewModel: one stable flow, reactive filter values.
                feed = remember { feedChain(genreFilters, sortByTitle, scope, languages) }.collectAsLazyPagingItems()
                val langs by languages.collectAsState()
                LazyColumn {
                    homeFeedContent(
                        isCatalogLoading = false,
                        hasLibraryBooks = false,
                        sections = emptyList(),
                        genreFacetOptions = emptyList(),
                        collections = emptyList(),
                        newArrivals = emptyList(),
                        recommendedBooks = emptyList(),
                        personalCycles = emptyList(),
                        shortBooks = emptyList(),
                        longBooks = emptyList(),
                        workFeedItems = feed,
                        feedGenreFilters = genreFilters.value,
                        feedSortByTitle = sortByTitle.value,
                        contentLanguages = langs,
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
                        onSetFeedSortByTitle = {}
                    )
                }
            }
        }

        compose.waitUntil(20_000) { feed.itemCount >= 2 }
        assertTrue(compose.onAllNodesWithText("Pride and Prejudice").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Кобзар").fetchSemanticsNodes().isNotEmpty())

        // Усі → Українська: the English book disappears, the chip reflects it.
        // The genre-test idiom: wait for the restarted Pager's refresh to
        // COMPLETE (a raw itemCount can sit on the previous generation's
        // items while refresh is Loading).
        //
        // #915 — the wait MUST include the DISAPPEARANCE it is about to
        // assert, not just the survival of the book that stays visible.
        // `kobzar == 1` is ALREADY true in the «Усі» generation this test
        // just asserted, and `refresh !is Loading` is true for a moment
        // before `flatMapLatest` restarts the Pager — so both conditions
        // together were satisfiable BEFORE the re-filter happened. The wait
        // then returned instantly and the very next line raced the real
        // (asynchronous, Dispatchers.IO) re-filter, which is the flake:
        // it failed as "Pride and Prejudice is still on screen" only when
        // the machine was slow enough for the restart to land late.
        // Waiting for the whole post-condition makes the assertion
        // deterministic — on a fast run it returns as soon as the refilter
        // settles, on a slow one it waits instead of guessing.
        languages.value = setOf("uk")
        var diag: String = ""
        try {
            compose.waitUntil(30_000) {
                val refresh = feed.loadState.refresh
                val kobzar = compose.onAllNodesWithText("Кобзар").fetchSemanticsNodes().size
                val pride = compose.onAllNodesWithText("Pride and Prejudice").fetchSemanticsNodes().size
                diag = "langs=${languages.value} itemCount=${feed.itemCount} refresh=$refresh kobzar=$kobzar pride=$pride"
                refresh !is androidx.paging.LoadState.Loading && kobzar == 1 && pride == 0
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Усі→Українська refilter timed out; state: $diag", e)
        }
        assertTrue(compose.onAllNodesWithText("Кобзар").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Pride and Prejudice").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Кобзар").fetchSemanticsNodes().isNotEmpty())
        // The UI language is independent of the selected content language.
        compose.onNodeWithContentDescription(context.getString(
            com.slukhayka.audiobooks.R.string.content_language_chip_label,
            context.getString(com.slukhayka.audiobooks.R.string.content_language_uk)
        )).assertExists()

        // Українська → English: the opposite world, again without restart.
        // #915 — same rule as above: wait for BOTH halves of the change
        // (the reappearance AND the disappearance), otherwise the wait can
        // return on the previous generation and the next line races it.
        //
        // #915 (recurrence): THIS is the wait that actually timed out in CI
        // (run 35436697401 attempt 1 → `ComposeTimeoutException` at
        // `WorkFeedFilterWiringTest.kt:517` in the `test-results-room-*` XML
        // artifact), NOT the uk wait above. The Gradle console only prints the
        // first frame of the stack — the method signature at `:416` — which is
        // why an earlier pass analysed the wrong phase; the uk-phase
        // try/catch therefore instrumented a phase that never fails and its
        // body was empty. The same diagnostic is duplicated here so the NEXT
        // recurrence arrives already diagnosed instead of being restarted: the
        // full feed state is captured on every poll, the last state before the
        // timeout is kept, and it is re-thrown inside the `AssertionError`
        // message — the message is what lands in the XML artifact we read.
        //
        // The wait itself is NOT weakened: same 30_000 ms, same condition. The
        // captured state separates the three live hypotheses:
        //   H1 `refresh` stuck `Loading` — the restarted generation never
        //      completes (a real hang);
        //   H2 `refresh` settled, but the uk generation was never replaced
        //      (`kobzar=1, pride=0`, identical to the after-uk snapshot) — the
        //      new filter did not take effect;
        //   H3 the en filter produced nothing (`itemCount=0, pride=0,
        //      kobzar=0`) — an empty result set, not a hang.
        val afterUkState = snapshotFeedState("after-uk", languages, feed)
        languages.value = setOf("en")
        var enFirstPoll: String = ""
        var enDiag: String = ""
        try {
            compose.waitUntil(30_000) {
                val refresh = feed.loadState.refresh
                val append = feed.loadState.append
                val prepend = feed.loadState.prepend
                // A raw `itemCount`/semantics snapshot can still describe the
                // PREVIOUS generation — that is exactly the #915 trap — so it
                // is reported next to the load states, never instead of them.
                val count = feed.itemCount
                val kobzar = compose.onAllNodesWithText("Кобзар").fetchSemanticsNodes().size
                val pride = compose.onAllNodesWithText("Pride and Prejudice").fetchSemanticsNodes().size
                val state = "langs=${languages.value} itemCount=$count refresh=$refresh " +
                    "append=$append prepend=$prepend kobzar=$kobzar pride=$pride"
                if (enFirstPoll.isEmpty()) enFirstPoll = state
                enDiag = state
                refresh !is androidx.paging.LoadState.Loading && languages.value == setOf("en") &&
                    pride == 1 && kobzar == 0
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError(
                "Українська→English refilter timed out; $afterUkState; " +
                    "first-en-poll: $enFirstPoll; last-en-poll: $enDiag",
                e
            )
        }
        assertTrue(compose.onAllNodesWithText("Кобзар").fetchSemanticsNodes().isEmpty())
    }

    // Spec-51 (#742) T3: the chip stopped cycling (two languages cycled, forty
    // do not) — a tap opens the one «Мови контенту» destination instead.
    @Test
    fun the_language_chip_opens_the_language_screen() {
        var opened = false
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                com.slukhayka.audiobooks.ui.screens.ContentLanguageChip(setOf("uk")) { opened = true }
            }
        }

        compose.onNodeWithTag("feed_language").performClick()

        assertTrue(opened)
    }
}
