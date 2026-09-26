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

/**
 * #533 AC5 — the three DIRECT sources, exercised through the APP (not by curl).
 *
 * The live fixtures and the network facts behind them are documented in
 * `docs/sources.md` ("Живі фікстури для перевірки стрімінгу"): discovery →
 * chapters → stream → Range, each verified there. This test takes the same
 * books and drives the real chain the listener drives — import from a source
 * URL, then play — so AC5 gets app-level proof rather than a network claim.
 *
 * Opt-in: `-Pandroid.testInstrumentationRunnerArguments.liveSources=true`.
 * Runs against the isolated scratch database
 * ([com.slukhayka.audiobooks.IsolatedDatabaseTestRunner]); the listener's
 * library is never touched.
 *
 * Lihtar is deliberately absent: it is `streamOnly` in the registry and its
 * page exposes no direct mp3 (a JS player), so it cannot be driven from a URL
 * the way these three can — it needs the catalogue/UI path and is tracked in
 * #533 separately.
 *
 * Two gaps found while building this, both recorded rather than papered over:
 *
 * 1. **Ordering matters, and it fooled me once.** An earlier revision
 *    imported the second SluhayUA book while the previous fixture was still
 *    PLAYING, and the import returned null. A standalone probe on the same
 *    build imported that exact URL successfully (title «Сердешна Оксана»), so
 *    the null was this test's sequencing rather than an app defect. The loop
 *    now stops playback before the next import — but NOTE: the 4-fixture run
 *    that change enables has NOT been observed green yet (the emulator was
 *    lost to a missing /dev/kvm mid-verification). The 3-fixture set was green
 *    on 2026-09-26 before the second SluhayUA fixture was added back.
 *
 *    Trap worth knowing: pass slugs UN-encoded. `SluhayuaAdapter.encodedPageUrl`
 *    encodes the slug itself, so a pre-encoded one is double-encoded
 *    (`%D1%96` -> `%25D1%2596`) and the import fails silently — which mimics an
 *    app defect.
 * 2. **Chapter counts are asserted for real now.** They used to be pinned to
 *    ≥ 1 because the app resolved 5 where the page advertised 8; #1037 traced
 *    that to a drained token bucket whose deferral was read as an
 *    end-of-playlist, and after the fix the counts match the pages.
 */
class LiveStreamingSourcesTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val fixtures = listOf(
        Fixture(
            sourceId = "sluhayua",
            url = "https://sluhay.com.ua/1403735:storozhenko-oleksa-marko-prokljatij",
            expectedTitle = "Марко Проклятий",
            // #1037 fixed: the app now resolves all 8. Before the fix it saw 5,
            // because the tail /play calls met a drained token bucket and a
            // deferral was read as an end-of-playlist.
            minChapters = 8
        ),
        Fixture(
            sourceId = "sluhayua",
            // Raw slug: the adapter encodes it itself (see the class doc).
            // A standalone probe on this build imported this very URL fine, so
            // the earlier "import returned null" was THIS TEST's sequencing,
            // not an app defect.
            url = "https://sluhay.com.ua/5931576:grigorіj-kvіtka-osnovjanenko-serdjeshna-oksana",
            expectedTitle = "Сердешна Оксана",
            minChapters = 7
        ),
        Fixture(
            sourceId = "soundbooks",
            url = "https://sound-books.net/zarubizhna-literatura/2827-bezgluzdi-zapytannia.html",
            expectedTitle = "Безглузді запитання",
            minChapters = 1
        ),
        Fixture(
            sourceId = "soundbooks",
            // Verified live 2026-09-26: 12 chapters on arch.sound-books.net,
            // chapter 1 serves audio through the source's Referer rule.
            url = "https://sound-books.net/zarubizhna-literatura/2581-ubyvstvo-pid-chas-doshchu.html",
            expectedTitle = "Убивство під час дощу",
            minChapters = 12
        ),
        Fixture(
            sourceId = "audiobookmp3",
            url = "https://audiobook-mp3.com/uk-audio-6217-brajan-lamli-mij-divnij-pjatnicja",
            // The adapter restores the apostrophe: «пятниця» -> «П'ятниця»,
            // so compare on the stable head of the title.
            expectedTitle = "Мій дивний",
            minChapters = 1
        ),
        Fixture(
            sourceId = "audiobookmp3",
            // Verified live 2026-09-26: 14 chapters, chapter 1 serves audio.
            url = "https://audiobook-mp3.com/uk-audio-1246-dzhek-london-zhaga-do-zhittja",
            expectedTitle = "Жага до життя",
            minChapters = 14
        )
    )

    private data class Fixture(
        val sourceId: String,
        val url: String,
        val expectedTitle: String,
        val minChapters: Int
    )

    @Test
    fun everyDirectSourceResolvesChaptersAndStreamsAudio() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("liveSources") == "true"
        )
        require(AudiobookDatabase.databaseNameOverride != null) {
            "Instrumented runs must use IsolatedDatabaseTestRunner"
        }
        val app = App.instance
        val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]

        val results = mutableListOf<String>()
        try {
            for (fixture in fixtures) {
                val label = "${fixture.sourceId}: ${fixture.expectedTitle}"
                // Nothing of the previous fixture may still be playing: an
                // earlier revision imported the next book mid-playback and got
                // a null import (see finding 1 in the class doc).
                rule.runOnUiThread { vm.playerManager.stopAndClear() }
                rule.waitUntil(15_000) { vm.playerState.value.currentBook == null }

                // 1. Import: discovery → page → chapters, through the adapter.
                val book = runBlocking {
                    app.libraryImport.importFromSourceUrl(fixture.sourceId, fixture.url)
                } ?: error("$label — import returned null")

                assertTrue(
                    "$label — imported title was «${book.title}», expected to contain «${fixture.expectedTitle}»",
                    book.title.contains(fixture.expectedTitle, ignoreCase = true)
                )

                val playable = runBlocking {
                    app.sourceCatalog.getPlayableChapters(book.id)
                }
                assertTrue(
                    "$label — resolved ${playable.size} playable chapters, expected ≥ ${fixture.minChapters}",
                    playable.size >= fixture.minChapters
                )

                // 2. Stream: play the first chapter and prove the position moves.
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
                assertTrue("$label — lastErrorMsg was «${vm.playerState.value.lastErrorMsg}»",
                    vm.playerState.value.lastErrorMsg.isBlank())
                assertTrue(
                    "$label — stream URL was «${vm.playerState.value.currentStreamUrl}»",
                    vm.playerState.value.currentStreamUrl.startsWith("http")
                )

                // 3. PAUSE: the position must freeze while paused.
                rule.runOnUiThread { vm.playerManager.pause() }
                rule.waitUntil(15_000) { !vm.playerState.value.isPlaying }
                val pausedAt = vm.playerState.value.currentPositionMs
                Thread.sleep(2_500)
                assertTrue(
                    "$label — position moved while paused ($pausedAt -> ${vm.playerState.value.currentPositionMs})",
                    vm.playerState.value.currentPositionMs <= pausedAt + 1_000
                )

                // 4. SEEK: past the 52-second source notice where one exists
                //    (SoundBooks serves one), and forward for the rest.
                val seekTarget = if (fixture.sourceId == "soundbooks") 70_000L else 30_000L
                rule.runOnUiThread { vm.playerManager.seekTo(seekTarget) }
                rule.waitUntil(30_000) {
                    vm.playerState.value.currentPositionMs >= seekTarget - 5_000
                }
                rule.runOnUiThread { vm.playerManager.play() }
                val resumedAt = vm.playerState.value.currentPositionMs
                rule.waitUntil(20_000) {
                    vm.playerState.value.isPlaying && vm.playerState.value.currentPositionMs > resumedAt
                }
                assertTrue(
                    "$label — lastErrorMsg after seek was «${vm.playerState.value.lastErrorMsg}»",
                    vm.playerState.value.lastErrorMsg.isBlank()
                )

                Log.i(
                    "LiveStreamingSources",
                    "PASS $label — chapters=${playable.size} stream=${vm.playerState.value.currentStreamUrl} " +
                        "pause/seek ok (played to $pausedAt, seek to $seekTarget)"
                )
                results += "$label — ${playable.size} chapters, position advanced, pause held, seek resumed"

                rule.runOnUiThread { vm.playerManager.stopAndClear() }
            }
        } finally {
            rule.runOnUiThread { vm.playerManager.stopAndClear() }
        }

        assertEquals("every fixture must be reported", fixtures.size, results.size)
        Log.i("LiveStreamingSources", "PASS all ${results.size} fixtures: $results")
    }
}
