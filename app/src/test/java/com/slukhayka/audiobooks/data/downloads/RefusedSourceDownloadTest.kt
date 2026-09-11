package com.slukhayka.audiobooks.data.downloads

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.privacy.PacingParams
import com.slukhayka.audiobooks.data.privacy.PacingPolicy
import com.slukhayka.audiobooks.data.source.HttpFetcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * ADR-0037 (spec-49 T1) — a book whose ONLY audio sources are refused
 * refuses the offline download UP FRONT: before any pacing, fetch or file
 * write. A book with real local files (or an allowed source) is unaffected,
 * and the honest zero-chapter refusal keeps the pre-ADR-0037 result shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RefusedSourceDownloadTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao
    private lateinit var refusal: MutableStateFlow<Set<String>>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, OfflineDownloads.OFFLINE_AUDIO_DIR).deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
        refusal = MutableStateFlow(emptySet())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun harness(): OfflineDownloads {
        val imports = LibraryImport(dao, context, emptyList())
        val catalog = SourceCatalog(dao, emptyList(), imports, sourceAudioRefusal = refusal)
        return OfflineDownloads(
            dao, context, catalog,
            fetcher = HttpFetcher(),
            pacing = PacingPolicy(PacingParams(minPauseMillis = 0, maxPauseMillis = 0)),
            pauseFor = { },
            sourceAudioRefusal = refusal
        )
    }

    private fun seedBook(bookId: String) = runBlocking {
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = bookId,
                    title = "Книга $bookId",
                    author = "Автор",
                    narrator = "",
                    description = "",
                    coverDrawableRes = 0,
                    sourceUrl = "https://sound-books.net/book/$bookId",
                    genre = ""
                )
            )
        )
        val editionId = "ed-$bookId"
        dao.insertEdition(
            EditionEntity(id = editionId, workId = bookId, narrator = "", totalChapters = 2)
        )
        dao.insertChapters(
            (0 until 2).map { index ->
                ChapterEntity(
                    id = "$bookId-ch$index",
                    bookId = bookId,
                    chapterIndex = index,
                    title = "Розділ ${index + 1}",
                    durationSeconds = 60L,
                    editionId = editionId
                )
            }
        )
        dao.insertSources(
            listOf(
                SourceEntity(
                    id = "soundbooks-$bookId",
                    bookId = bookId,
                    editionId = editionId,
                    type = "soundbooks",
                    url = "https://sound-books.net/book/$bookId"
                )
            )
        )
        dao.insertTracks(
            (0 until 2).map { index ->
                SourceTrackEntity(
                    id = "soundbooks-$bookId-tr$index",
                    sourceId = "soundbooks-$bookId",
                    trackIndex = index,
                    url = "https://arch.sound-books.net/audio/$bookId/$index.mp3"
                )
            }
        )
    }

    @Test
    fun `a refused-only book refuses the download before any fetch`() = runBlocking {
        seedBook("refuseddl")
        refusal.value = setOf("soundbooks")

        val outcome = harness().downloadAudiobookOffline("refuseddl")

        assertEquals(0, outcome.totalChapters)
        assertEquals(0, outcome.downloadedChapters)
        assertEquals(false, dao.getAudiobookById("refuseddl")!!.isDownloaded)
        assertEquals(0f, dao.getAudiobookById("refuseddl")!!.downloadProgress)
        // Nothing was written to disk.
        assertEquals(0, tracksOnDisk("refuseddl"))
    }

    @Test
    fun `undoing the refusal lets the download run again`() = runBlocking {
        seedBook("refuseddl2")
        refusal.value = setOf("soundbooks")
        assertEquals(0, harness().downloadAudiobookOffline("refuseddl2").totalChapters)

        refusal.value = emptySet()

        val outcome = harness().downloadAudiobookOffline("refuseddl2")
        // The pairing sees the refused-exempt pool again; the loop runs
        // (non-reachable urls → every chapter fails honestly — no network).
        assertEquals(2, outcome.totalChapters)
    }

    @Test
    fun `no refusal keeps the download loop unchanged`() = runBlocking {
        seedBook("plaindl")

        val outcome = harness().downloadAudiobookOffline("plaindl")

        assertEquals(2, outcome.totalChapters)
        assertEquals(0, outcome.downloadedChapters)
    }

    private fun tracksOnDisk(bookId: String): Int =
        File(context.filesDir, OfflineDownloads.OFFLINE_AUDIO_DIR)
            .walkTopDown()
            .filter { it.isFile }
            .count()
}
