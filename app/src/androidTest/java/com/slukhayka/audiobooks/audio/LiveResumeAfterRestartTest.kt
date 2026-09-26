package com.slukhayka.audiobooks.audio

import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.player.PlaybackResume
import com.slukhayka.audiobooks.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * #533 AC5 — «resume after restart», for a real source book.
 *
 * A true `am force-stop` cannot be driven from inside one instrumentation run,
 * so this proves the SAME contract the restart depends on: the position the
 * listener reached is persisted, and a cold `PlaybackResume.resumeMostRecent`
 * — the exact call the widget and `onPlaybackResumption` make — brings it back.
 *
 * The restart-equivalent state is produced by `stopAndClear()`: no book loaded,
 * nothing playing, which is what a fresh process looks like to this code path.
 * `resumeMostRecent` refuses when a book IS loaded, so clearing it first is
 * what makes the check meaningful rather than vacuous.
 *
 * Opt-in: `-Pandroid.testInstrumentationRunnerArguments.liveSources=true`.
 */
class LiveResumeAfterRestartTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val fixtureUrl =
        "https://sound-books.net/zarubizhna-literatura/2581-ubyvstvo-pid-chas-doshchu.html"

    @Test
    fun thePositionSurvivesAndAColdResumeBringsItBack() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("liveSources") == "true"
        )
        require(AudiobookDatabase.databaseNameOverride != null) {
            "Instrumented runs must use IsolatedDatabaseTestRunner"
        }
        val app = App.instance
        val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]

        try {
            val book = runBlocking {
                app.libraryImport.importFromSourceUrl("soundbooks", fixtureUrl)
            } ?: error("import failed for $fixtureUrl")

            // 1. Play far enough that a saved position is unmistakably not 0.
            rule.runOnUiThread {
                vm.playAudiobook(book, chapterIndex = 0)
                vm.setShowFullPlayer(true)
            }
            rule.waitUntil(90_000) {
                vm.playerState.value.let { it.currentBook?.id == book.id && it.isPlaying }
            }
            rule.runOnUiThread { vm.playerManager.seekTo(75_000L) }
            rule.waitUntil(30_000) { vm.playerState.value.currentPositionMs >= 70_000L }

            // 2. PAUSE is the honest save point (the player pushes at once on a
            //    pause), so the row exists before we simulate the restart.
            rule.runOnUiThread { vm.playerManager.pause() }
            rule.waitUntil(15_000) { !vm.playerState.value.isPlaying }
            val savedAt = vm.playerState.value.currentPositionMs
            // The row must exist before the restart-equivalent step — this is
            // the "persisted" half of the claim.
            rule.waitUntil(15_000) {
                runBlocking { app.listeningState.getProgressSync(book.id) }
                    ?.currentPositionSeconds
                    ?.let { it * 1000L >= savedAt - 5_000L } ?: false
            }

            // 3. The restart-equivalent: no book loaded, nothing playing.
            rule.runOnUiThread { vm.playerManager.stopAndClear() }
            rule.waitUntil(15_000) { vm.playerState.value.currentBook == null }

            // 4. The cold resume the restart path uses.
            val resumed = runBlocking {
                PlaybackResume.resumeMostRecent(
                    playerManager = vm.playerManager,
                    libraryEntries = app.libraryEntries,
                    playableFor = { app.sourceCatalog.getPlayableChapters(it) },
                    autoPlay = true
                )
            }
            assertTrue("cold resume found nothing to resume", resumed)

            rule.waitUntil(90_000) {
                vm.playerState.value.let { it.currentBook?.id == book.id && it.isPlaying }
            }
            // It must be back near the saved position — NOT at zero, which is
            // what a naive restart would do.
            rule.waitUntil(30_000) { vm.playerState.value.currentPositionMs >= 60_000L }
            assertTrue(
                "resumed at ${vm.playerState.value.currentPositionMs}ms, saved was ${savedAt}ms",
                vm.playerState.value.currentPositionMs >= 60_000L
            )
            Log.i(
                "LiveResumeAfterRestart",
                "PASS resumed ${book.title}: savedAt=${savedAt}ms " +
                    "resumedAt=${vm.playerState.value.currentPositionMs}ms " +
                    "chapter=${vm.playerState.value.currentChapterIndex}"
            )
        } finally {
            rule.runOnUiThread { vm.playerManager.stopAndClear() }
        }
    }
}
