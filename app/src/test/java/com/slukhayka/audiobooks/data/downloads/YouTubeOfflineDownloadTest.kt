package com.slukhayka.audiobooks.data.downloads

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.YouTubeTracks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A bare Application (#915): the manifest wires the real `App`, whose
 * `onCreate` launches ~11 unmanaged `Dispatchers.IO` jobs (sitemap/work-index
 * warm-up, catalogue sync, scrub/merge passes). They keep writing into this
 * test's private `filesDir` while Robolectric tears the test down, so the temp
 * directory is sometimes left behind (observed: `sitemap_validators.tsv` in a
 * leftover `robolectric-*` dir), and they open native SQLite in the same
 * process the in-memory test DB uses. This test composes every module it needs
 * (its own `LibraryImport`, `SourceCatalog`, `OfflineDownloads`), so none of
 * that graph is wanted here — the same bare-app seam
 * `SharedPreferencesLocalCredentialStoreTest` and
 * `CrashDiagnosticLedgerPersistenceTest` already use.
 */
private class BareYouTubeDownloadApp : Application()

/**
 * #780 — the YouTube offline path: a persisted watch URL is a POINTER, not a
 * file. Before the fetch the loop must resolve it per use to a concrete
 * stream URL (the signed URL expires and is never stored), and a failed
 * resolution must fail the chapter honestly instead of writing a fabricated
 * file. The seam was added without a test — this pins it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = BareYouTubeDownloadApp::class)
class YouTubeOfflineDownloadTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private val dao get() = db.audiobookDao()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, OfflineDownloads.OFFLINE_AUDIO_DIR).deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        File(context.filesDir, OfflineDownloads.OFFLINE_AUDIO_DIR).deleteRecursively()
        db.close()
    }

    private val playlistJson = """
        {
          "_type": "playlist",
          "id": "PLyt1",
          "title": "Острів Дума",
          "entries": [
            {"id": "v1aaaaaaaaa", "url": "https://www.youtube.com/watch?v=v1aaaaaaaaa", "title": "Розділ 1", "duration": 60},
            {"id": "v2bbbbbbbbb", "url": "https://www.youtube.com/watch?v=v2bbbbbbbbb", "title": "Розділ 2", "duration": 60}
          ]
        }
    """.trimIndent()

    private val playlistUrl = "https://www.youtube.com/playlist?list=PLyt1"

    private fun importYouTubeBook(): String = runBlocking {
        val imports = LibraryImport(dao, context, emptyList())
        val imported = imports.importSubmittedYouTube(playlistUrl, playlistJson, "@chan")
        imported.bookId!!
    }

    private fun downloads(fetcher: HttpFetcher, resolve: suspend (String) -> String?): OfflineDownloads =
        OfflineDownloads(
            dao,
            context,
            SourceCatalog(dao, emptyList(), LibraryImport(dao, context, emptyList())),
            fetcher,
            // The human rhythm is pinned in OfflineDownloadsPacingTest; here
            // it would only add real waiting.
            pauseFor = { },
            streamUrlResolver = resolve
        )

    private fun audioFetcher(fetched: MutableList<String>) = object : HttpFetcher() {
        override fun getSizedStream(url: String, extraHeaders: Map<String, String>): SizedStream {
            fetched += url
            val audio = ByteArray(4096) { url.hashCode().toByte() }
            return SizedStream(ByteArrayInputStream(audio), audio.size.toLong())
        }

        override fun getSizedStreamResult(url: String, extraHeaders: Map<String, String>): SizedStreamResult =
            SizedStreamResult(200, getSizedStream(url, extraHeaders))
    }

    @Test
    fun `a watch url is resolved before the fetch and the offline copy lands`() = runBlocking {
        val bookId = importYouTubeBook()
        // The chapter workers run in parallel on Dispatchers.IO (the direct-
        // source semaphore holds three permits), so the one resolver call per
        // chapter and the one fetch per chapter land from different threads.
        // A plain ArrayList lost an element under that interleaving — the
        // observed #915 failure "expected:<2> but was:<1>" at the resolved.count
        // assertion — even though both chapters had been downloaded. Collect
        // thread-safely and the assertion observes the real post-state.
        val resolved = CopyOnWriteArrayList<String>()
        val fetched = CopyOnWriteArrayList<String>()
        val downloads = downloads(audioFetcher(fetched)) { watchUrl ->
            resolved += watchUrl
            // The signed stream URL is what the fetch may see — never stored.
            "https://rr1.googlevideo.com/videoplayback?sig=${watchUrl.hashCode()}"
        }

        val result = downloads.downloadAudiobookOffline(bookId)

        assertEquals(2, result.downloadedChapters)
        assertEquals("every persisted watch url went through the resolver", 2, resolved.size)
        assertTrue(
            "the resolver was asked about the STORED watch urls",
            resolved.all { YouTubeTracks.isYouTubeWatchUrl(it) }
        )
        assertTrue(
            "the fetch used the resolved stream url, not the watch url",
            fetched.isNotEmpty() && fetched.all { it.startsWith("https://rr1.googlevideo.com/") }
        )
    }

    @Test
    fun `a failed resolution fails the chapter honestly and writes nothing`() = runBlocking {
        val bookId = importYouTubeBook()
        val fetched = CopyOnWriteArrayList<String>()
        // Same parallel workers: the resolver is asked once per chapter from
        // two threads, so a plain `var attempts++` loses an increment exactly
        // like the list above. The ticket's failing method is this one.
        val attempts = AtomicInteger(0)
        val downloads = downloads(audioFetcher(fetched)) {
            attempts.incrementAndGet()
            null
        }

        val result = downloads.downloadAudiobookOffline(bookId)

        assertEquals("nothing was downloaded", 0, result.downloadedChapters)
        assertEquals(2, result.failedChapters)
        assertEquals("the engine was actually asked", 2, attempts.get())
        assertTrue("no fabricated fetch happened", fetched.isEmpty())
    }
}
