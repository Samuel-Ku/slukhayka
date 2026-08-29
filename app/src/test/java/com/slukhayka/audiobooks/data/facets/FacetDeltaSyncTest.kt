package com.slukhayka.audiobooks.data.facets

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.metadata.FacetAssertion
import com.slukhayka.audiobooks.data.metadata.FacetAvailability
import com.slukhayka.audiobooks.data.metadata.FacetCompleteness
import com.slukhayka.audiobooks.data.metadata.FacetCursor
import com.slukhayka.audiobooks.data.metadata.FacetDurationBucket
import com.slukhayka.audiobooks.data.metadata.FacetGenre
import com.slukhayka.audiobooks.data.metadata.FacetPerson
import com.slukhayka.audiobooks.data.metadata.FacetSeriesMembership
import com.slukhayka.audiobooks.testing.FakeSharedBookMetaStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FacetDeltaSyncTest {
    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var catalog: SourceCatalog
    private lateinit var shared: FakeSharedBookMetaStore
    private lateinit var cursors: InMemoryFacetSyncCursorStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        catalog = SourceCatalog(
            db.audiobookDao(),
            emptyList(),
            LibraryImport(db.audiobookDao(), context, emptyList())
        )
        shared = FakeSharedBookMetaStore()
        cursors = InMemoryFacetSyncCursorStore()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `remote genre delta lands through Room writer and advances cursor after commit`() = runBlocking {
        catalog.writeWorkEdition("4read", "Дюна", "Френк Герберт", "", "https://4read.org/dune")
        val workId = db.audiobookDao().observeWorks().first().single().id
        shared.putFacet(
            FacetAssertion.Work(
                workId = workId,
                sourceId = "community",
                genres = listOf(FacetGenre("science-fiction", "Наукова фантастика")),
                observedAt = 10,
                updatedAt = 11
            )
        )
        val sync = FacetDeltaSync(shared, catalog.facetWriter, cursors) { 12 }

        val result = sync.syncPage(pageSize = 10)

        assertEquals(FacetDeltaSync.PageResult.Applied(1), result)
        assertEquals(shared.getFacetPage(null, 10).nextCursor, cursors.load())
        assertEquals(
            listOf("Дюна"),
            collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(setOf("science-fiction"))))
                .map { it.title }
        )
    }

    @Test
    fun `catalogue session consumes bounded ordered pages after the committed cursor`() = runBlocking {
        catalog.writeWorkEdition("4read", "Дюна", "Френк Герберт", "", "https://4read.org/dune")
        catalog.writeWorkEdition("4read", "Відьмак", "Анджей Сапковський", "", "https://4read.org/witcher")
        val works = db.audiobookDao().observeWorks().first().associateBy { it.title }
        shared.putFacet(
            FacetAssertion.Work(
                workId = works.getValue("Дюна").id,
                sourceId = "community",
                genres = listOf(FacetGenre("science-fiction", "Фантастика")),
                observedAt = 10,
                updatedAt = 10
            )
        )
        shared.putFacet(
            FacetAssertion.Work(
                workId = works.getValue("Відьмак").id,
                sourceId = "community",
                genres = listOf(FacetGenre("fantasy", "Фентезі")),
                observedAt = 11,
                updatedAt = 11
            )
        )
        val sync = FacetDeltaSync(shared, catalog.facetWriter, cursors) { 12 }

        val result = sync.syncAvailablePages(pageSize = 1, maxPages = 5)

        assertEquals(FacetDeltaSync.ChainResult(pagesApplied = 2, assertionsApplied = 2), result)
        assertEquals(shared.getFacetPage(null, 10).nextCursor, cursors.load())
        assertEquals(
            setOf("Дюна", "Відьмак"),
            collectAll(
                catalog.pagedWorkFeedRecent(
                    WorkFacetFilter(setOf("science-fiction", "fantasy"))
                )
            ).map { it.title }.toSet()
        )
    }

    @Test
    fun `Edition facts land while expired availability remains absent`() = runBlocking {
        shared.putFacet(
            FacetAssertion.Edition(
                editionId = "edition-1",
                workId = "work-1",
                sourceId = "community",
                narrator = FacetPerson("narrator-1", "Оповідач"),
                language = "uk",
                durationRef = "edition-1",
                durationBucket = FacetDurationBucket.FIVE_TO_TEN_HOURS,
                chapterCount = 18,
                completeness = FacetCompleteness.ABRIDGED,
                availability = FacetAvailability(
                    available = true,
                    observedAt = 1_000,
                    ttlSeconds = 5
                ),
                observedAt = 1_000,
                updatedAt = 2_000
            )
        )
        val sync = FacetDeltaSync(shared, catalog.facetWriter, cursors) { 6_000 }

        assertEquals(FacetDeltaSync.PageResult.Applied(1), sync.syncPage())

        db.openHelper.writableDatabase.query(
            "SELECT workId, narratorId, language, durationBucketId, chapterCount, isAbridged, " +
                "availabilityAvailable, availabilityObservedAtMillis, availabilityTtlSeconds " +
                "FROM edition_facets WHERE editionId='edition-1'"
        ).use { row ->
            assertTrue(row.moveToFirst())
            assertEquals("work-1", row.getString(0))
            assertEquals("narrator-1", row.getString(1))
            assertEquals("uk", row.getString(2))
            assertEquals("5h_to_10h", row.getString(3))
            assertEquals(18, row.getInt(4))
            assertEquals(1, row.getInt(5))
            assertTrue(row.isNull(6))
            assertTrue(row.isNull(7))
            assertTrue(row.isNull(8))
        }
    }

    @Test
    fun `committed cursor survives a new local store instance`() {
        context.getSharedPreferences(
            SharedPreferencesFacetSyncCursorStore.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        val expected = FacetCursor(updatedAt = 42, documentId = "work~id~source")

        SharedPreferencesFacetSyncCursorStore(context).save(expected)

        assertEquals(expected, SharedPreferencesFacetSyncCursorStore(context).load())
    }

    @Test
    fun `Work author aliases and Series Membership land through the prepared writer`() = runBlocking {
        shared.putFacet(
            FacetAssertion.Work(
                workId = "work-author",
                sourceId = "community",
                author = FacetPerson(
                    id = "author-frank-herbert",
                    name = "Френк Герберт",
                    aliases = listOf("Frank Herbert")
                ),
                seriesMemberships = listOf(FacetSeriesMembership("series-dune", position = 1)),
                observedAt = 10,
                updatedAt = 11
            )
        )

        assertEquals(
            FacetDeltaSync.PageResult.Applied(1),
            FacetDeltaSync(shared, catalog.facetWriter, cursors) { 12 }.syncPage()
        )

        db.openHelper.writableDatabase.query(
            "SELECT canonicalAuthorId FROM work_facets WHERE workId='work-author'"
        ).use { row ->
            assertTrue(row.moveToFirst())
            assertEquals("author-frank-herbert", row.getString(0))
        }
        db.openHelper.writableDatabase.query(
            "SELECT displayName FROM author_facets WHERE id='author-frank-herbert'"
        ).use { row ->
            assertTrue(row.moveToFirst())
            assertEquals("Френк Герберт", row.getString(0))
        }
        db.openHelper.writableDatabase.query(
            "SELECT rawAlias, sourceId FROM author_aliases WHERE authorId='author-frank-herbert'"
        ).use { row ->
            assertTrue(row.moveToFirst())
            assertEquals("Frank Herbert", row.getString(0))
            assertEquals("community", row.getString(1))
        }
        db.openHelper.writableDatabase.query(
            "SELECT seriesId FROM work_facet_series WHERE workId='work-author'"
        ).use { row ->
            assertTrue(row.moveToFirst())
            assertEquals("series-dune", row.getString(0))
        }
    }

    @Test
    fun `malformed local projection is a miss without discarding valid siblings`() = runBlocking {
        catalog.writeWorkEdition("4read", "Дюна", "Френк Герберт", "", "https://4read.org/dune")
        val validWorkId = db.audiobookDao().observeWorks().first().single().id
        shared.putFacet(
            FacetAssertion.Work(
                workId = "w".repeat(241),
                sourceId = "community",
                genres = listOf(FacetGenre("fantasy", "Фентезі")),
                observedAt = 9,
                updatedAt = 9
            )
        )
        shared.putFacet(
            FacetAssertion.Work(
                workId = validWorkId,
                sourceId = "community",
                genres = listOf(FacetGenre("science-fiction", "Фантастика")),
                observedAt = 10,
                updatedAt = 10
            )
        )

        val result = FacetDeltaSync(shared, catalog.facetWriter, cursors) { 11 }.syncPage()

        assertEquals(FacetDeltaSync.PageResult.Applied(1), result)
        assertEquals(shared.getFacetPage(null, 10).nextCursor, cursors.load())
        assertEquals(
            listOf("Дюна"),
            collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(setOf("science-fiction"))))
                .map { it.title }
        )
    }

    @Test
    fun `remote valid oversized optional author facts do not discard a sibling genre`() = runBlocking {
        catalog.writeWorkEdition("4read", "Дюна", "Френк Герберт", "", "https://4read.org/dune")
        val workId = db.audiobookDao().observeWorks().first().single().id
        shared.putFacet(
            FacetAssertion.Work(
                workId = workId,
                sourceId = "community",
                author = FacetPerson(
                    id = "a".repeat(81),
                    name = "Я".repeat(121),
                    aliases = listOf("Alias")
                ),
                genres = listOf(FacetGenre("science-fiction", "Наукова фантастика")),
                seriesMemberships = listOf(FacetSeriesMembership("s".repeat(81), position = 1)),
                observedAt = 10,
                updatedAt = 11
            )
        )

        val result = FacetDeltaSync(shared, catalog.facetWriter, cursors) { 12 }.syncPage()

        assertEquals(FacetDeltaSync.PageResult.Applied(1), result)
        assertEquals(1, count("work_genres", "workId='$workId' AND genreId='science-fiction'"))
        assertEquals(0, count("author_facets", "id='${"a".repeat(81)}'"))
        assertEquals(0, count("work_facet_series", "workId='$workId'"))
        assertEquals(shared.getFacetPage(null, 10).nextCursor, cursors.load())
    }

    @Test
    fun `overlapping catalogue chains apply one remote page only once`() = runBlocking {
        shared.putFacet(
            FacetAssertion.Work(
                workId = "single-flight-work",
                sourceId = "community",
                genres = listOf(FacetGenre("fantasy", "Фентезі")),
                observedAt = 10,
                updatedAt = 11
            )
        )
        val applyCalls = AtomicInteger(0)
        val recordingWriter = object : LocalFacetWriter {
            override suspend fun apply(deltas: List<LocalFacetDelta>) {
                applyCalls.incrementAndGet()
                delay(100)
                catalog.facetWriter.apply(deltas)
            }
        }
        val sync = FacetDeltaSync(shared, recordingWriter, cursors) { 12 }

        coroutineScope {
            awaitAll(
                async { sync.syncAvailablePages(pageSize = 10, maxPages = 1) },
                async { sync.syncAvailablePages(pageSize = 10, maxPages = 1) }
            )
        }

        assertEquals(1, applyCalls.get())
        assertEquals(shared.getFacetPage(null, 10).nextCursor, cursors.load())
        assertEquals(1, count("work_genres", "workId='single-flight-work'"))
    }

    @Test
    fun `Source Catalog session owns one bounded shared facet chain`() = runBlocking {
        catalog.writeWorkEdition("4read", "Дюна", "Френк Герберт", "", "https://4read.org/dune")
        val workId = db.audiobookDao().observeWorks().first().single().id
        shared.putFacet(
            FacetAssertion.Work(
                workId = workId,
                sourceId = "community",
                genres = listOf(FacetGenre("science-fiction", "Фантастика")),
                observedAt = 10,
                updatedAt = 10
            )
        )
        val sessionCatalog = SourceCatalog(
            db.audiobookDao(),
            emptyList(),
            LibraryImport(db.audiobookDao(), context, emptyList()),
            sharedFacetStore = shared,
            facetSyncCursorStore = cursors,
            facetSyncNowMillis = { 11 }
        )

        assertEquals(
            FacetDeltaSync.ChainResult(1, 1),
            sessionCatalog.syncSharedFacets(pageSize = 10, maxPages = 2)
        )
        assertEquals(
            listOf("Дюна"),
            collectAll(sessionCatalog.pagedWorkFeedRecent(WorkFacetFilter(setOf("science-fiction"))))
                .map { it.title }
        )
    }

    @Test
    fun `failed Room page rolls back keeps cursor and retries idempotently`() = runBlocking {
        catalog.facetWriter.apply(
            listOf(
                LocalFacetDelta(
                    WorkFacetDelta(
                        workId = "stable-work",
                        genres = listOf(GenreFacetAssertion("Детектив", "local", 1))
                    )
                )
            )
        )
        shared.putFacet(
            FacetAssertion.Work(
                workId = "remote-one",
                sourceId = "community",
                genres = listOf(FacetGenre("science-fiction", "Фантастика")),
                observedAt = 10,
                updatedAt = 10
            )
        )
        shared.putFacet(
            FacetAssertion.Work(
                workId = "remote-two",
                sourceId = "community",
                genres = listOf(FacetGenre("trigger-failure", "Жанр помилки")),
                observedAt = 11,
                updatedAt = 11
            )
        )
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_remote_page BEFORE INSERT ON genre_assertions " +
                "WHEN NEW.genreId='trigger-failure' BEGIN SELECT RAISE(ABORT, 'forced rollback'); END"
        )
        val sync = FacetDeltaSync(shared, catalog.facetWriter, cursors) { 12 }

        assertEquals(FacetDeltaSync.PageResult.Failed, sync.syncPage())
        assertEquals(null, cursors.load())
        assertEquals(0, count("work_genres", "workId='remote-one'"))
        assertEquals(1, count("work_genres", "workId='stable-work'"))

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_remote_page")
        assertEquals(FacetDeltaSync.PageResult.Applied(2), sync.syncPage())
        assertEquals(shared.getFacetPage(null, 10).nextCursor, cursors.load())
        assertEquals(FacetDeltaSync.PageResult.NoChanges, sync.syncPage())
        assertEquals(1, count("work_genres", "workId='remote-one'"))
        assertEquals(1, count("work_genres", "workId='remote-two'"))
    }

    private fun count(table: String, where: String): Int =
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table WHERE $where").use { row ->
            row.moveToFirst()
            row.getInt(0)
        }

    private suspend fun collectAll(source: PagingSource<Int, WorkFeedRow>): List<WorkFeedRow> {
        val result = source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 25, placeholdersEnabled = false)
        )
        assertTrue(result is PagingSource.LoadResult.Page)
        return (result as PagingSource.LoadResult.Page).data
    }
}
