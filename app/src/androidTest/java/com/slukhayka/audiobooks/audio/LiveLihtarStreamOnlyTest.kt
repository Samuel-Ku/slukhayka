package com.slukhayka.audiobooks.audio

import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.source.LihtarAudio
import com.slukhayka.audiobooks.data.source.SourceRegistry
import com.slukhayka.audiobooks.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * #533 AC5 — «Lihtar перевірено окремо в дозволеному stream-only режимі».
 *
 * Lihtar is the one source the adapter suite treats differently: the registry
 * marks it `streamOnly`, so its audio may be STREAMED but never downloaded
 * (ToS). This test drives the same chain the listener does — import from a
 * book URL, resolve chapters, play — and then asserts the constraint that
 * makes Lihtar special: every resolved chapter is BOOK audio
 * (`/audio/library/`), never the site's own navigation clips
 * (`/audio/name/`), and the import door must not offer it as a download.
 *
 * Opt-in: `-Pandroid.testInstrumentationRunnerArguments.liveSources=true`.
 */
class LiveLihtarStreamOnlyTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val bookUrl =
        "https://lihtar.in.ua/biblioteka/khudozhnja-literatura/i-znovu-pro-lubov"

    @Test
    fun lihtarStreamsInTheAllowedStreamOnlyMode() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("liveSources") == "true"
        )
        require(AudiobookDatabase.databaseNameOverride != null) {
            "Instrumented runs must use IsolatedDatabaseTestRunner"
        }
        val app = App.instance
        val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]

        try {
            // 0. The registry is the source of truth for the mode.
            assertTrue(
                "lihtar must be marked streamOnly in the registry",
                SourceRegistry.streamOnlyFor("lihtar")
            )

            val book = runBlocking {
                app.libraryImport.importFromSourceUrl("lihtar", bookUrl)
            } ?: error("Lihtar import failed for $bookUrl")

            val playable = runBlocking { app.sourceCatalog.getPlayableChapters(book.id) }
            assertTrue(
                "Lihtar resolved ${playable.size} playable chapters",
                playable.size >= 1
            )

            // 1. Every chapter must be BOOK audio, not a navigation clip.
            //    LihtarAudio is the shared verdict the catalogue also uses.
            val urls = playable.map { it.track?.url.orEmpty() }
            val navigation = urls.filter { LihtarAudio.isNavigationAudio(it) }
            assertTrue(
                "navigation clips leaked into the chapter list: $navigation",
                navigation.isEmpty()
            )
            assertTrue(
                "no chapter is recognised as book audio: $urls",
                urls.all { LihtarAudio.isBookAudio(it) }
            )

            // 2. Stream it: the allowed mode is streaming.
            rule.runOnUiThread {
                vm.playAudiobook(book, chapterIndex = 0)
                vm.setShowFullPlayer(true)
            }
            rule.waitUntil(90_000) {
                vm.playerState.value.let { it.currentBook?.id == book.id && it.isPlaying }
            }
            val start = vm.playerState.value.currentPositionMs
            rule.waitUntil(20_000) {
                vm.playerState.value.currentPositionMs >= start + 1_000
            }
            assertTrue(
                "lastErrorMsg was «${vm.playerState.value.lastErrorMsg}»",
                vm.playerState.value.lastErrorMsg.isBlank()
            )

            // 3. The stream-only constraint: this copy must NOT be downloadable.
            val downloaded = runBlocking {
                app.offlineDownloads.downloadSelectedChapters(
                    book.id,
                    setOf(playable.first().chapter.id)
                )
            }
            assertEquals(
                "a stream-only source must not download (result=$downloaded)",
                0,
                downloaded.downloadedChapters
            )

            Log.i(
                "LiveLihtarStreamOnly",
                "PASS Lihtar: ${playable.size} chapters, engine=${vm.playerState.value.audioEngineMode}, " +
                    "stream=${vm.playerState.value.currentStreamUrl}, download refused as required"
            )
        } finally {
            rule.runOnUiThread { vm.playerManager.stopAndClear() }
        }
    }
}
