package com.slukhayka.audiobooks.data.catalog

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import com.slukhayka.audiobooks.data.recommend.recommendationCandidates
import com.slukhayka.audiobooks.data.recommend.recommendationWorkKey
import com.slukhayka.audiobooks.data.source.ScamSourcePurge
import com.slukhayka.audiobooks.data.source.SourceAccessPolicy
import com.slukhayka.audiobooks.data.source.SourceSelectionCoordinator
import com.slukhayka.audiobooks.ui.catalog.CatalogCardAction
import com.slukhayka.audiobooks.ui.catalog.CatalogCardActionCoordinator
import com.slukhayka.audiobooks.ui.catalog.CatalogCardActionGateway
import com.slukhayka.audiobooks.ui.catalog.CatalogCardActionState
import com.slukhayka.audiobooks.ui.catalog.CatalogCardFailure
import com.slukhayka.audiobooks.ui.catalog.CatalogCardTarget
import com.slukhayka.audiobooks.ui.catalog.catalogSessionCandidates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Feedback loop for the report behind the 2026-09-15 screenshots: the
 * «Рекомендовано для вас» rail offered «Дюна» by Френк Герберт, tapping it
 * answered «Не вдалося відкрити книгу. Спробуйте ще раз.», and a live search
 * for «дюна» found nothing at all.
 *
 * Root cause: the scam purge deletes 4read's `work_sources` claims but keeps
 * the `works` row as the merge anchor. Discovery surfaces read `works`
 * directly, so they published ghost Works that resolve zero Sources; the card
 * died as `EMPTY_SOURCES`, which renders the generic open error. Search never
 * lists them either, because the banned source is excluded from live search.
 *
 * The contract pinned here: a Work with no Source claim may stay in the
 * Mirror, but no discovery surface (recommendation pool, endless feed) may
 * publish it as an openable card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GhostWorkDiscoveryTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * The real phone state: at least one imported 4read book makes the purge
     * run; the mirror carries a 4read-only Work the listener never imported
     * (the «Дюна» recommendation) and one Work that keeps a real direct claim.
     */
    private suspend fun seedScamSourceWithGhostAndClaimedWorks() {
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = "b-imported",
                    title = "Передісторія",
                    author = "Джоан Роулінг",
                    narrator = "Mike Juice",
                    description = "",
                    coverDrawableRes = 0,
                    genre = "Фентезі",
                    sourceUrl = "https://4read.org/book.html",
                    totalDurationSeconds = 52L,
                    totalChapters = 1
                )
            )
        )
        dao.insertSources(
            listOf(
                SourceEntity(
                    id = "4read-imported",
                    bookId = "b-imported",
                    type = "4read",
                    url = "https://4read.org/book.html"
                )
            )
        )
        dao.upsertWork(
            WorkEntity(
                id = "w-dune",
                mergeKey = "дюна|френк герберт",
                title = "Дюна",
                author = "Френк Герберт",
                addedAt = 1L
            )
        )
        dao.upsertWorkSource(
            WorkSourceEntity(
                id = "ws-dune",
                workId = "w-dune",
                sourceId = "4read",
                sourceUrl = "https://4read.org/dune.html"
            )
        )
        dao.upsertWork(
            WorkEntity(
                id = "w-zakhar",
                mergeKey = "захар беркут|іван франко",
                title = "Захар Беркут",
                author = "Іван Франко",
                addedAt = 2L
            )
        )
        dao.upsertWorkSource(
            WorkSourceEntity(
                id = "ws-zakhar",
                workId = "w-zakhar",
                sourceId = "soundbooks",
                sourceUrl = "https://sound-books.net/zakhar-berkut"
            )
        )
    }

    private suspend fun feedRowIds(): List<String> {
        val result = dao.pagedWorksFeedRecent(
            emptyList(), 0,
            emptyList(), 0,
            emptyList(), 0,
            emptyList(), 0
        ).load(PagingSource.LoadParams.Refresh(key = null, loadSize = 50, placeholdersEnabled = false))
        assertTrue("expected a feed page, got $result", result is PagingSource.LoadResult.Page)
        return (result as PagingSource.LoadResult.Page<Int, WorkFeedRow>).data.map { it.workId }
    }

    /**
     * The user's exact symptom, at the recommendation-pool seam: every Work
     * the rail offers must still be resolvable to a Source claim.
     */
    @Test
    fun `the discovery pool publishes only works that still carry a source claim`() = runBlocking {
        seedScamSourceWithGhostAndClaimedWorks()

        assertTrue("the purge really removed the 4read claim", ScamSourcePurge(dao).purgeOnce() >= 1)
        assertTrue(dao.getWorkSourcesForWorkSync("w-dune").isEmpty())

        // The Work row survives as the merge anchor — the purge keeps it.
        val all = dao.observeWorks().first()
        assertTrue(all.any { recommendationWorkKey(it) == "дюна|френк герберт" })

        val pool = recommendationCandidates(dao.observeDiscoverableWorks().first()).map { it.id }
        assertTrue("ghost Work leaked into the discovery pool: $pool", "дюна|френк герберт" !in pool)
        assertTrue("a claimed Work must stay discoverable: $pool", "захар беркут|іван франко" in pool)
    }

    /** The endless feed is the same discovery surface — no ghost cards there. */
    @Test
    fun `the endless feed never pages a claim-less work`() = runBlocking {
        seedScamSourceWithGhostAndClaimedWorks()
        ScamSourcePurge(dao).purgeOnce()

        val rows = feedRowIds()
        assertTrue("ghost Work leaked into the endless feed: $rows", "w-dune" !in rows)
        assertTrue("a claimed Work must stay in the feed: $rows", "w-zakhar" in rows)
    }

    /**
     * The card-tap half of the symptom: resolved through the same policy the
     * production gateway uses (`work_sources`), the ghost's OPEN action dies
     * as `EMPTY_SOURCES`, which the card renders as
     * «Не вдалося відкрити книгу. Спробуйте ще раз.». Kept as a
     * characterization of the failure the discovery filter now prevents.
     */
    @Test
    fun `opening a ghost work card fails as EMPTY_SOURCES`() = runTest {
        // Room's own executor is not the test scheduler, so the DB reads are
        // resolved up front; the coordinator then runs wholly on the test
        // dispatcher and advanceUntilIdle is faithful.
        val (work, resolvedCandidates) = runBlocking {
            seedScamSourceWithGhostAndClaimedWorks()
            ScamSourcePurge(dao).purgeOnce()
            val dune = dao.observeWorks().first().first { recommendationWorkKey(it) == "дюна|френк герберт" }
            // Production shape: MainViewModel.catalogSourceCandidates reads the
            // Mirror's work_sources and maps them through catalogSessionCandidates.
            val candidates = dao.getWorkSourcesForWorkSync(dune.id).flatMap { source ->
                catalogSessionCandidates(
                    source = SourceEntity(
                        id = "x",
                        bookId = dune.id,
                        type = source.sourceId,
                        url = source.sourceUrl
                    ),
                    mode = SourceAccessPolicy.modeFor(source.sourceId),
                    hasFirstPartySession = false
                )
            }
            dune to candidates
        }
        assertTrue("the purge emptied the ghost's claims", resolvedCandidates.isEmpty())

        val gateway = object : CatalogCardActionGateway<String> {
            override suspend fun savedBook(target: CatalogCardTarget): String? = null
            override suspend fun sourceCandidates(target: CatalogCardTarget) = resolvedCandidates
            override suspend fun import(target: CatalogCardTarget, source: SourceEntity): String? = null
            override suspend fun open(book: String): Boolean = true
            override suspend fun play(book: String, source: SourceEntity?): Boolean = true
        }
        val coordinator = CatalogCardActionCoordinator(
            scope = this,
            gateway = gateway,
            sourceProbe = SourceSelectionCoordinator.SourceProbe { _, _ ->
                SourceSelectionCoordinator.ProbeResult.Success
            }
        )

        coordinator.start(
            target = CatalogCardTarget(
                workId = work.id,
                title = work.title,
                author = work.author,
                mergeKey = work.mergeKey
            ),
            action = CatalogCardAction.OPEN
        )
        advanceUntilIdle()

        val state = coordinator.state.value
        assertTrue("expected a Failed state, got $state", state is CatalogCardActionState.Failed)
        assertEquals(
            CatalogCardFailure.EMPTY_SOURCES,
            (state as CatalogCardActionState.Failed).reason
        )
        assertEquals(CatalogCardAction.OPEN, state.action)
    }
}
