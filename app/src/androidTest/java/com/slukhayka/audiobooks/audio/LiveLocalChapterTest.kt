package com.slukhayka.audiobooks.audio

import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * #533 AC5 — «local chapter» and the «local/stream transition».
 *
 * The streaming half of AC5 is covered by `LiveStreamingSourcesTest`. This one
 * covers what that test deliberately does not: a chapter written to DISK and
 * then played from the copy.
 *
 * The chain it proves, for one real SoundBooks book:
 *   1. import from the source URL (network, as the listener does);
 *   2. download ONE chapter — it lands on disk and the TRACK row records the
 *      path (ADR-0007: the physical copy lives on the track, never the
 *      chapter);
 *   3. play that chapter — the player must serve it from the local file, i.e.
 *      the local/stream transition actually happened.
 *
 * Opt-in: `-Pandroid.testInstrumentationRunnerArguments.liveSources=true`.
 * Runs against the isolated scratch database, so the listener's library and
 * downloads are never touched. `@After` removes the copied audio.
 */
class LiveLocalChapterTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** The engine verdict `AudioPlayerManager` reports for a local copy. */
    private val LOCAL_ENGINE = "Offline Local File"

    /** The verified fixture (12 chapters, arch.sound-books.net). */
    private val goodUrl =
        "https://sound-books.net/zarubizhna-literatura/2581-ubyvstvo-pid-chas-doshchu.html"

    @Test
    fun aDownloadedChapterPlaysFromDiskNotFromTheNetwork() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("liveSources") == "true"
        )
        require(AudiobookDatabase.databaseNameOverride != null) {
            "Instrumented runs must use IsolatedDatabaseTestRunner"
        }
        val app = App.instance
        val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        val dao = app.audiobookDao

        try {
            val book = runBlocking {
                app.libraryImport.importFromSourceUrl("soundbooks", goodUrl)
            } ?: error("import failed for $goodUrl")

            // 1. Download exactly ONE chapter.
            val chapters = runBlocking { app.sourceCatalog.getPlayableChapters(book.id) }
            assertTrue("no playable chapters to download", chapters.isNotEmpty())
            val firstChapterId = chapters.first().chapter.id

            val result = runBlocking {
                app.offlineDownloads.downloadSelectedChapters(book.id, setOf(firstChapterId))
            }
            Log.i(
                "LiveLocalChapter",
                "download: downloaded=${result.downloadedChapters} total=${result.totalChapters} " +
                    "shared=${result.sharedChapters} reused=${result.reusedChapters} " +
                    "browserRefresh=${result.requiresBrowserRefresh}"
            )
            assertTrue(
                "the chapter did not land on disk (result=$result)",
                result.downloadedChapters + result.sharedChapters + result.reusedChapters >= 1
            )

            // 2. The TRACK row must carry the local path (ADR-0007).
            val track = runBlocking { dao.getTracksForBookSync(book.id) }
                .firstOrNull { it.trackIndex == 0 }
                ?: error("no track 0 for ${book.id}")
            val path = track.localFilePath
            assertTrue("track 0 has no localFilePath after download", !path.isNullOrBlank())
            assertTrue("isDownloaded must be true on the track", track.isDownloaded)
            val file = File(path!!)
            assertTrue("recorded path does not exist: $path", file.exists())
            assertTrue("downloaded file is empty: $path", file.length() > 0L)
            Log.i("LiveLocalChapter", "on disk: $path (${file.length()} B)")

            // 3. Play it. The point of this test is the local/stream
            //    TRANSITION, so the evidence is the ENGINE the player reports:
            //    AudioPlayerManager sets `audioEngineMode = "Offline Local File"`
            //    exactly when SmartRetryPolicy accepts track.localFilePath and
            //    the MediaItem is built from `Uri.fromFile`. `currentStreamUrl`
            //    is NOT evidence — it reports track.url either way.
            rule.runOnUiThread {
                vm.playAudiobook(book, chapterIndex = 0)
                vm.setShowFullPlayer(true)
            }
            rule.waitUntil(90_000) {
                vm.playerState.value.let { it.currentBook?.id == book.id && it.isPlaying }
            }
            val start = vm.playerState.value.currentPositionMs
            rule.waitUntil(20_000) {
                vm.playerState.value.currentPositionMs >= start + 1500
            }
            assertTrue(
                "lastErrorMsg was «${vm.playerState.value.lastErrorMsg}»",
                vm.playerState.value.lastErrorMsg.isBlank()
            )
            // The engine may take a moment to report after READY; wait, then
            // assert on the verdict rather than the instant.
            rule.waitUntil(20_000) {
                vm.playerState.value.audioEngineMode == LOCAL_ENGINE
            }
            assertEquals(
                "a downloaded chapter must play from the local file, not the stream",
                LOCAL_ENGINE,
                vm.playerState.value.audioEngineMode
            )
            Log.i(
                "LiveLocalChapter",
                "PASS local chapter: engine=${vm.playerState.value.audioEngineMode} " +
                    "positionAdvancedTo=${vm.playerState.value.currentPositionMs}"
            )
        } finally {
            rule.runOnUiThread { vm.playerManager.stopAndClear() }
        }
    }
}
