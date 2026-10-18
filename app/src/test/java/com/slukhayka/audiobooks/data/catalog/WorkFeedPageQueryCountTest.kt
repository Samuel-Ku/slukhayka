package com.slukhayka.audiobooks.data.catalog

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import com.slukhayka.audiobooks.data.facets.WorkFacetFilter
import com.slukhayka.audiobooks.data.imports.LibraryImport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * #831 AC5 — «Одна поверхня — один запит; без N+1», MEASURED.
 *
 * `WorkFeedOriginQueryTest` pins the VALUES the origin flag takes. A value pin
 * cannot see a second query: a per-row lookup would still return the right
 * flags, it would just ask the database once per row. This class counts the
 * statements instead.
 *
 * The mechanism is `RoomDatabase.Builder.setQueryCallback` (Room 2.2+), which
 * Room calls once per STATEMENT it sends to SQLite — not once per DAO method
 * call. `assertEquals(1, someCallCount)` would be green for an N+1 that lives
 * inside one method; a statement log cannot be.
 *
 * The surface is the endless Overview feed, and its shape is
 * `MainViewModel.workFeed` (`MainViewModel.kt:3706-3714`):
 * `PagingConfig(pageSize = 30, prefetchDistance = 15, enablePlaceholders =
 * false)` over `SourceCatalog.pagedWorkFeedRecent` / `pagedWorkFeedByTitle`.
 * The app does not override `initialLoadSize`, so the first refresh asks for
 * 3 × 30 = 90 rows — a LIMIT, not a loop.
 *
 * ## The numbers, measured by a run of this class
 *
 *   - The FIRST page of a feed generation costs **two** statements: the page
 *     `SELECT` and Room's service `SELECT COUNT(*) FROM (<page query>)`. Both
 *     are pinned by name, so the total is never read as "one query" by
 *     accident. The count is not the app's query and not the app's flag: it
 *     belongs to the GENERATION — Room resolves it once per `PagingSource`
 *     instance (`CommonLimitOffsetImpl`'s `itemCount`) and it appears with
 *     `enablePlaceholders` both off and on.
 *   - Every page AFTER the first of the same generation costs **one** (the
 *     `SELECT` alone), and a NEW `PagingSource` instance pays the count again.
 *     All three numbers are pinned in the second-page test, because "how many
 *     statements does this screen send" has three honest answers.
 *   - The «Лише зі спільної бібліотеки» flag costs **zero** extra: the same two
 *     statements, the same SQL text, only the `:sharedLibraryOnly` bind
 *     argument moves. Provenance lives inside the page statement
 *     (`AudiobookDao.pagedWorksFeedRecent` — `EXISTS (…) AS fromSharedLibrary`),
 *     which is exactly what AC5 asks for.
 *   - Nothing scales with the row count: a 1-row page and a 30-row page cost
 *     the same two statements, which is the "без N+1" half of the AC.
 *
 * ## What is excluded from the count, and why (one named category, not a guess)
 *
 * `setQueryCallback` also reports Room's own machinery, and the fixture's
 * seeding makes a lot of it: the first refresh after a write lazily installs
 * the InvalidationTracker's temp triggers and index rows — 32 statements for
 * this schema — and Room posts that work to its query executor, so it can land
 * inside the measured window. A run of this class showed it landing in 5 of 7
 * tests and not in the other 2: timing, not code.
 *
 * So one category is excluded, by NAME: anything naming
 * `room_table_modification_log` (the poll, the `INSERT OR IGNORE` index rows,
 * the `CREATE TEMP TRIGGER` bodies), plus transaction control
 * (`BEGIN IMMEDIATE TRANSACTION`, `END TRANSACTION`, `TRANSACTION SUCCESSFUL`,
 * `ROLLBACK TRANSACTION`, `COMMIT`) — Room 2.7 reports those through the same
 * callback too. Everything else is counted: a
 * FAIL-LOUD direction on purpose. A count filtered by the feed's own marker
 * (`fromSharedLibrary`) would be blind to the N+1 that AC5 forbids, because a
 * per-row cover lookup has a different SQL shape:
 * `SELECT coverImageUrl FROM audiobooks WHERE sourceUrl = ? LIMIT 1`. The
 * control test below proves this counter sees a statement that is not the
 * page's at all, and the recorded mutation
 * (`.mutations/patches/w831-ac5-per-row-origin.patch`: the origin resolved per
 * row on the page path) turns every count in this class red.
 *
 * ## The N+1 next door — found, NOT fixed here
 *
 * `MainViewModel.fillBlockCovers` (`MainViewModel.kt:3012-3035`) asks the
 * database up to THREE times per card without a cover: `coverForSourceUrl`,
 * then `findByMergeKey`, then `findWorkByMergeKey` — 1…3 statements per coverless
 * card, on `refreshCollectiveBlocks()`. It is on the same screen but not the
 * origin feature, and its file is held by an open PR, so AC5's test does not
 * cover it and this change does not touch it. It is named in the PR body as
 * found and NOT fixed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class WorkFeedPageQueryCountTest {

    /** The full page query's projection; both the page and its count carry it. */
    private val feedMarker = "FROM works w"

    /** Every statement Room sent to SQLite, in the order the callback saw it. */
    private val statements = CopyOnWriteArrayList<String>()

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var catalog: SourceCatalog

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            // A direct executor records a statement on Room's own query thread,
            // before the query that issued it returns; the list is complete by
            // the time `load()` resumes. The list is concurrent because the
            // InvalidationTracker polls from another thread.
            .setQueryCallback(
                { sql, _ -> statements += sql },
                Executor { it.run() }
            )
            .build()
        val dao = db.audiobookDao()
        catalog = SourceCatalog(dao, emptyList(), LibraryImport(dao, context, emptyList()))
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * `MainViewModel.kt:3707` — the app's page size. `initialLoadSize` is left
     * at Paging's default, `pageSize * 3`: the first refresh asks for 90 rows.
     */
    private val pageSize = 30
    private val initialLoadSize = pageSize * 3

    /**
     * Room's own machinery, excluded by NAME — see the class KDoc. Two shapes,
     * both observed in a run: the InvalidationTracker's bookkeeping (anything
     * naming `room_table_modification_log`: its poll, the index rows, the temp
     * trigger bodies) and transaction control. Neither is a query of the
     * surface, and the first one lands in the measured window by timing.
     */
    private val transactionControl = setOf(
        "BEGIN IMMEDIATE TRANSACTION",
        "BEGIN EXCLUSIVE TRANSACTION",
        "BEGIN DEFERRED TRANSACTION",
        "END TRANSACTION",
        "TRANSACTION SUCCESSFUL",
        "ROLLBACK TRANSACTION",
        "COMMIT"
    )

    private fun String.isRoomMachinery(): Boolean {
        val sql = trim()
        return sql.contains("room_table_modification") || sql in transactionControl
    }

    /** What one load cost, split into the surface's statements and Room's own. */
    private data class Observed(val counted: List<String>, val machinery: List<String>) {
        fun describe(): String =
            "зараховані (${counted.size}):\n" +
                counted.joinToString("\n") { "  • " + it.label() } +
                "\nмашинерія Room (${machinery.size}):\n" +
                machinery.joinToString("\n") { "  • " + it.label() }

        private fun String.label(): String =
            replace(Regex("\\s+"), " ").trim().let { if (it.length > 150) it.take(150) + "…" else it }
    }

    /** The page's own statement: `SELECT * FROM (<feed query>) LIMIT ? OFFSET ?`. */
    private fun String.isFeedPageSelect(): Boolean =
        trimStart().startsWith("SELECT * FROM (") && contains(feedMarker)

    /** Room's service statement over the same query — not the app's query. */
    private fun String.isFeedServiceCount(): Boolean =
        trimStart().startsWith("SELECT COUNT(*) FROM (") && contains(feedMarker)

    private fun seedWorks(total: Int, withGroupSource: Int) = runBlocking {
        val dao = db.audiobookDao()
        repeat(total) { index ->
            // Newest first: the feed orders by `addedAt DESC`, so index 0 is the
            // first row a page returns.
            val addedAt = 1_000_000L - index
            dao.upsertWork(
                WorkEntity(
                    id = "w$index",
                    mergeKey = "key-$index",
                    title = "Книга $index",
                    author = "Автор",
                    addedAt = addedAt
                )
            )
            dao.upsertWorkSource(
                WorkSourceEntity(
                    id = "w$index|community|$index",
                    workId = "w$index",
                    sourceId = "community",
                    sourceUrl = if (index < withGroupSource) {
                        "https://t.me/slukhayka"
                    } else {
                        "https://sound-books.net/book-$index"
                    },
                    addedAt = addedAt
                )
            )
        }
    }

    /**
     * One `load()` against the real feed source, and the statements it cost.
     * The log is cleared AFTER seeding, so the fixture's own writes are never
     * counted — the seed is not the surface.
     */
    private fun load(
        params: PagingSource.LoadParams<Int>,
        filter: WorkFacetFilter = WorkFacetFilter(),
        byTitle: Boolean = false
    ): Pair<Observed, PagingSource.LoadResult.Page<Int, WorkFeedRow>> =
        loadOn(feedSource(filter, byTitle), params)

    /**
     * The feed's `PagingSource` — ONE instance per generation, the shape Paging
     * itself uses: `Pager` calls the factory once and drives every refresh and
     * append of that generation through the same instance. That matters here:
     * Room's `LimitOffsetPagingSource` carries its service COUNT in the instance
     * (an `AtomicInteger` in `CommonLimitOffsetImpl`), so a FRESH instance pays
     * the count again even for an `Append` — measured, and the reason the
     * second-page test below reuses this instance instead of making a new one.
     */
    private fun feedSource(
        filter: WorkFacetFilter = WorkFacetFilter(),
        byTitle: Boolean = false
    ): PagingSource<Int, WorkFeedRow> =
        if (byTitle) catalog.pagedWorkFeedByTitle(filter) else catalog.pagedWorkFeedRecent(filter)

    private fun loadOn(
        source: PagingSource<Int, WorkFeedRow>,
        params: PagingSource.LoadParams<Int>
    ): Pair<Observed, PagingSource.LoadResult.Page<Int, WorkFeedRow>> {
        statements.clear()
        val result = runBlocking { source.load(params) }
        val page = result as? PagingSource.LoadResult.Page
            ?: throw AssertionError("сторінка стрічки не завантажилась: $result")
        return classify() to page
    }

    /** Splits the log Room just wrote into the surface's statements and Room's own. */
    private fun classify(): Observed {
        val (machinery, counted) = statements.toList().partition { it.isRoomMachinery() }
        return Observed(counted, machinery)
    }

    private fun refresh(
        loadSize: Int = pageSize,
        placeholders: Boolean = false
    ): PagingSource.LoadParams.Refresh<Int> =
        PagingSource.LoadParams.Refresh<Int>(
            key = null,
            loadSize = loadSize,
            placeholdersEnabled = placeholders
        )

    private fun append(key: Int, loadSize: Int = pageSize): PagingSource.LoadParams.Append<Int> =
        PagingSource.LoadParams.Append<Int>(
            key = key,
            loadSize = loadSize,
            placeholdersEnabled = false
        )

    @Test
    fun `the first page of a feed generation costs two statements — the SELECT and Room's COUNT`() {
        seedWorks(total = 30, withGroupSource = 10)

        val (observed, page) = load(refresh(loadSize = initialLoadSize))

        // Non-vacuous: the statements have to have RETURNED the page. A skipped
        // query would make a small count true for the wrong reason.
        assertEquals("сторінка мусить нести всі 30 книжок", 30, page.data.size)
        assertEquals(
            "перша сторінка генерації = SELECT сторінки + службовий COUNT Room:\n" + observed.describe(),
            2,
            observed.counted.size
        )
        assertEquals(
            "рівно один SELECT сторінки:\n" + observed.describe(),
            1,
            observed.counted.count { it.isFeedPageSelect() }
        )
        assertEquals(
            "рівно один службовий COUNT Room (initialLoad робить його на кожен Refresh):\n" +
                observed.describe(),
            1,
            observed.counted.count { it.isFeedServiceCount() }
        )
    }

    @Test
    fun `the origin flag adds no statement`() {
        seedWorks(total = 30, withGroupSource = 10)

        val (off, offPage) = load(refresh(loadSize = initialLoadSize), WorkFacetFilter(sharedLibraryOnly = false))
        val (on, onPage) = load(refresh(loadSize = initialLoadSize), WorkFacetFilter(sharedLibraryOnly = true))

        // The narrowing is real work: 10 of 30 rows are the group's, and both
        // kinds exist without the filter.
        assertEquals("без звуження видно всі 30", 30, offPage.data.size)
        assertEquals("зі звуженням лишаються 10 книжок групи", 10, onPage.data.size)
        assertTrue("жодної сторонньої книжки під звуженням", onPage.data.all { it.fromSharedLibrary })
        assertTrue(
            "ознака мусить різнити рядки, інакше звуження вакуумне",
            offPage.data.any { it.fromSharedLibrary } && offPage.data.any { !it.fromSharedLibrary }
        )

        assertEquals(
            "«Лише зі спільної бібліотеки» не додає statement:\n" +
                "вимкнено:\n" + off.describe() + "\nувімкнено:\n" + on.describe(),
            off.counted.size,
            on.counted.size
        )
        assertEquals("обидва прогони — ті самі два statement-и", 2, on.counted.size)
        assertEquals(
            "ознака міняє bind-аргумент, а не форму запиту:\n" +
                "вимкнено:\n" + off.describe() + "\nувімкнено:\n" + on.describe(),
            off.counted.sorted(),
            on.counted.sorted()
        )
        assertTrue(
            "звуження резолвиться САМЕ запитом сторінки:\n" + on.describe(),
            on.counted.any { it.isFeedPageSelect() } && on.counted.any { it.isFeedServiceCount() }
        )
    }

    /**
     * One generation = one `PagingSource` instance (see [feedSource]), and Room
     * resolves its service COUNT inside that instance: the FIRST page of a
     * generation pays it, every page after that does not. A second PagingSource
     * — a new generation — pays it again, which is a fact about Room's
     * `LimitOffsetPagingSource`, not about the number of pages a listener reads.
     */
    @Test
    fun `a second page of the same generation costs one statement — the count belongs to the generation`() {
        seedWorks(total = 60, withGroupSource = 20)

        val source = feedSource()
        val (first, firstPage) = loadOn(source, refresh(loadSize = pageSize))
        assertEquals("перша сторінка — два statement-и:\n" + first.describe(), 2, first.counted.size)
        assertTrue("перша сторінка несе SELECT і службовий COUNT:\n" + first.describe(),
            first.counted.any { it.isFeedPageSelect() } && first.counted.any { it.isFeedServiceCount() })
        val nextKey = firstPage.nextKey
            ?: throw AssertionError("у 60 рядків мусить бути друга сторінка, nextKey=null")

        val (second, secondPage) = loadOn(source, append(nextKey))
        assertTrue("друга сторінка мусить бути непорожня", secondPage.data.isNotEmpty())
        assertEquals(
            "друга сторінка тієї самої генерації = сам SELECT, без службового COUNT:\n" + second.describe(),
            1,
            second.counted.size
        )
        assertTrue("це SELECT сторінки:\n" + second.describe(), second.counted.single().isFeedPageSelect())

        // 3 for 2 pages is per GENERATION; N+1 for 30 rows would be 60+.
        assertEquals("дві сторінки разом:", 3, first.counted.size + second.counted.size)

        // ...and a NEW generation pays the count again. Named, because it is
        // what a fresh instance costs, not what a page costs.
        val (freshGeneration, _) = load(append(nextKey))
        assertEquals(
            "нова генерація (новий PagingSource) знову платить службовий COUNT:\n" + freshGeneration.describe(),
            2,
            freshGeneration.counted.size
        )
    }

    @Test
    fun `statements do not scale with the number of rows`() {
        seedWorks(total = 30, withGroupSource = 10)

        val (single, singlePage) = load(refresh(loadSize = 1))
        val (full, fullPage) = load(refresh(loadSize = pageSize))

        assertEquals("сторінка в 1 рядок", 1, singlePage.data.size)
        assertEquals("сторінка в $pageSize рядків", pageSize, fullPage.data.size)
        assertEquals(
            "1 рядок і $pageSize рядків коштують однаково — жодного statement на рядок:\n" +
                "1 рядок:\n" + single.describe() + "\n$pageSize рядків:\n" + full.describe(),
            single.counted.size,
            full.counted.size
        )
        assertEquals("і це ті самі два statement-и", 2, full.counted.size)
    }

    @Test
    fun `both feed orders cost the same two statements`() {
        seedWorks(total = 30, withGroupSource = 10)

        val (recent, recentPage) = load(refresh(loadSize = pageSize))
        val (byTitle, byTitlePage) = load(refresh(loadSize = pageSize), byTitle = true)

        assertEquals(pageSize, recentPage.data.size)
        assertEquals(pageSize, byTitlePage.data.size)
        assertEquals(
            "найновіші:\n" + recent.describe(),
            2,
            recent.counted.size
        )
        assertEquals(
            "за назвою:\n" + byTitle.describe(),
            2,
            byTitle.counted.size
        )
    }

    @Test
    fun `enablePlaceholders changes nothing — the service COUNT is not the flag's`() {
        seedWorks(total = 30, withGroupSource = 10)

        val (off, offPage) = load(refresh(loadSize = pageSize, placeholders = false))
        val (on, onPage) = load(refresh(loadSize = pageSize, placeholders = true))

        assertEquals(pageSize, offPage.data.size)
        assertEquals(pageSize, onPage.data.size)
        assertEquals(
            "enablePlaceholders = false (як у застосунку):\n" + off.describe(),
            2,
            off.counted.size
        )
        assertEquals(
            "enablePlaceholders = true:\n" + on.describe(),
            2,
            on.counted.size
        )
    }

    /**
     * The counter's own witness. The page is not the only statement Room sends,
     * and a counter narrowed to the feed's marker would be blind to everything
     * else — including the per-row lookup shape AC5 forbids. A plain
     * `countWorks()` must therefore show up as a counted statement, and its SQL
     * matches NEITHER of the page's two shapes, so this cannot pass by accident.
     */
    @Test
    fun `the counter sees a statement that is not the page's`() {
        seedWorks(total = 3, withGroupSource = 1)

        statements.clear()
        runBlocking { db.audiobookDao().countWorks() }
        val observed = classify()

        assertEquals(
            "лічильник мусить бачити запит поза сторінкою:\n" + observed.describe(),
            1,
            observed.counted.size
        )
        assertTrue(
            "це не жодна з форм запиту сторінки:\n" + observed.describe(),
            observed.counted.none { it.isFeedPageSelect() || it.isFeedServiceCount() }
        )
    }
}
