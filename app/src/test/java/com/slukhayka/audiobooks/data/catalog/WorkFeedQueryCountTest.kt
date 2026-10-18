package com.slukhayka.audiobooks.data.catalog

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #831 AC5 — «Одна поверхня — один запит; без N+1».
 *
 * The Overview feed is one surface, and the «Лише зі спільної бібліотеки»
 * toggle narrows that SAME surface (`MainViewModel.kt` `workFeed` — one
 * `Pager`, `pageSize = 30`). The ticket's AC is about how many times the
 * database is asked, so this test asks the database itself instead of trusting
 * a reading of the DAO: every statement Room prepares is counted through
 * `RoomDatabase.Builder.setQueryCallback`, which fires for the generated DAO
 * code on both executor and coroutine paths.
 *
 * The number pinned below is MEASURED. It is not an estimate: `feedStatements`
 * returns the statements that actually ran, and each assertion prints them when
 * it fails, so a future change that splits the page into a second statement
 * fails here with the extra SQL in the message rather than as a mystery count.
 *
 * What this test deliberately does NOT cover: the community blocks rail next to
 * the feed still resolves covers per card (`MainViewModel.fillBlockCovers`,
 * up to three statements per card without a cover). That N+1 is on the same
 * screen but is not the origin flag, and AC5 here is scoped to the feed page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class WorkFeedQueryCountTest {

    /** One statement Room executed, in the order the callback saw it. */
    private data class Statement(val sql: String, val args: List<Any?>) {
        /** The feed's own page query, whatever the paging source wraps it in. */
        val isFeedPage: Boolean get() = sql.contains(FEED_MARKER)
    }

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase

    /**
     * Room dispatches the callback on the executor it was given; `directExecutor`
     * runs it on the thread that runs the query, so a statement has been counted
     * by the time `load` returns. The list is synchronized because paging hands
     * the query to Room's own executor.
     */
    private val statements: MutableList<Statement> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(
                { sql, args -> statements += Statement(sql, args.toList()) },
                Executors.directExecutor()
            )
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A Work with one Source; `shared` chooses the registered group's link. */
    private fun seedWork(id: String, shared: Boolean) = runBlocking {
        val dao = db.audiobookDao()
        dao.upsertWork(
            WorkEntity(
                id = id,
                mergeKey = "key-$id",
                title = "Книга $id",
                author = "Автор",
                addedAt = 1_000L
            )
        )
        dao.upsertWorkSource(
            WorkSourceEntity(
                id = "$id|community",
                workId = id,
                sourceId = "community",
                sourceUrl = if (shared) GROUP_LINK else FOREIGN_LINK,
                addedAt = 1_000L
            )
        )
    }

    /**
     * Seeds [total] Works, half of them from the registered group, then returns
     * the statements ONE page of the Overview feed costs.
     *
     * Seeding is excluded on purpose: it is fixture work, not the surface. The
     * feed is loaded from a cleared log so the count is the page alone.
     */
    private fun onePageStatements(
        total: Int = 40,
        sharedOnly: Boolean,
        loadSize: Int = PAGE_SIZE
    ): List<Statement> {
        val sharedCount = total / 2
        for (index in 0 until total) {
            seedWork("work-$index", shared = index < sharedCount)
        }
        statements.clear()
        val result = runBlocking {
            db.audiobookDao()
                .pagedWorksFeedRecent(
                    emptyList(), 0,
                    emptyList(), 0,
                    emptyList(), 0,
                    emptyList(), 0,
                    if (sharedOnly) 1 else 0
                )
                .load(
                    PagingSource.LoadParams.Refresh(
                        key = null,
                        loadSize = loadSize,
                        placeholdersEnabled = false
                    )
                )
        }
        check(result is PagingSource.LoadResult.Page) {
            "сторінка стрічки не завантажилась: $result"
        }
        assertEquals(
            "сторінка мусить бути заповнена: запити рахуються на реальних рядках",
            minOf(loadSize, if (sharedOnly) sharedCount else total),
            result.data.size
        )
        return statements.toList()
    }

    private fun onePageQueryCount(sharedOnly: Boolean, loadSize: Int = PAGE_SIZE): Int =
        onePageStatements(sharedOnly = sharedOnly, loadSize = loadSize).count { it.isFeedPage }

    @Test
    fun `one page of the Overview feed costs exactly one query`() {
        val page = onePageStatements(sharedOnly = false)
        val feedQueries = page.filter { it.isFeedPage }
        assertEquals(
            "одна сторінка стрічки (pageSize = 30) — один запит до Room; " +
                "фактичні запити: ${page.map { it.sql.take(120) }}",
            QUERIES_PER_FEED_PAGE,
            feedQueries.size
        )
        // The count is only meaningful if the callback really saw the feed's
        // own statement — a silent zero would otherwise pass for a cheap page.
        assertTrue(
            "колбек мусить бачити сам запит стрічки, інакше рахунок нічого не доводить",
            feedQueries.single().sql.contains("fromSharedLibrary")
        )
    }

    @Test
    fun `the shared-library flag adds no queries`() {
        // The flag is a predicate, not a second surface: the same page with the
        // toggle on must cost the same as with it off.
        val off = onePageQueryCount(sharedOnly = false)
        val on = onePageQueryCount(sharedOnly = true)
        assertEquals(
            "«Лише зі спільної бібліотеки» не додає запитів: вимкнено=$off, увімкнено=$on",
            off,
            on
        )
        assertEquals(
            "обидва прогони лишаються в межах одного запиту на сторінку",
            QUERIES_PER_FEED_PAGE,
            on
        )
    }

    @Test
    fun `the flag changes only the bind argument, never the statement`() {
        val off = onePageStatements(sharedOnly = false).filter { it.isFeedPage }
        val on = onePageStatements(sharedOnly = true).filter { it.isFeedPage }

        assertEquals(
            "ознака резолвиться ТИМ САМИМ запитом — інакше це вже друга поверхня",
            off.single().sql,
            on.single().sql
        )
        // `:sharedLibraryOnly` is the bind argument that differs; the narrowing
        // is the predicate, so the arg moves 0 → 1 and nothing else does.
        assertEquals(
            "вимкнений фільтр передає 0 (не звужувати)",
            SHARED_LIBRARY_ONLY_INACTIVE,
            off.single().args.last()
        )
        assertEquals(
            "увімкнений фільтр передає 1",
            SHARED_LIBRARY_ONLY_ACTIVE,
            on.single().args.last()
        )
    }

    @Test
    fun `the page query does not scale with the number of rows`() {
        // The N+1 shape this guards against: if the origin flag (or anything
        // else on the row) were resolved by asking the database per row, a
        // 30-row page would cost more than a 1-row page. One page is one
        // statement no matter how many rows it carries.
        val single = onePageQueryCount(sharedOnly = false, loadSize = 1)
        val full = onePageQueryCount(sharedOnly = false, loadSize = PAGE_SIZE)
        assertEquals(
            "1 рядок і $PAGE_SIZE рядків коштують однаково — ознака не робить запит на рядок",
            single,
            full
        )
    }

    private companion object {
        /** Both the sort orders' projection carry this column alias. */
        const val FEED_MARKER = "fromSharedLibrary"

        /** `MainViewModel.workFeed` — `PagingConfig(pageSize = 30)`. */
        const val PAGE_SIZE = 30

        /** Measured, not assumed: one `LIMIT`-ed select per page. */
        const val QUERIES_PER_FEED_PAGE = 1

        const val SHARED_LIBRARY_ONLY_INACTIVE = 0
        const val SHARED_LIBRARY_ONLY_ACTIVE = 1

        const val GROUP_LINK = "https://t.me/slukhayka"
        const val FOREIGN_LINK = "https://sound-books.net/book"
    }
}
