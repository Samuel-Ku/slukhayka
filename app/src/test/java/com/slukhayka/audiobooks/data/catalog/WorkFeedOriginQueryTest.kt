package com.slukhayka.audiobooks.data.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
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

/**
 * #831 AC2/AC3 — the shared-library origin reaches the feed WITHOUT a per-row
 * query.
 *
 * The ticket's AC5 is «одна поверхня — один запит; без N+1», and the badge is
 * rendered per row, so the flag is resolved inside the page query
 * (`AS fromSharedLibrary`) rather than by asking the database per Work. This
 * test proves the FACT the classifier classifies, on the real DAO: a Work whose
 * Source carries the registered community group's link comes back flagged, a
 * Work whose Source does not stays unflagged.
 *
 * It also pins the canonical comparison. `CommunityOriginClassifier.isRegisteredGroupLink`
 * lower-cases and strips a trailing slash, so the SQL must accept the same
 * shapes — otherwise a link written as `https://t.me/slukhayka/` (which the
 * classifier calls a group link) would render no chip, and the two would
 * silently disagree about what "the group link" means.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class WorkFeedOriginQueryTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun seedWork(id: String, url: String) = runBlocking {
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
                id = "$id|community|${url.hashCode()}",
                workId = id,
                sourceId = "community",
                sourceUrl = url,
                addedAt = 1_000L
            )
        )
    }

    /** Titles returned when the «Лише зі спільної бібліотеки» narrowing is on. */
    private fun titlesWithSharedOnly(only: Boolean): List<String> = runBlocking {
        db.audiobookDao()
            .pagedWorksFeedRecent(
                emptyList(), 0, emptyList(), 0, emptyList(), 0, emptyList(), 0,
                if (only) 1 else 0
            )
            .load(
                androidx.paging.PagingSource.LoadParams.Refresh(
                    key = null,
                    loadSize = 50,
                    placeholdersEnabled = false
                )
            )
            .let { result ->
                check(result is androidx.paging.PagingSource.LoadResult.Page)
                result.data.map { it.title }
            }
    }

    private fun flagFor(workId: String): Boolean = runBlocking {
        db.audiobookDao()
            .pagedWorksFeedRecent(emptyList(), 0, emptyList(), 0, emptyList(), 0, emptyList(), 0)
            .load(
                androidx.paging.PagingSource.LoadParams.Refresh(
                    key = null,
                    loadSize = 50,
                    placeholdersEnabled = false
                )
            )
            .let { result ->
                check(result is androidx.paging.PagingSource.LoadResult.Page)
                result.data.first { it.workId == workId }.fromSharedLibrary
            }
    }

    @Test
    fun `a Work whose source is the registered group is flagged`() {
        seedWork("group-work", "https://t.me/slukhayka")
        assertTrue(
            "джерело веде на зареєстровану групу — ознака мусить бути",
            flagFor("group-work")
        )
    }

    @Test
    fun `a Work from an unrelated source is NOT flagged`() {
        seedWork("other-work", "https://sound-books.net/book")
        assertFalse(
            "ознаку не можна вигадувати для стороннього джерела (ADR-0035)",
            flagFor("other-work")
        )
    }

    @Test
    fun `the comparison matches the classifier's canonical shape`() {
        // The classifier accepts these; the query must agree, or the two
        // would disagree about the same link.
        seedWork("upper", "HTTPS://T.ME/SLUKHAYKA")
        seedWork("trailing", "https://t.me/slukhayka/")

        assertTrue("великі літери — та сама група", flagFor("upper"))
        assertTrue("кінцевий слеш — та сама група", flagFor("trailing"))
    }

    @Test
    fun `both feed orders resolve the same flag`() {
        // The flag lives in BOTH queries; a Work must not change provenance
        // because the listener switched the sort.
        seedWork("sorted", "https://t.me/slukhayka")
        val byTitle = runBlocking {
            db.audiobookDao()
                .pagedWorksFeedByTitle(emptyList(), 0, emptyList(), 0, emptyList(), 0, emptyList(), 0)
                .load(
                    androidx.paging.PagingSource.LoadParams.Refresh(
                        key = null,
                        loadSize = 50,
                        placeholdersEnabled = false
                    )
                )
                .let { result ->
                    check(result is androidx.paging.PagingSource.LoadResult.Page)
                    result.data.first { it.workId == "sorted" }.fromSharedLibrary
                }
        }
        assertTrue("сортування за назвою не міняє походження", byTitle)
        assertEquals(flagFor("sorted"), byTitle)
    }

    /**
     * #831 AC3 — «Лише зі спільної бібліотеки» as a NARROWING of the same feed.
     *
     * The ticket is explicit that no second rail is created and the «Слухати»
     * ninth block is untouched (ADR-0015), so the filter is the whole visible
     * surface: it must hide foreign entries and keep the group's own.
     */
    @Test
    fun `the origin filter keeps only shared-library works`() {
        seedWork("from-group", "https://t.me/slukhayka")
        seedWork("foreign", "https://sound-books.net/book")

        val unfiltered = titlesWithSharedOnly(only = false)
        assertTrue("без фільтра видно обидві книги", unfiltered.contains("Книга from-group"))
        assertTrue("без фільтра видно обидві книги", unfiltered.contains("Книга foreign"))

        val filtered = titlesWithSharedOnly(only = true)
        assertTrue("книга з групи лишається", filtered.contains("Книга from-group"))
        assertFalse("стороння книга зникає", filtered.contains("Книга foreign"))
    }

    /**
     * `false` means «не звужувати», not «показати лише чуже» — the same
     * contract as the language dimension, where an empty selection is
     * inactive. Getting this backwards would hide the community's own books
     * from everyone who never opened the sheet.
     */
    @Test
    fun `an inactive origin filter hides nothing`() {
        seedWork("only-group", "https://t.me/slukhayka")
        assertEquals(
            "вимкнений фільтр не має ховати нічого",
            titlesWithSharedOnly(only = false),
            titlesWithSharedOnly(only = false)
        )
        assertTrue(titlesWithSharedOnly(only = false).contains("Книга only-group"))
    }
}
