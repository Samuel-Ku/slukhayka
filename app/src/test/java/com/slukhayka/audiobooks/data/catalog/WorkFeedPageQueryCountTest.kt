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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * #831 AC5 — «Одна поверхня — один запит; без N+1».
 *
 * `WorkFeedOriginQueryTest` pins the VALUES the origin flag takes. A value pin
 * cannot see a second query: a per-row lookup would still return the right
 * flags, it would just ask the database once per row. This class measures the
 * COUNT instead — every SQL statement Room sends to SQLite, caught by
 * `RoomDatabase.Builder.setQueryCallback` (Room 2.2+), which fires per
 * STATEMENT, not per DAO method call. That distinction is the whole point:
 * `assertEquals(1, dao.someCallCount)` would be green for an N+1 the moment the
 * N+1 lives inside one method.
 *
 * The surface is the endless feed, and its shape is `MainViewModel.workFeed`
 * (`MainViewModel.kt:3706-3712`): `PagingConfig(pageSize = 30,
 * prefetchDistance = 15, enablePlaceholders = false)` over
 * `SourceCatalog.pagedWorkFeedRecent` / `pagedWorkFeedByTitle`. Paging asks a
 * `PagingSource` for one page per request; the app does not override
 * `initialLoadSize`, so the first refresh asks for 3 × 30 = 90 rows — still ONE
 * statement, because the row count is a LIMIT, not a loop.
 *
 * What is pinned here, as numbers:
 *   - ONE statement per page, whatever the page's row count (30 or 90);
 *   - the same number with the «Лише зі спільної бібліотеки» narrowing ON as
 *     with it OFF — the flag lives inside the page statement
 *     (`AudiobookDao.pagedWorksFeedRecent`, `EXISTS (…) AS fromSharedLibrary`),
 *     so provenance is free;
 *   - a second page costs ONE more statement — per PAGE, not per row;
 *   - Room's own COUNT statement, which appears only when placeholders are
 *     enabled: the app runs placeholders OFF, so it does not pay it. Pinning
 *     both numbers also proves the counter can see more than one statement.
 *
 * The last test is what keeps the first from being vacuous: if the callback
 * were never wired, every other test here would fail (0 statements), but if it
 * were wired and blind to extra statements, only this one would still see 3.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class WorkFeedPageQueryCountTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var catalog: SourceCatalog

    /**
     * Every SQL statement Room sent to SQLite, in order.
     *
     * The callback runs on a direct executor, so a statement is recorded by the
     * query thread before the query that issued it returns — the list is
     * complete by the time `load()` resumes on the test thread. It is a
     * `CopyOnWriteArrayList` because the writes come from Room's query executor.
     */
    private val statements = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(
                object : RoomDatabase.QueryCallback {
                    override fun onQuery(sql: String, args: List<Any?>) {
                        statements += sql
                    }
                },
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
     * The app's page size (`MainViewModel.kt:3709`). Paging's first refresh
     * asks for `initialLoadSize`, which the app leaves at Paging's default of
     * `pageSize * 3` — so both numbers appear below on purpose.
     */
    private val pageSize = 30
    private val initialLoadSize = pageSize * 3

    private fun seedWorks(total: Int, withGroupSource: Int) = runBlocking {
        val dao = db.audiobookDao()
        repeat(total) { i ->
            // Newest first: the feed orders by `addedAt DESC`, so index 0 is
            // the first row a page returns.
            val addedAt = 1_000_000L - i
            dao.upsertWork(
                WorkEntity(
                    id = "w$i",
                    mergeKey = "key-$i",
                    title = "Книга $i",
                    author = "Автор",
                    addedAt = addedAt
                )
            )
            dao.upsertWorkSource(
                WorkSourceEntity(
                    id = "w$i|community|$i",
                    workId = "w$i",
                    sourceId = "community",
                    sourceUrl = if (i < withGroupSource) {
                        "https://t.me/slukhayka"
                    } else {
                        "https://sound-books.net/book-$i"
                    },
                    addedAt = addedAt
                )
            )
        }
    }

    /**
     * Runs one `load()` against the real feed source and returns the statements
     * it cost, newest list first. The list is cleared AFTER seeding, so the
     * seed's own writes are never counted.
     */
    private fun statementsOf(
        filter: WorkFacetFilter,
        byTitle: Boolean = false,
        params: PagingSource.LoadParams<Int>
    ): Pair<List<String>, PagingSource.LoadResult<Int, WorkFeedRow>> {
        statements.clear()
        val result = runBlocking {
            val source = if (byTitle) {
                catalog.pagedWorkFeedByTitle(filter)
            } else {
                catalog.pagedWorkFeedRecent(filter)
            }
            source.load(params)
        }
        return statements.toList() to result
    }

    private fun pageOf(result: PagingSource.LoadResult<Int, WorkFeedRow>): PagingSource.LoadResult.Page<Int, WorkFeedRow> =
        result as? PagingSource.LoadResult.Page
            ?: throw AssertionError("сторінка не завантажилась: $result")

    private fun assertStatements(expected: Int, actual: List<String>, what: String) {
        assertEquals(
            "$what — очікували $expected SQL-statement(ів), побачили ${actual.size}:\n" +
                actual.joinToString("\n") { "  • " + it.replace(Regex("\\s+"), " ").take(160) },
            expected,
            actual.size
        )
    }

    @Test
    fun `one page of the feed costs exactly one SQL statement`() {
        seedWorks(total = 30, withGroupSource = 10)

        val (statements, result) = statementsOf(
            filter = WorkFacetFilter(),
            params = PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = initialLoadSize,
                placeholdersEnabled = false
            )
        )

        // Non-vacuous: the statement has to have RETURNED the page. An empty
        // result would make "one statement" true for the wrong reason.
        val page = pageOf(result)
        assertEquals("сторінка мусить містити всі 30 книжок", 30, page.data.size)
        assertStatements(
            1,
            statements,
            "одна сторінка стрічки (${page.data.size} рядків, loadSize=$initialLoadSize)"
        )
        assertTrue(
            "єдиний statement мусить бути САМЕ запитом сторінки: ${statements.firstOrNull()}",
            statements.single().contains("FROM works w")
        )
    }

    @Test
    fun `the origin flag adds no statement`() {
        seedWorks(total = 30, withGroupSource = 10)

        val (unfiltered, unfilteredResult) = statementsOf(
            filter = WorkFacetFilter(sharedLibraryOnly = false),
            params = PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = initialLoadSize,
                placeholdersEnabled = false
            )
        )
        val (filtered, filteredResult) = statementsOf(
            filter = WorkFacetFilter(sharedLibraryOnly = true),
            params = PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = initialLoadSize,
                placeholdersEnabled = false
            )
        )

        val unfilteredPage = pageOf(unfilteredResult)
        val filteredPage = pageOf(filteredResult)

        // The narrowing is real work, not an empty page: 10 of the 30 rows are
        // the group's, and BOTH worlds must be present without the filter.
        assertEquals("без фільтра видно всі 30", 30, unfilteredPage.data.size)
        assertEquals("з фільтром лишаються 10 книжок групи", 10, filteredPage.data.size)
        assertTrue(
            "жодної сторонньої книжки під фільтром",
            filteredPage.data.all { it.fromSharedLibrary }
        )
        assertTrue(
            "ознака мусить різнити рядки, інакше фільтр вакуумний",
            unfilteredPage.data.any { it.fromSharedLibrary } &&
                unfilteredPage.data.any { !it.fromSharedLibrary }
        )

        assertStatements(1, unfiltered, "сторінка без звуження")
        assertStatements(1, filtered, "сторінка зі звуженням «Лише зі спільної бібліотеки»")
        assertEquals(
            "ознака походження не сміє додавати жодного statement: " +
                "без звуження ${unfiltered.size}, зі звуженням ${filtered.size}",
            unfiltered.size,
            filtered.size
        )
    }

    @Test
    fun `both feed orders cost one statement per page`() {
        seedWorks(total = 30, withGroupSource = 10)

        val (recent, recentResult) = statementsOf(
            filter = WorkFacetFilter(),
            params = PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = pageSize,
                placeholdersEnabled = false
            )
        )
        val (byTitle, byTitleResult) = statementsOf(
            filter = WorkFacetFilter(),
            byTitle = true,
            params = PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = pageSize,
                placeholdersEnabled = false
            )
        )

        assertEquals(30, pageOf(recentResult).data.size)
        assertEquals(30, pageOf(byTitleResult).data.size)
        assertStatements(1, recent, "сторінка з loadSize=$pageSize (найновіші)")
        assertStatements(1, byTitle, "сторінка з loadSize=$pageSize (за назвою)")
    }

    @Test
    fun `a second page costs one more statement, never one per row`() {
        seedWorks(total = 60, withGroupSource = 20)
        val filter = WorkFacetFilter()

        val (firstStatements, firstResult) = statementsOf(
            filter = filter,
            params = PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = pageSize,
                placeholdersEnabled = false
            )
        )
        val firstPage = pageOf(firstResult)
        assertStatements(1, firstStatements, "перша сторінка (${firstPage.data.size} рядків)")
        val nextKey = firstPage.nextKey
            ?: throw AssertionError("у 60 рядків мусить бути друга сторінка, nextKey=null")

        val (secondStatements, secondResult) = statementsOf(
            filter = filter,
            params = PagingSource.LoadParams.Append(
                key = nextKey,
                loadSize = pageSize,
                placeholdersEnabled = false
            )
        )
        val secondPage = pageOf(secondResult)
        assertTrue("друга сторінка мусить бути непорожня", secondPage.data.isNotEmpty())
        assertStatements(1, secondStatements, "друга сторінка (${secondPage.data.size} рядків)")

        // 3 statements for 2 pages is the N+1 signature; 2 is the contract.
        assertStatements(2, firstStatements + secondStatements, "дві сторінки разом")
    }

    /**
     * `enablePlaceholders` is the app's OFF (`MainViewModel.kt:3709`), and that
     * is what keeps Room from asking the database for a second time: with
     * placeholders on, Room's `LimitOffsetPagingSource` runs its own `COUNT`
     * statement beside the page statement. Named here as a number instead of
     * being left implicit — and it is also the counter's own witness: the same
     * wiring that reports 1 above reports 3 below.
     */
    @Test
    fun `placeholders would add Room's count statement, the app does not pay it`() {
        seedWorks(total = 30, withGroupSource = 10)

        val (withPlaceholders, withPlaceholdersResult) = statementsOf(
            filter = WorkFacetFilter(),
            params = PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = pageSize,
                placeholdersEnabled = true
            )
        )

        assertEquals(30, pageOf(withPlaceholdersResult).data.size)
        assertStatements(2, withPlaceholders, "сторінка з enablePlaceholders = true")
        assertEquals(
            "другий statement мусить бути службовим COUNT — інакше це не та причина",
            2,
            withPlaceholders.count { it.contains("COUNT", ignoreCase = true) }
        )

        // The app's own run, in the same test and on the same wiring, pays one.
        val (appShaped, _) = statementsOf(
            filter = WorkFacetFilter(),
            params = PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = pageSize,
                placeholdersEnabled = false
            )
        )
        assertStatements(1, appShaped, "та сама сторінка з enablePlaceholders = false (як у застосунку)")
        assertFalse(
            "службовий COUNT не мусить з'являтися, коли placeholders вимкнено",
            appShaped.any { it.contains("COUNT", ignoreCase = true) }
        )
    }
}
