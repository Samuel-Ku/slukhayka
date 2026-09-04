package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.player.SmartRetryPolicy
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #505 (parent #397) — partial offline playback, pure JVM (no Robolectric).
 *
 * A partially downloaded Edition plays ready local chapters from disk AND
 * streams the chapters without a local copy from the SAME Source's remote
 * tracks. Only a chapter with no track at all stays honestly unavailable
 * (`track = null`) — never an empty URI to the engine, never a silent
 * switch to another narration or source.
 */
class PartialOfflinePlaybackTest {

    private suspend fun seed(
        dao: FakeAudiobookDao,
        bookId: String,
        /** chapter index -> ready local file; absent index with remote=true gets a remote-only track. */
        localReadyIndices: Set<Int>,
        remoteIndices: Set<Int>
    ) {
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = bookId,
                    title = "Книга",
                    author = "Автор",
                    narrator = "Читець",
                    description = "",
                    coverDrawableRes = 0,
                    sourceUrl = "https://4read.org/book/$bookId",
                    genre = ""
                )
            )
        )
        val editionId = "ed-$bookId"
        dao.insertEdition(
            EditionEntity(id = editionId, workId = bookId, narrator = "Читець", totalChapters = 3)
        )
        dao.insertChapters(
            (0..2).map { index ->
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
                    id = "4read-$bookId",
                    bookId = bookId,
                    editionId = editionId,
                    type = "4read",
                    url = "https://4read.org/book/$bookId"
                )
            )
        )
        val tracks = mutableListOf<SourceTrackEntity>()
        localReadyIndices.forEach { index ->
            val file = File.createTempFile("$bookId-$index", ".mp3").apply {
                writeText("x".repeat(200))
                deleteOnExit()
            }
            tracks += SourceTrackEntity(
                id = "4read-$bookId-tr$index",
                sourceId = "4read-$bookId",
                trackIndex = index,
                url = "https://4read.org/audio/$bookId/$index.mp3",
                localFilePath = file.absolutePath,
                isDownloaded = true
            )
        }
        remoteIndices.forEach { index ->
            tracks += SourceTrackEntity(
                id = "4read-$bookId-tr$index",
                sourceId = "4read-$bookId",
                trackIndex = index,
                url = "https://4read.org/audio/$bookId/$index.mp3"
            )
        }
        dao.insertTracks(tracks)
    }

    @Test
    fun `partial coverage streams missing-local chapters from the same source`() = runTest {
        val dao = FakeAudiobookDao()
        // Chapter 0 on disk, chapter 1 remote-only, chapter 2 has no track at all.
        seed(dao, "partial", localReadyIndices = setOf(0), remoteIndices = setOf(1))
        val catalog = SourceCatalog(dao, emptyList(), LibraryImport(dao, null, emptyList()))

        val playable = catalog.getPlayableChapters("partial")

        assertEquals(3, playable.size)
        // Ready local chapter plays from disk.
        assertNotNull(playable[0].track)
        assertEquals("4read", playable[0].sourceId)
        assertTrue(SmartRetryPolicy.localFileReady(playable[0].track?.localFilePath))
        // No local copy → the SAME source's remote track, not null, not another source.
        assertNotNull("розділ без локальної копії стрімить з того самого джерела", playable[1].track)
        assertEquals("4read", playable[1].sourceId)
        assertEquals("https://4read.org/audio/partial/1.mp3", playable[1].track?.url)
        // No track at all → honestly unavailable, never an empty URI.
        assertNull(playable[2].track)
    }

    @Test
    fun `full remote book is untouched by the lock`() = runTest {
        val dao = FakeAudiobookDao()
        seed(dao, "remote", localReadyIndices = emptySet(), remoteIndices = setOf(0, 1, 2))
        val catalog = SourceCatalog(dao, emptyList(), LibraryImport(dao, null, emptyList()))

        val playable = catalog.getPlayableChapters("remote")

        assertEquals(3, playable.size)
        playable.forEachIndexed { index, pair ->
            assertNotNull(pair.track)
            assertEquals("4read", pair.sourceId)
            assertEquals("https://4read.org/audio/remote/$index.mp3", pair.track?.url)
        }
    }
}
