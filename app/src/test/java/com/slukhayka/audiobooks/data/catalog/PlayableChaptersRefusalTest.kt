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
import kotlinx.coroutines.flow.MutableStateFlow
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
 * ADR-0037 (spec-49 T1) — the Source Audio Refusal in the playable pairing
 * seam ([SourceCatalog.getPlayableChapters]): audio of a refused source
 * never pairs — its streams and its downloads vanish from the Edition —
 * while the listener's own downloaded files (the `local` pseudo-source)
 * stay playable, and metadata flows are untouched. A refused-only Edition
 * pairs honestly empty: the player surfaces «Книга недоступна», the
 * download loop refuses up front with nothing to fetch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayableChaptersRefusalTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao
    private lateinit var catalog: SourceCatalog
    private lateinit var refusal: MutableStateFlow<Set<String>>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
        refusal = MutableStateFlow(emptySet())
        val imports = LibraryImport(dao, context, emptyList())
        catalog = SourceCatalog(dao, emptyList(), imports, sourceAudioRefusal = refusal)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // Seeding — the post-ADR-0007 aggregate: Edition chapters + per-Source
    // tracks paired 1:1 by index (mirrors PlayableChaptersPartialDownloadTest).
    // ------------------------------------------------------------------

    private fun seedBook(
        bookId: String,
        chapterCount: Int,
        /** Indices with a ready local file (> LOCAL_MIN_BYTES) on the local source. */
        localReadyIndices: Set<Int> = emptySet(),
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
        localReadyIndices.forEach { index ->
            val file = File(context.cacheDir, "$bookId-$index.mp3").apply { writeText("x".repeat(200)) }
            tracks += SourceTrackEntity(
                id = "local-$bookId-tr$index",
                sourceId = "local-$bookId",
                trackIndex = index,
                url = file.absolutePath,
                localFilePath = file.absolutePath,
                isDownloaded = true
            )
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

    @Test
    fun `a refused source never pairs but its metadata stays`() = runBlocking {
        seedBook("refused", chapterCount = 2, withRemoteSource = true)
        refusal.value = setOf("4read")

        val playable = catalog.getPlayableChapters("refused")

        assertEquals(2, playable.size)
        playable.forEachIndexed { index, pair ->
            assertNull("розділ $index не грає з відмовленого джерела", pair.track)
        }
        // The dormant Source row stays in the database — undo wakes it with
        // no re-import.
        assertEquals(1, dao.getSourcesForBookSync("refused").count { it.type == "4read" })
        // Metadata reads are untouched by the refusal.
        assertEquals(2, catalog.getChaptersList("refused").size)
    }

    @Test
    fun `downloaded local files keep playing when the source is refused`() = runBlocking {
        seedBook("withfiles", chapterCount = 3, localReadyIndices = setOf(0, 2), withRemoteSource = true)
        refusal.value = setOf("4read")

        val playable = catalog.getPlayableChapters("withfiles")

        assertEquals(3, playable.size)
        playable.forEachIndexed { index, pair ->
            if (index == 1) {
                assertNull("розділ без копії чесно недоступний", pair.track)
            } else {
                assertNotNull("розділ $index грає з локального файлу", pair.track)
                assertEquals("local", pair.sourceId)
            }
        }
    }

    @Test
    fun `undoing the refusal restores the pairing`() = runBlocking {
        seedBook("restored", chapterCount = 2, withRemoteSource = true)
        refusal.value = setOf("4read")
        assertTrue(catalog.getPlayableChapters("restored").all { it.track == null })

        refusal.value = emptySet()

        val playable = catalog.getPlayableChapters("restored")
        assertEquals(2, playable.size)
        playable.forEachIndexed { index, pair ->
            assertEquals("4read", pair.sourceId)
            assertEquals("https://4read.org/audio/restored/$index.mp3", pair.track?.url)
        }
    }

    @Test
    fun `no refusal keeps the pairing exactly as before`() = runBlocking {
        seedBook("plain", chapterCount = 2, withRemoteSource = true)

        val playable = catalog.getPlayableChapters("plain")

        assertEquals(2, playable.size)
        assertEquals("4read", playable[0].sourceId)
        assertNotNull(playable[0].track)
    }

    @Test
    fun `a refused book with local files only still plays them all`() = runBlocking {
        seedBook("alllocal", chapterCount = 2, localReadyIndices = setOf(0, 1), withRemoteSource = true)
        refusal.value = setOf("4read")

        val playable = catalog.getPlayableChapters("alllocal")

        assertEquals(2, playable.size)
        assertTrue(playable.all { it.sourceId == "local" && it.track != null })
    }
}
