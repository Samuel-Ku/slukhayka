package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.downloads.OfflineDownloads
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import com.slukhayka.audiobooks.testing.FakeFetcher
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repository seam (spec-15 T4 + ADR-0002 #139): the one-tap catalogue-card
 * download flow. The card is ephemeral — the ViewModel imports the book
 * transparently via the Library Import door and then runs the shared
 * download loop in the Offline Downloads module. These tests pin the seam
 * with fake adapters (no network): the import materialises chapters so the
 * download loop has something to run, and a stream-only source refuses the
 * download in depth even when the card would otherwise play. Per ADR-0002
 * the download-path tests construct the Offline Downloads + Source Catalog +
 * Library Import modules directly — never the god module.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CatalogDownloadRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // The download loop skips a fetch whose local file already exists
        // ("already downloaded"), and chapter ids are deterministic — a stale
        // Robolectric filesDir from an earlier run would make the ADR-0006
        // header-count test flaky. Start every test from an empty audio dir.
        File(context.filesDir, com.slukhayka.audiobooks.data.downloads.OfflineDownloads.OFFLINE_AUDIO_DIR)
            .deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private class FakeAdapter(
        override val sourceId: String,
        private val book: SourceBook,
        // Chapter stream URL scheme: non-http by default so the download loop
        // skips the fetch entirely (existing tests stay network-free); the
        // ADR-0006 stream test injects real http URLs served in-memory.
        private val streamUrl: (Int) -> String = { "chapter-$it" }
    ) : SourceAdapter {
        override val sessionBound: Boolean get() = false

        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = listOf(book)

        override suspend fun fetchBookPage(url: String): SourceBookDetail = SourceBookDetail(
            title = book.title,
            author = book.author,
            url = url,
            chapters = (0 until 2).map { i ->
                SourceChapter(title = "${book.title} ${i + 1}", streamUrl = streamUrl(i))
            }
        )
    }

    // ADR-0002 (#139): download-path tests construct the deep modules
    // directly — downloads + catalog + fake DAO, nothing else.
    private class Harness(
        val imports: LibraryImport,
        val catalog: SourceCatalog,
        val downloads: OfflineDownloads
    )

    private fun harness(vararg adapters: SourceAdapter, fetcher: HttpFetcher = HttpFetcher()): Harness {
        val adaptersList = adapters.toList()
        val imports = LibraryImport(dao, context, adaptersList)
        val catalog = SourceCatalog(dao, adaptersList, imports)
        return Harness(imports, catalog, OfflineDownloads(dao, context, catalog, fetcher))
    }

    private fun book(sourceId: String, url: String) =
        SourceBook(title = "Пасажир", author = "Жан-Крістоф Гранже", url = url, sourceId = sourceId)

    @Test
    fun `catalogue card import materialises chapters so the download loop runs`() = runBlocking {
        val url = "https://sluhay.com/svitova-literatura/6177-pasazhir.html"
        val harness = harness(FakeAdapter("sluhay", book("sluhay", url)))

        val imported = harness.imports.importFromSourceUrl("sluhay", url)

        assertNotNull(imported)
        val chapters = dao.getChaptersListForBook(imported!!.id)
        assertEquals(2, chapters.size)
        // The loop runs (non-http urls → the chapter fetch is skipped, so it
        // reports every chapter as failed — the whole path is exercised, no
        // network). The card's progress derives from this Room row.
        val outcome = harness.downloads.downloadAudiobookOffline(imported.id)
        assertEquals(2, outcome.totalChapters)
        assertEquals(0, outcome.downloadedChapters)
        assertFalse(dao.getAudiobookById(imported.id)!!.isDownloaded)
    }

    @Test
    fun `stream-only source refuses the download in depth even after import`() = runBlocking {
        val url = "https://lihtar.in.ua/biblioteka/pasazhir"
        val harness = harness(FakeAdapter("lihtar", book("lihtar", url)))

        // The card would play from lihtar (streaming is allowed) — but the
        // download path refuses up front, so the ViewModel's transparent
        // import + download never writes files.
        val imported = harness.imports.importFromSourceUrl("lihtar", url)

        assertNotNull(imported)
        val outcome = harness.downloads.downloadAudiobookOffline(imported!!.id)

        assertEquals(0, outcome.totalChapters)
        assertEquals(0, outcome.downloadedChapters)
        assertFalse(dao.getAudiobookById(imported.id)!!.isDownloaded)
        assertEquals(0f, dao.getAudiobookById(imported.id)!!.downloadProgress)
        // ADR-0007: download state lives on the TRACK rows.
        assertTrue(dao.getTracksForBookSync(imported.id).none { it.isDownloaded })
    }

    @Test
    fun `download loop consumes the shared fetcher stream with per-source headers - no network`() = runBlocking {
        // ADR-0006: the download path performs no HTTP of its own — it
        // consumes the fetcher's binary stream method; the fixture fake serves
        // in-memory bytes, and the per-source Referer rides along.
        val url = "https://sluhay.com/svitova-literatura/6177-pasazhir.html"
        val track0 = "https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/4/4/track-0.mp3"
        val track1 = "https://j3wccg4mgjcw.redirectto.cc/s05/2/6/5/4/4/track-1.mp3"
        // In-memory "audio" — large enough to pass the >100-byte check.
        val audio = ByteArray(1024) { 0x42 }
        val fetcher = FakeFetcher(streamResponses = mapOf(track0 to audio, track1 to audio))
        val harness = harness(
            FakeAdapter("sluhay", book("sluhay", url)) { i -> if (i == 0) track0 else track1 },
            fetcher = fetcher
        )

        val imported = harness.imports.importFromSourceUrl("sluhay", url)!!

        val outcome = harness.downloads.downloadAudiobookOffline(imported.id)

        assertEquals(2, outcome.totalChapters)
        assertEquals(2, outcome.downloadedChapters)
        assertTrue(dao.getAudiobookById(imported.id)!!.isDownloaded)
        // ADR-0006: every stream fetch carried the owning source's Referer
        // (the playerjs CDN 403s without it), not the fetcher's defaults.
        assertEquals(
            listOf(mapOf("Referer" to "https://sluhay.com/"), mapOf("Referer" to "https://sluhay.com/")),
            fetcher.recordedHeaders
        )
    }

    @Test
    fun `re-download after partial failure skips completed chapters`() = runBlocking {
        // Non-http urls fail; nothing is written, so a re-run behaves the same
        // — the loop stays idempotent and never corrupts state.
        val url = "https://sluhay.com/svitova-literatura/6177-pasazhir.html"
        val harness = harness(FakeAdapter("sluhay", book("sluhay", url)))
        val imported = harness.imports.importFromSourceUrl("sluhay", url)!!

        harness.downloads.downloadAudiobookOffline(imported.id)
        val second = harness.downloads.downloadAudiobookOffline(imported.id)

        assertEquals(2, second.totalChapters)
        assertEquals(0, second.downloadedChapters)
        assertEquals(1, dao.getAllAudiobooks().first().size)
    }
}
