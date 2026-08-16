package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.entries.LibraryEntries
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

/**
 * spec-24 T9 (#170) — the page-open cover heal dispatches to the book's OWN
 * source adapter. The refresh was 4read-hard-coded, so a sound-books page was
 * parsed by the wrong adapter and a null-cover sound-books row never healed.
 * These tests pin the fix: the sound-books adapter serves the page, the
 * stored cover back-fills, the 4read adapter is never invoked, and a page
 * without a cover never clears or fabricates one (prior art: the
 * SourceProfileRepositoryTest fake-adapter construction).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryEntriesCoverRefreshTest {

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

    /** Records which urls each adapter's page fetch was asked for. */
    private open class FakeAdapter(
        override val sourceId: String,
        private val detail: SourceBookDetail
    ) : SourceAdapter {
        val fetchedUrls = mutableListOf<String>()
        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): SourceBookDetail {
            fetchedUrls += url
            return detail
        }
    }

    private val soundBooksUrl = "https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html"

    private suspend fun seedBook(coverImageUrl: String?): String {
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = "sb-book",
                    title = "Темна матерія",
                    author = "Блейк Крауч",
                    narrator = "",
                    description = "",
                    coverDrawableRes = 0,
                    coverImageUrl = coverImageUrl,
                    genre = "",
                    sourceUrl = soundBooksUrl,
                    isDownloaded = false
                )
            )
        )
        return "sb-book"
    }

    private fun detail(cover: String?) = SourceBookDetail(
        title = "Темна матерія",
        author = "Блейк Крауч",
        url = soundBooksUrl,
        chapters = emptyList(),
        coverImageUrl = cover
    )

    @Test
    fun `a sound-books book with a null cover back-fills it on page open`() = runBlocking {
        seedBook(coverImageUrl = null)
        val soundBooks = FakeAdapter(
            "soundbooks",
            detail("https://sound-books.net/uploads/posts/2026-07/bleik-krauch-temna-materiia.webp")
        )
        // The 4read adapter is present but must NOT be invoked for this url.
        val fourRead = FakeAdapter(
            "4read",
            SourceBookDetail(title = "", author = "", url = "https://4read.org/x.html", chapters = emptyList())
        )

        LibraryEntries(dao, listOf(soundBooks, fourRead)).refreshBookCoverAndDetails("sb-book")

        assertEquals(
            "https://sound-books.net/uploads/posts/2026-07/bleik-krauch-temna-materiia.webp",
            dao.getAudiobookById("sb-book")!!.coverImageUrl
        )
        // The refresh dispatched to the sound-books adapter, never the 4read one.
        assertEquals(listOf(soundBooksUrl), soundBooks.fetchedUrls)
        assertTrue(fourRead.fetchedUrls.isEmpty())
    }

    @Test
    fun `a page without a cover never clears the stored cover`() = runBlocking {
        seedBook(coverImageUrl = "https://sound-books.net/uploads/posts/2026-07/old-cover.webp")
        val soundBooks = FakeAdapter("soundbooks", detail(cover = null))

        LibraryEntries(dao, listOf(soundBooks)).refreshBookCoverAndDetails("sb-book")

        assertEquals(
            "https://sound-books.net/uploads/posts/2026-07/old-cover.webp",
            dao.getAudiobookById("sb-book")!!.coverImageUrl
        )
    }

    @Test
    fun `a coverless page leaves a null cover null - never fabricated`() = runBlocking {
        seedBook(coverImageUrl = null)
        val soundBooks = FakeAdapter("soundbooks", detail(cover = null))

        LibraryEntries(dao, listOf(soundBooks)).refreshBookCoverAndDetails("sb-book")

        assertNull(dao.getAudiobookById("sb-book")!!.coverImageUrl)
    }
}
