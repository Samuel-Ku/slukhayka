package com.slukhayka.audiobooks.data.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.player.SmartRetryPolicy
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
import java.io.File

/**
 * #472 (spec #462, Implementation Decision 9) — часткове завантаження,
 * as revised by #505 (parent #397).
 *
 * `getPlayableChapters` must not require FULL chapter coverage before the
 * local source wins, AND the presence of local files must not block
 * streaming: ready chapters play from LOCAL, chapters without a copy stream
 * from the SAME source's remote tracks. Only a chapter with no http track
 * anywhere stays honestly unavailable (`track = null`) — never an empty
 * URI to the engine, never another narration or source.
 *
 * The local verdict rides the ONE threshold (#471) —
 * [SmartRetryPolicy.localFileReady] — so a stub file (< [SmartRetryPolicy.LOCAL_MIN_BYTES])
 * counts exactly as "not on disk". Books with NO local files keep the
 * full-remote selection unchanged.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayableChaptersPartialDownloadTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao
    private lateinit var catalog: SourceCatalog

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
        val imports = LibraryImport(dao, context, emptyList())
        catalog = SourceCatalog(dao, emptyList(), imports)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // Seeding helpers — the full post-ADR-0007 aggregate: Edition chapters
    // + per-Source tracks paired 1:1 by index.
    // ------------------------------------------------------------------

    private fun seedBook(
        bookId: String,
        chapterCount: Int,
        /** index -> ready local file (> [SmartRetryPolicy.LOCAL_MIN_BYTES]); absent = no track file at all. */
        localReadyIndices: Set<Int> = emptySet(),
        /** indices with a STUB local file (<= threshold) — on disk but not playable. */
        localStubIndices: Set<Int> = emptySet(),
        withRemoteSource: Boolean = true
    ) = runBlocking {
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = bookId,
                    title = "Книга $bookId",
                    author = "Автор",
                    narrator = "",
                    description = "",
                    coverDrawableRes = 0,
                    sourceUrl = if (withRemoteSource) "https://4read.org/book/$bookId" else "",
                    genre = ""
                )
            )
        )
        val editionId = "ed-$bookId"
        dao.insertEdition(
            EditionEntity(id = editionId, workId = bookId, narrator = "", totalChapters = chapterCount)
        )
        dao.insertChapters(
            (0 until chapterCount).map { index ->
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
        val sources = mutableListOf(
            SourceEntity(
                id = "local-$bookId",
                bookId = bookId,
                editionId = editionId,
                type = "local",
                url = ""
            )
        )
        if (withRemoteSource) {
            sources += SourceEntity(
                id = "4read-$bookId",
                bookId = bookId,
                editionId = editionId,
                type = "4read",
                url = "https://4read.org/book/$bookId"
            )
        }
        dao.insertSources(sources)
        val tracks = mutableListOf<SourceTrackEntity>()
        if (localReadyIndices.isNotEmpty() || localStubIndices.isNotEmpty()) {
            (localReadyIndices + localStubIndices).forEach { index ->
                val file = if (index in localReadyIndices) {
                    File(context.cacheDir, "$bookId-$index.mp3").apply { writeText("x".repeat(200)) }
                } else {
                    // Below the threshold: on disk, but NOT a playable copy.
                    File(context.cacheDir, "$bookId-$index-stub.mp3").apply { writeText("x") }
                }
                tracks += SourceTrackEntity(
                    id = "local-$bookId-tr$index",
                    sourceId = "local-$bookId",
                    trackIndex = index,
                    url = file.absolutePath,
                    localFilePath = file.absolutePath,
                    isDownloaded = true
                )
            }
        }
        if (withRemoteSource) {
            (0 until chapterCount).forEach { index ->
                tracks += SourceTrackEntity(
                    id = "4read-$bookId-tr$index",
                    sourceId = "4read-$bookId",
                    trackIndex = index,
                    url = "https://4read.org/audio/$bookId/$index.mp3"
                )
            }
        }
        dao.insertTracks(tracks)
    }

    // ------------------------------------------------------------------
    // (a) Partial coverage → local chapters play from LOCAL; chapters
    //     without a copy STREAM from the same source's remote tracks (#505
    //     revises #472: presence of local files must not block streaming).
    //     Only a chapter with no http track anywhere stays unavailable.
    // ------------------------------------------------------------------

    @Test
    fun partialCoverage_playsLocalAndStreamsTheRestFromTheSameSource() = runBlocking {
        // 5 chapters, only 2 real files on disk; chapter 2 has a stub file.
        seedBook("partial", chapterCount = 5, localReadyIndices = setOf(0, 1), localStubIndices = setOf(2))

        val playable = catalog.getPlayableChapters("partial")

        assertEquals(5, playable.size)
        playable.take(2).forEachIndexed { index, pair ->
            assertNotNull("розділ $index має гратися локально", pair.track)
            assertEquals("local", pair.sourceId)
            assertTrue(
                "розділ $index грає з локального файлу",
                SmartRetryPolicy.localFileReady(pair.track?.localFilePath)
            )
        }
        // The stub (< LOCAL_MIN_BYTES) is NOT a copy — the same threshold the
        // player applies — so the stub chapter streams like any other
        // chapter without a local file.
        playable.drop(2).forEachIndexed { offset, pair ->
            val index = offset + 2
            assertNotNull("розділ $index стрімить з того самого джерела", pair.track)
            assertEquals("4read", pair.sourceId)
            assertEquals(
                "https://4read.org/audio/partial/$index.mp3",
                pair.track?.url
            )
        }
    }

    // ------------------------------------------------------------------
    // (b) NO local files → the existing full-remote behavior, unchanged.
    // ------------------------------------------------------------------

    @Test
    fun noLocalFiles_keepsFullRemoteBehavior() = runBlocking {
        seedBook("remote-only", chapterCount = 5)

        val playable = catalog.getPlayableChapters("remote-only")

        assertEquals(5, playable.size)
        playable.forEachIndexed { index, pair ->
            assertNotNull("розділ $index грає з remote", pair.track)
            assertEquals("4read", pair.sourceId)
            assertEquals(
                "https://4read.org/audio/remote-only/$index.mp3",
                pair.track?.url
            )
        }
    }

    @Test
    fun deletedLocalFiles_fallBackToFullRemoteLikeBefore() = runBlocking {
        // A local source whose files are all gone is NOT a local book — the
        // full-remote selection stands (nothing changed for such books).
        seedBook("stale-local", chapterCount = 3, withRemoteSource = true)

        val playable = catalog.getPlayableChapters("stale-local")

        assertEquals(3, playable.size)
        playable.forEach { pair ->
            assertNotNull(pair.track)
            assertEquals("4read", pair.sourceId)
        }
    }

    // ------------------------------------------------------------------
    // (c) Full coverage → all chapters play from LOCAL.
    // ------------------------------------------------------------------

    @Test
    fun fullCoverage_playsEverythingLocally() = runBlocking {
        seedBook("full", chapterCount = 4, localReadyIndices = setOf(0, 1, 2, 3))

        val playable = catalog.getPlayableChapters("full")

        assertEquals(4, playable.size)
        playable.forEachIndexed { index, pair ->
            assertNotNull(pair.track)
            assertEquals("local", pair.sourceId)
            assertTrue(SmartRetryPolicy.localFileReady(pair.track?.localFilePath))
        }
    }
}
