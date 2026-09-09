package com.slukhayka.audiobooks.data.facets

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.EditionId
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.metadata.MetadataAssertions
import com.slukhayka.audiobooks.data.metadata.SharedTombstone
import com.slukhayka.audiobooks.data.metadata.SubmissionAccessMode
import com.slukhayka.audiobooks.data.metadata.SubmissionChapter
import com.slukhayka.audiobooks.data.metadata.SubmissionPublication
import com.slukhayka.audiobooks.data.metadata.TombstoneTargetKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-0035 / #607 — the LOCAL enforcement of curator Shared Tombstones
 * against an in-memory Room database: on a consumer install the source's
 * catalog claim disappears and future materialization is blocked; on an
 * install that owns the local copy (the submitter's), the tombstone lands as
 * a marker alone — the Library row, the downloaded files and the Listening
 * State survive untouched; a SOURCE tombstone removes exactly that source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomTombstoneProjectionWriterTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao
    private lateinit var writer: RoomTombstoneProjectionWriter
    private lateinit var submissionWriter: RoomSubmissionProjectionWriter

    private val mergeKey = MergeKey.keyFor("Острів Дума", "Стівен Кінг")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
        writer = RoomTombstoneProjectionWriter(dao)
        submissionWriter = RoomSubmissionProjectionWriter(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun publication(url: String) = SubmissionPublication(
        sourceUrl = url,
        accessMode = SubmissionAccessMode.YOUTUBE,
        title = "Острів Дума",
        author = "Стівен Кінг",
        narrator = "Сергій Філатов",
        chapters = listOf(
            SubmissionChapter("Розділ 1", "https://www.youtube.com/watch?v=6XIPkMFZf-0"),
            SubmissionChapter("Розділ 2", "https://www.youtube.com/watch?v=biwxkjI06KA")
        ),
        verifiedAt = 900,
        submittedAt = 1_000,
        submitterId = "device-1"
    )

    private fun workTombstone() = SharedTombstone(
        targetKind = TombstoneTargetKind.WORK,
        mergeKey = mergeKey,
        placedAt = 2_000,
        curatorId = "curator-7"
    )

    private fun sourceTombstone(url: String) = SharedTombstone(
        targetKind = TombstoneTargetKind.SOURCE,
        sourceUrl = url,
        placedAt = 2_000,
        curatorId = "curator-7"
    )

    /** The submitter's door-shaped rows: a real library copy with a downloaded file and Listening State. */
    private suspend fun seedSubmitterCopy(url: String): Pair<String, String> {
        val bookId = "book-1"
        val editionId = EditionId.forBook(mergeKey, bookId, "Сергій Філатов")
        dao.upsertWork(
            WorkEntity(
                id = mergeKey, mergeKey = mergeKey, title = "Острів Дума",
                author = "Стівен Кінг", addedAt = 1_000
            )
        )
        dao.insertEdition(
            EditionEntity(
                id = editionId, workId = mergeKey, narrator = "Сергій Філатов",
                totalChapters = 2, addedAt = 1_000
            )
        )
        dao.insertChapters(
            listOf(
                ChapterEntity(id = "$bookId-ch1", bookId = bookId, chapterIndex = 0, title = "Розділ 1", durationSeconds = 0L, editionId = editionId),
                ChapterEntity(id = "$bookId-ch2", bookId = bookId, chapterIndex = 1, title = "Розділ 2", durationSeconds = 0L, editionId = editionId)
            )
        )
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = bookId, title = "Острів Дума", author = "Стівен Кінг",
                    narrator = "Сергій Філатов", description = "Надіслано посиланням: $url",
                    coverDrawableRes = 0, genre = "local", sourceUrl = url
                )
            )
        )
        dao.upsertLibraryEntry(
            id = bookId, workId = mergeKey, isFavorite = false,
            createdAt = 1_000, downloadProgress = 1f
        )
        val sourceId = "youtube-$editionId-${Integer.toHexString(url.hashCode())}"
        dao.insertSources(
            listOf(
                SourceEntity(
                    id = sourceId, bookId = bookId, editionId = editionId,
                    type = "youtube", url = url, addedAt = 1_000
                )
            )
        )
        dao.insertTracks(
            listOf(
                SourceTrackEntity(
                    id = MetadataAssertions.trackId(sourceId, 0), sourceId = sourceId,
                    trackIndex = 0, url = "https://www.youtube.com/watch?v=6XIPkMFZf-0",
                    localFilePath = "/data/local/ostriv-1.mp3", isDownloaded = true
                ),
                SourceTrackEntity(
                    id = MetadataAssertions.trackId(sourceId, 1), sourceId = sourceId,
                    trackIndex = 1, url = "https://www.youtube.com/watch?v=biwxkjI06KA",
                    localFilePath = "/data/local/ostriv-2.mp3", isDownloaded = true
                )
            )
        )
        dao.savePlaybackProgress(
            PlaybackProgressEntity(
                editionId = editionId, bookId = bookId,
                currentChapterIndex = 1, currentPositionSeconds = 123L, lastListenedAt = 500
            )
        )
        return bookId to sourceId
    }

    // --- WORK tombstone ----------------------------------------------------

    @Test
    fun `work tombstone hides the source on a consumer install and blocks re-materialization`() = runBlocking {
        submissionWriter.apply(listOf(publication("https://www.youtube.com/playlist?list=PLking1")))
        val workId = dao.findWorkByMergeKey(mergeKey)!!.id

        writer.apply(listOf(workTombstone()))

        assertTrue("the local tombstone marker landed", dao.isBookTombstoned(workId))
        assertTrue("the source's catalog claim is gone", dao.getWorkSourcesForWorkSync(workId).isEmpty())
        assertTrue("no sources remain", dao.getSourcesForBookSync(workId).isEmpty())
        assertTrue("no tracks remain", dao.getTracksForBookSync(workId).isEmpty())
        assertTrue("no chapters remain", dao.getChaptersListForBook(workId).isEmpty())

        // The same publication re-arriving must NOT resurrect anything.
        submissionWriter.apply(listOf(publication("https://www.youtube.com/playlist?list=PLking1")))
        assertTrue(dao.getWorkSourcesForWorkSync(workId).isEmpty())
        assertTrue(dao.getSourcesForBookSync(workId).isEmpty())
    }

    @Test
    fun `work tombstone spares the library copy and the listening state`() = runBlocking {
        val url = "https://www.youtube.com/playlist?list=PLking1"
        val (bookId, sourceId) = seedSubmitterCopy(url)

        writer.apply(listOf(workTombstone()))

        assertTrue(dao.isBookTombstoned(mergeKey))
        assertNotNull("the library row survives", dao.getAudiobookById(bookId))
        assertEquals("the source survives - it owns the local copy", 1, dao.getSourcesForBookSync(bookId).size)
        val tracks = dao.getTracksForSourceSync(sourceId)
        assertEquals(2, tracks.size)
        assertTrue(
            "the downloaded files survive",
            tracks.all { it.isDownloaded && it.localFilePath != null }
        )
        assertNotNull("the Listening State survives", dao.getPlaybackProgressSync(bookId))
        assertEquals("chapters stay playable", 2, dao.getChaptersListForBook(bookId).size)
    }

    // --- SOURCE tombstone --------------------------------------------------

    @Test
    fun `source tombstone removes exactly the targeted source`() = runBlocking {
        val urlA = "https://www.youtube.com/playlist?list=PLking1"
        val urlB = "https://www.youtube.com/playlist?list=PLking1-variant"
        submissionWriter.apply(listOf(publication(urlA), publication(urlB)))
        val workId = dao.findWorkByMergeKey(mergeKey)!!.id

        writer.apply(listOf(sourceTombstone(urlA)))

        assertEquals("only the targeted source's claim is gone", 1, dao.getWorkSourcesForWorkSync(workId).size)
        assertEquals(urlB, dao.getWorkSourcesForWorkSync(workId).single().sourceUrl)
        val remaining = dao.getSourcesForBookSync(workId).single()
        assertEquals(urlB, remaining.url)
        assertEquals("chapters are shared - they stay", 2, dao.getChaptersListForBook(workId).size)
    }

    @Test
    fun `source tombstone spares the install that owns the local copy`() = runBlocking {
        val url = "https://www.youtube.com/playlist?list=PLking1"
        val (bookId, _) = seedSubmitterCopy(url)

        writer.apply(listOf(sourceTombstone(url)))

        assertTrue(dao.isBookTombstoned(mergeKey))
        assertNotNull("the library row survives", dao.getAudiobookById(bookId))
        assertEquals("the source rows survive - they own the copy", 1, dao.getSourcesForBookSync(bookId).size)
        assertNotNull("the Listening State survives", dao.getPlaybackProgressSync(bookId))
    }

    // --- no-ops ------------------------------------------------------------

    @Test
    fun `tombstone for an unknown work or url is a no-op`() = runBlocking {
        writer.apply(
            listOf(
                workTombstone().copy(mergeKey = "невідомий твір|автор"),
                sourceTombstone("https://www.youtube.com/watch?v=never-submitted")
            )
        )
        assertTrue(dao.getTombstoneBookIds().isEmpty())
    }

    @Test
    fun `re-applying the same tombstone is idempotent`() = runBlocking {
        submissionWriter.apply(listOf(publication("https://www.youtube.com/playlist?list=PLking1")))
        writer.apply(listOf(workTombstone()))
        writer.apply(listOf(workTombstone()))
        val workId = dao.findWorkByMergeKey(mergeKey)!!.id
        assertTrue(dao.isBookTombstoned(workId))
        assertEquals("one marker, one work", 1, dao.getTombstoneBookIds().size)
    }
}