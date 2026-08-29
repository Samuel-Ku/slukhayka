package com.slukhayka.audiobooks.data.imports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #428 — 4read recovery must update only the exact 4read [Source] of the same
 * [Work] and [Edition]. A page of another book, another narration, reordered
 * chapters, or a second Source of the same type must not mutate tracks,
 * progress, bookmarks or download state. Re-running recovery on the same
 * Edition is idempotent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FourReadRecoveryIdentityTest {

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

    private fun fakeAdapter(capturedHtml: String, detail: SourceBookDetail?): SourceAdapter =
        object : SourceAdapter {
            override val sourceId: String = "4read"
            override suspend fun search(query: String): List<SourceBook> = emptyList()
            override suspend fun fetchBookPage(url: String): SourceBookDetail =
                throw IllegalStateException("not used in recovery")
            override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
            override suspend fun parseCapturedPage(html: String, url: String): SourceBookDetail? {
                return if (html == capturedHtml) detail else null
            }
        }

    private fun detail(
        title: String,
        author: String,
        narrator: String,
        url: String,
        chapters: List<Pair<String, String>>
    ) = SourceBookDetail(
        title = title,
        author = author,
        narrator = narrator,
        url = url,
        coverImageUrl = "https://4read.org/uploads/$title.jpg",
        chapters = chapters.map { (t, u) -> SourceChapter(t, u) }
    )

    private suspend fun seedBook(detail: SourceBookDetail): String {
        val adapter = fakeAdapter("seed", detail)
        val imports = LibraryImport(dao, context, listOf(adapter))
        // Use captured-page door to create the book exactly as recovery will read it.
        val book = imports.importWebSourcePage("4read", detail.url, "seed")
            ?: error("seed import failed for ${detail.title}")
        return book.id
    }

    @Test
    fun `exact match updates tracks and is idempotent`() = runBlocking {
        val originalDetail = detail(
            title = "Кобзар", author = "Тарас Шевченко", narrator = "Іван Петров",
            url = "https://4read.org/kobzar.html",
            chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3", "Глава 2" to "https://s1.reasd.org/kobzar/old2.mp3")
        )
        val bookId = seedBook(originalDetail)

        val refreshedDetail = detail(
            title = "Кобзар", author = "Тарас Шевченко", narrator = "Іван Петров",
            url = "https://4read.org/kobzar.html",
            chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3", "Глава 2" to "https://s1.reasd.org/kobzar/new2.mp3")
        )
        val imports = LibraryImport(dao, context, listOf(fakeAdapter("captured", refreshedDetail)))

        val first = imports.recoverWebSourcePage(bookId, "4read", "https://4read.org/kobzar.html", "captured")
        assertNotNull(first)
        val tracksAfterFirst = dao.getTracksForBookSync(bookId).sortedBy { it.trackIndex }
        assertEquals("https://s1.reasd.org/kobzar/new1.mp3", tracksAfterFirst[0].url)
        assertEquals("https://s1.reasd.org/kobzar/new2.mp3", tracksAfterFirst[1].url)
        val bookCountAfterFirst = dao.getAllAudiobooksOnce().size

        // Second recovery with same detail is idempotent — no duplicate Work/Edition/Source.
        val second = imports.recoverWebSourcePage(bookId, "4read", "https://4read.org/kobzar.html", "captured")
        assertNotNull(second)
        assertEquals(bookId, second!!.id)
        assertEquals(bookCountAfterFirst, dao.getAllAudiobooksOnce().size)
        val tracksAfterSecond = dao.getTracksForBookSync(bookId).sortedBy { it.trackIndex }
        assertEquals("https://s1.reasd.org/kobzar/new1.mp3", tracksAfterSecond[0].url)
        assertEquals(1, dao.getSourcesForBookSync(bookId).count { it.type == "4read" && it.url == "https://4read.org/kobzar.html" })
    }

    @Test
    fun `other book is rejected and leaves room untouched`() = runBlocking {
        val originalDetail = detail(
            title = "Кобзар", author = "Тарас Шевченко", narrator = "Іван Петров",
            url = "https://4read.org/kobzar.html",
            chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3", "Глава 2" to "https://s1.reasd.org/kobzar/old2.mp3")
        )
        val bookId = seedBook(originalDetail)
        val tracksBefore = dao.getTracksForBookSync(bookId).map { it.url }
        val bookCountBefore = dao.getAllAudiobooksOnce().size

        val otherBookDetail = detail(
            title = "Лісова пісня", author = "Леся Українка", narrator = "Іван Петров",
            url = "https://4read.org/kobzar.html",
            chapters = listOf("Глава 1" to "https://s1.reasd.org/forest/new1.mp3", "Глава 2" to "https://s1.reasd.org/forest/new2.mp3")
        )
        val imports = LibraryImport(dao, context, listOf(fakeAdapter("other", otherBookDetail)))
        val result = imports.recoverWebSourcePage(bookId, "4read", "https://4read.org/kobzar.html", "other")

        assertNull(result)
        assertEquals(tracksBefore, dao.getTracksForBookSync(bookId).map { it.url })
        assertEquals(bookCountBefore, dao.getAllAudiobooksOnce().size)
    }

    @Test
    fun `other narration is rejected`() = runBlocking {
        val originalDetail = detail(
            title = "Кобзар", author = "Тарас Шевченко", narrator = "Іван Петров",
            url = "https://4read.org/kobzar.html",
            chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3", "Глава 2" to "https://s1.reasd.org/kobzar/old2.mp3")
        )
        val bookId = seedBook(originalDetail)

        val otherNarratorDetail = detail(
            title = "Кобзар", author = "Тарас Шевченко", narrator = "Олена Сидорова",
            url = "https://4read.org/kobzar.html",
            chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3", "Глава 2" to "https://s1.reasd.org/kobzar/new2.mp3")
        )
        val imports = LibraryImport(dao, context, listOf(fakeAdapter("other-narr", otherNarratorDetail)))
        val result = imports.recoverWebSourcePage(bookId, "4read", "https://4read.org/kobzar.html", "other-narr")

        assertNull(result)
        val tracks = dao.getTracksForBookSync(bookId)
        assertEquals("https://s1.reasd.org/kobzar/old1.mp3", tracks.sortedBy { it.trackIndex }[0].url)
    }

    @Test
    fun `reordered chapters are rejected`() = runBlocking {
        val originalDetail = detail(
            title = "Кобзар", author = "Тарас Шевченко", narrator = "Іван Петров",
            url = "https://4read.org/kobzar.html",
            chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3", "Глава 2" to "https://s1.reasd.org/kobzar/old2.mp3")
        )
        val bookId = seedBook(originalDetail)

        val reorderedDetail = detail(
            title = "Кобзар", author = "Тарас Шевченко", narrator = "Іван Петров",
            url = "https://4read.org/kobzar.html",
            // Titles swapped — same count but wrong order.
            chapters = listOf("Глава 2" to "https://s1.reasd.org/kobzar/new2.mp3", "Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3")
        )
        val imports = LibraryImport(dao, context, listOf(fakeAdapter("reordered", reorderedDetail)))
        val result = imports.recoverWebSourcePage(bookId, "4read", "https://4read.org/kobzar.html", "reordered")

        assertNull(result)
        val tracks = dao.getTracksForBookSync(bookId).sortedBy { it.trackIndex }
        assertEquals("https://s1.reasd.org/kobzar/old1.mp3", tracks[0].url)
        assertEquals("https://s1.reasd.org/kobzar/old2.mp3", tracks[1].url)
    }

    @Test
    fun `chapter count mismatch is rejected`() = runBlocking {
        val originalDetail = detail(
            title = "Кобзар", author = "Тарас Шевченко", narrator = "Іван Петров",
            url = "https://4read.org/kobzar.html",
            chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3", "Глава 2" to "https://s1.reasd.org/kobzar/old2.mp3")
        )
        val bookId = seedBook(originalDetail)

        val shortDetail = detail(
            title = "Кобзар", author = "Тарас Шевченко", narrator = "Іван Петров",
            url = "https://4read.org/kobzar.html",
            chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3")
        )
        val imports = LibraryImport(dao, context, listOf(fakeAdapter("short", shortDetail)))
        val result = imports.recoverWebSourcePage(bookId, "4read", "https://4read.org/kobzar.html", "short")

        assertNull(result)
        assertEquals(2, dao.getTracksForBookSync(bookId).size)
    }

    @Test
    fun `multiple sources of same type - exact url required no fallback`() = runBlocking {
        val firstDetail = detail(
            title = "Кобзар", author = "Тарас Шевченко", narrator = "Іван Петров",
            url = "https://4read.org/kobzar.html",
            chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3", "Глава 2" to "https://s1.reasd.org/kobzar/old2.mp3")
        )
        val bookId = seedBook(firstDetail)

        // Attach a second 4read Source of the same type but different URL (simulate legacy duplicate).
        val secondUrl = "https://4read.org/kobzar-alt.html"
        val secondDetail = detail(
            title = "Кобзар", author = "Тарас Шевченко", narrator = "Іван Петров",
            url = secondUrl,
            chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/alt1.mp3", "Глава 2" to "https://s1.reasd.org/kobzar/alt2.mp3")
        )
        // Directly insert a second source row for same book/edition (mimic multiple sources scenario).
        val existingSource = dao.getSourcesForBookSync(bookId).first()
        val secondSource = existingSource.copy(
            id = "4read-${existingSource.editionId}-alt",
            url = secondUrl
        )
        dao.insertSources(listOf(secondSource))
        // Also need tracks for second source to keep DB consistent, but recovery should target first source only when URL matches first.
        val secondTracks = dao.getTracksForSourceSync(existingSource.id).map {
            it.copy(id = it.id + "-alt", sourceId = secondSource.id)
        }
        dao.insertTracks(secondTracks)

        assertEquals(2, dao.getSourcesForBookSync(bookId).filter { it.type == "4read" }.size)

        val refreshedDetail = detail(
            title = "Кобзар", author = "Тарас Шевченко", narrator = "Іван Петров",
            url = "https://4read.org/kobzar.html",
            chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3", "Глава 2" to "https://s1.reasd.org/kobzar/new2.mp3")
        )
        val importsExact = LibraryImport(dao, context, listOf(fakeAdapter("exact", refreshedDetail)))
        val exactResult = importsExact.recoverWebSourcePage(bookId, "4read", "https://4read.org/kobzar.html", "exact")
        assertNotNull(exactResult)

        // Attempt recovery with a URL that does not match any source — must not fallback to first Source.
        val bogusDetail = detail(
            title = "Кобзар", author = "Тарас Шевченко", narrator = "Іван Петров",
            url = "https://4read.org/bogus.html",
            chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/bogus1.mp3", "Глава 2" to "https://s1.reasd.org/kobzar/bogus2.mp3")
        )
        val importsBogus = LibraryImport(dao, context, listOf(fakeAdapter("bogus", bogusDetail)))
        val bogusResult = importsBogus.recoverWebSourcePage(bookId, "4read", "https://4read.org/bogus.html", "bogus")
        assertNull(bogusResult)

        // Verify the bogus attempt did not mutate the first source's tracks.
        val firstSourceTracks = dao.getTracksForSourceSync(existingSource.id).sortedBy { it.trackIndex }
        assertEquals("https://s1.reasd.org/kobzar/new1.mp3", firstSourceTracks[0].url)
    }

    @Test
    fun `refusal leaves listening state bookmarks and download state untouched`() = runBlocking {
        val originalDetail = detail(
            title = "Кобзар", author = "Тарас Шевченко", narrator = "Іван Петров",
            url = "https://4read.org/kobzar.html",
            chapters = listOf("Глава 1" to "https://s1.reasd.org/kobzar/old1.mp3", "Глава 2" to "https://s1.reasd.org/kobzar/old2.mp3")
        )
        val bookId = seedBook(originalDetail)

        // Seed auxiliary state.
        val edition = dao.getEditionForWork(bookId)!!
        dao.savePlaybackProgress(
            com.slukhayka.audiobooks.data.db.PlaybackProgressEntity(
                editionId = edition.id,
                bookId = bookId,
                currentChapterIndex = 1,
                currentPositionSeconds = 123L,
                lastListenedAt = System.currentTimeMillis()
            )
        )
        dao.insertBookmark(
            com.slukhayka.audiobooks.data.db.BookmarkEntity(
                bookId = bookId,
                editionId = edition.id,
                chapterIndex = 1,
                chapterTitle = "Глава 1",
                timestampSeconds = 10L,
                note = "test bookmark",
                createdAt = System.currentTimeMillis()
            )
        )
        // Mark first track as downloaded.
        val track = dao.getTracksForBookSync(bookId).first()
        dao.updateTrackDownloadState(track.id, true, "/tmp/fake.mp3")

        val progressBefore = dao.getPlaybackProgressSyncByEdition(edition.id)
        val bookmarksBefore = dao.getBookmarksForEdition(edition.id).first()
        val downloadBefore = dao.getTracksForBookSync(bookId).first { it.id == track.id }.isDownloaded

        val reorderedDetail = detail(
            title = "Кобзар", author = "Тарас Шевченко", narrator = "Іван Петров",
            url = "https://4read.org/kobzar.html",
            chapters = listOf("Глава 2" to "https://s1.reasd.org/kobzar/new2.mp3", "Глава 1" to "https://s1.reasd.org/kobzar/new1.mp3")
        )
        val imports = LibraryImport(dao, context, listOf(fakeAdapter("reordered", reorderedDetail)))
        val result = imports.recoverWebSourcePage(bookId, "4read", "https://4read.org/kobzar.html", "reordered")

        assertNull(result)
        assertEquals(progressBefore?.currentChapterIndex, dao.getPlaybackProgressSyncByEdition(edition.id)?.currentChapterIndex)
        assertEquals(progressBefore?.currentPositionSeconds, dao.getPlaybackProgressSyncByEdition(edition.id)?.currentPositionSeconds)
        assertEquals(bookmarksBefore.size, dao.getBookmarksForEdition(edition.id).first().size)
        assertEquals(downloadBefore, dao.getTracksForBookSync(bookId).first { it.id == track.id }.isDownloaded)
    }
}
