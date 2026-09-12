package com.slukhayka.audiobooks.data.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.collective.CollectiveAttemptStatus
import com.slukhayka.audiobooks.data.collective.CollectiveBlockKind
import com.slukhayka.audiobooks.data.collective.CollectiveRefreshOutcome
import com.slukhayka.audiobooks.data.collective.classifyCollectiveFailure
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.source.GenrePage
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * #523 — the concrete one-page fetch: a non-empty page becomes a candidate
 * block with the source's own card order and provenance, while an empty
 * answer and a thrown transport failure stay distinct non-erasing outcomes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CollectiveBlockFetchTest {

    private lateinit var db: AudiobookDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private class FakeAdapter(
        override val sourceId: String,
        private val books: List<SourceBook>,
        private val error: Exception? = null
    ) : SourceAdapter {
        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            SourceBookDetail("", "", url = url, chapters = emptyList())
        override suspend fun fetchNew(limit: Int): List<SourceBook> {
            error?.let { throw it }
            return books
        }
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = emptyList()

        /** #527 — the listener genre door: one call = one page. */
        var genreCalls = 0
        override suspend fun fetchGenrePage(
            genrePath: String,
            cursor: String?,
            limit: Int
        ): GenrePage {
            genreCalls++
            return GenrePage(books, nextCursor = if (cursor == null) "$genrePath/page/2/" else null)
        }
    }

    private fun catalog(adapters: List<SourceAdapter>): SourceCatalog {
        val context: Context = ApplicationProvider.getApplicationContext()
        return SourceCatalog(
            db.audiobookDao(),
            adapters,
            LibraryImport(db.audiobookDao(), context, adapters)
        )
    }

    private fun book(title: String, url: String = "https://sound-books.net/$title") = SourceBook(
        title = title,
        author = "Автор",
        url = url,
        sourceId = "soundbooks"
    )

    @Test
    fun `a non-empty page becomes a candidate block in the source's order`() = runBlocking {
        val outcome = catalog(
            listOf(FakeAdapter("soundbooks", listOf(book("Перша"), book("Друга"))))
        ).collectiveBlockFetch("soundbooks", CollectiveBlockKind.NEW_ARRIVALS)

        assertTrue(outcome is CollectiveRefreshOutcome.Success)
        val block = (outcome as CollectiveRefreshOutcome.Success).block
        assertEquals("soundbooks", block.sourceId)
        assertEquals(CollectiveBlockKind.NEW_ARRIVALS, block.kind)
        assertEquals("Sound-Books", block.name)
        assertEquals("https://sound-books.net", block.provenanceUrl)
        assertEquals(listOf("Перша", "Друга"), block.cards.map { it.title })
        assertEquals("https://sound-books.net/Перша", block.cards.first().sourceUrl)
    }

    @Test
    fun `an empty page is an honest empty attempt`() = runBlocking {
        val outcome = catalog(listOf(FakeAdapter("soundbooks", emptyList())))
            .collectiveBlockFetch("soundbooks", CollectiveBlockKind.NEW_ARRIVALS)

        assertEquals(CollectiveRefreshOutcome.Empty, outcome)
    }

    @Test
    fun `a thrown transport failure is classified, never swallowed`() = runBlocking {
        val timedOut = catalog(listOf(FakeAdapter("soundbooks", emptyList(), SocketTimeoutException())))
            .collectiveBlockFetch("soundbooks", CollectiveBlockKind.NEW_ARRIVALS)
        assertEquals(
            CollectiveRefreshOutcome.Failure(CollectiveAttemptStatus.TIMEOUT),
            timedOut
        )

        val parseFailed = catalog(listOf(FakeAdapter("soundbooks", emptyList(), IllegalStateException("bad html"))))
            .collectiveBlockFetch("soundbooks", CollectiveBlockKind.NEW_ARRIVALS)
        assertEquals(
            CollectiveRefreshOutcome.Failure(CollectiveAttemptStatus.PARSE_FAILURE),
            parseFailed
        )
    }

    @Test
    fun `a source without an adapter is not found`() = runBlocking {
        val outcome = catalog(emptyList())
            .collectiveBlockFetch("soundbooks", CollectiveBlockKind.NEW_ARRIVALS)

        assertEquals(CollectiveRefreshOutcome.Failure(CollectiveAttemptStatus.NOT_FOUND), outcome)
    }

    @Test
    fun `recommendations and collections report an honest empty for now`() = runBlocking {
        val page = listOf(FakeAdapter("soundbooks", listOf(book("Перша"))))

        assertEquals(
            CollectiveRefreshOutcome.Empty,
            catalog(page).collectiveBlockFetch("soundbooks", CollectiveBlockKind.RECOMMENDATIONS)
        )
        assertEquals(
            CollectiveRefreshOutcome.Empty,
            catalog(page).collectiveBlockFetch("soundbooks", CollectiveBlockKind.COLLECTIONS)
        )
    }

    @Test
    fun `transport exceptions classify honestly`() {
        assertEquals(CollectiveAttemptStatus.TIMEOUT, classifyCollectiveFailure(SocketTimeoutException()))
        assertEquals(CollectiveAttemptStatus.TIMEOUT, classifyCollectiveFailure(IOException("network")))
        assertEquals(CollectiveAttemptStatus.PARSE_FAILURE, classifyCollectiveFailure(IllegalStateException("html")))
    }

    @Test
    fun `a listener genre page becomes a COLLECTIONS block with one request`() = runBlocking {
        val adapter = FakeAdapter("audiobookmp3", listOf(book("Клуб боягузів")))
        val fetch = catalog(listOf(adapter))
            .collectiveGenreBlockFetch("audiobookmp3", "/uk-genre-12-fantastyka")

        assertTrue(fetch.outcome is CollectiveRefreshOutcome.Success)
        val block = (fetch.outcome as CollectiveRefreshOutcome.Success).block
        assertEquals(CollectiveBlockKind.COLLECTIONS, block.kind)
        assertEquals("audiobookmp3", block.sourceId)
        assertEquals("https://audiobook-mp3.com/uk-genre-12-fantastyka", block.provenanceUrl)
        assertEquals(listOf("Клуб боягузів"), block.cards.map { it.title })
        assertEquals("one page for one action", 1, adapter.genreCalls)
        assertEquals("/uk-genre-12-fantastyka/page/2/", fetch.nextCursor)

        // The cursor is the NEXT action's call, not an implicit walk.
        val second = catalog(listOf(adapter))
            .collectiveGenreBlockFetch("audiobookmp3", "/uk-genre-12-fantastyka", fetch.nextCursor)
        assertTrue(second.outcome is CollectiveRefreshOutcome.Success)
        assertNull("the last page has no cursor", second.nextCursor)

        // A source without the genre door is an honest empty.
        assertEquals(
            CollectiveRefreshOutcome.Empty,
            catalog(listOf(FakeEmptyGenreAdapter("lihtar")))
                .collectiveGenreBlockFetch("lihtar", "/genre").outcome
        )
    }

    private class FakeEmptyGenreAdapter(override val sourceId: String) : SourceAdapter {
        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            SourceBookDetail("", "", url = url, chapters = emptyList())
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
    }
}
