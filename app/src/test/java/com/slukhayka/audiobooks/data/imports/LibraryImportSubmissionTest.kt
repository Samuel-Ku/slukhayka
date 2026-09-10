package com.slukhayka.audiobooks.data.imports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.EditionId
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.imports.LibraryImport.SubmittedImportResult
import com.slukhayka.audiobooks.data.merge.MergeKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-0035 / #604 — the submitted-YouTube-link door against an in-memory
 * Room database (the `LibraryImportDirectPageTest` harness): a playlist
 * imports with chapters from its observed entries, a repeated identical
 * submit is a no-op, the SAME narration claimed with an identity lands as a
 * SECOND Source of the SAME Edition (ADR-0007/0035 — progress never forks),
 * and honest failures import nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryImportSubmissionTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao
    private lateinit var libraryImport: LibraryImport

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
        libraryImport = LibraryImport(dao, context, emptyList())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val playlistJson = """
        {
          "_type": "playlist",
          "id": "PLabcd1234",
          "title": "Гаррі Поттер 1 — АудіоКниги Українською",
          "entries": [
            {"_type": "url", "ie_key": "Youtube", "id": "6XIPkMFZf-0", "url": "https://www.youtube.com/watch?v=6XIPkMFZf-0", "title": "Гаррі Поттер 1. Розділ 1", "duration": 4285},
            {"_type": "url", "ie_key": "Youtube", "id": "biwxkjI06KA", "url": "https://www.youtube.com/watch?v=biwxkjI06KA", "title": "Гаррі Поттер 1. Розділ 2", "duration": 4075}
          ]
        }
    """.trimIndent()

    private val singleVideoJson = """
        {"id": "6XIPkMFZf-0", "title": "Гаррі Поттер 1 — АудіоКниги Українською", "formats": []}
    """.trimIndent()

    private val kingPlaylistJson = """
        {
          "_type": "playlist",
          "id": "PLking1",
          "title": "Стівен Кінг - Острів Дума",
          "entries": [
            {"_type": "url", "ie_key": "Youtube", "id": "6XIPkMFZf-0", "url": "https://www.youtube.com/watch?v=6XIPkMFZf-0", "title": "Острів Дума. Розділ 1"},
            {"_type": "url", "ie_key": "Youtube", "id": "biwxkjI06KA", "url": "https://www.youtube.com/watch?v=biwxkjI06KA", "title": "Острів Дума. Розділ 2"}
          ]
        }
    """.trimIndent()

    private val kingSingleJson = """
        {"id": "6XIPkMFZf-0", "title": "Стівен Кінг - Острів Дума", "formats": []}
    """.trimIndent()

    private val playlistUrl = "https://www.youtube.com/playlist?list=PLabcd1234"
    private val videoUrl = "https://www.youtube.com/watch?v=6XIPkMFZf-0"

    @Test
    fun `playlist imports a book with chapters from observed entries`() = runBlocking {
        val result = libraryImport.importSubmittedYouTube(playlistUrl, playlistJson, "@youtube")

        assertEquals(SubmittedImportResult.IMPORTED, result.result)
        val book = dao.getAllBookTitleRows().single()
        assertEquals("Гаррі Поттер 1", book.title)
        assertEquals(book.id, result.bookId)
        assertEquals(dao.getSourceByUrl(playlistUrl)?.id, result.sourceId)
        val tracks = dao.getTracksForBookSync(book.id)
        assertEquals(2, tracks.size)
        assertTrue("tracks carry watch URLs, never signed URLs", tracks.all { it.url.startsWith("https://www.youtube.com/watch?v=") })
        assertTrue("nothing is downloaded at submit time", tracks.all { !it.isDownloaded })
        // Spec-53 T2 — real entry durations ride the import.
        val chapters = dao.getChaptersListForBook(book.id)
        assertEquals(listOf(4285L, 4075L), chapters.map { it.durationSeconds })
    }

    @Test
    fun `the exact same submitted url is a no-op`() = runBlocking {
        libraryImport.importSubmittedYouTube(playlistUrl, playlistJson, "@youtube")

        val result = libraryImport.importSubmittedYouTube(playlistUrl, playlistJson, "@youtube")

        assertEquals(SubmittedImportResult.ALREADY_ADDED, result.result)
        assertEquals(1, dao.getAllBookTitleRows().size)
    }

    @Test
    fun `same claimed narration as a different variant lands in the same edition - a second source`() = runBlocking {
        libraryImport.importSubmittedYouTube("https://www.youtube.com/playlist?list=PLking1", kingPlaylistJson, "@stivenkingua")
        val firstBookId = dao.getAllBookTitleRows().single().id

        // The single-file variant of the SAME narration with the SAME claimed
        // identity («Стівен Кінг - Острів Дума» → Острів Дума | Стівен Кінг):
        // same mergeKey → same Edition id → a SECOND Source, NO second card.
        val result = libraryImport.importSubmittedYouTube(videoUrl, kingSingleJson, "@stivenkingua")

        assertEquals(SubmittedImportResult.IMPORTED, result.result)
        assertEquals(1, dao.getAllBookTitleRows().size)
        assertEquals(firstBookId, dao.getAllBookTitleRows().single().id)
        val mergeKey = MergeKey.keyFor("Острів Дума", "Стівен Кінг")
        val editionId = EditionId.forBook(mergeKey, firstBookId, "YouTube")
        listOf(
            "youtube-$editionId-${Integer.toHexString("https://www.youtube.com/playlist?list=PLking1".hashCode())}",
            "youtube-$editionId-${Integer.toHexString(videoUrl.hashCode())}"
        ).forEach { assertNotNull("both variants are Sources of the SAME edition", dao.getSourceById(it)) }
    }

    @Test
    fun `broken metadata imports nothing`() = runBlocking {
        assertEquals(SubmittedImportResult.METADATA_FAILED, libraryImport.importSubmittedYouTube(playlistUrl, "not json", "@youtube").result)
        assertTrue(dao.getAllBookTitleRows().isEmpty())
    }

    @Test
    fun `metadata without playable tracks imports nothing`() = runBlocking {
        // A non-YouTube URL with no id and no entries yields no watch URL —
        // the honest NO_PLAYABLE_TRACKS case (a youtu.be/video URL with an id
        // IS derivable, so it imports).
        assertEquals(
            SubmittedImportResult.NO_PLAYABLE_TRACKS,
            libraryImport.importSubmittedYouTube("https://example.com/not-a-video", """{"title":"Книга"}""", "@youtube").result
        )
        assertTrue(dao.getAllBookTitleRows().isEmpty())
    }
}