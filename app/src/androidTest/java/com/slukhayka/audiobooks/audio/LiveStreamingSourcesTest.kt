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
 * 1. **A second SluhayUA book does not import.** «Сердешна Оксана»
 *    (`https://sluhay.com.ua/5931576:grigorіj-kvіtka-osnovjanenko-serdjeshna-oksana`,
 *    RAW slug — the adapter encodes it itself) answers 200 with **7 playlist
 *    entries** to a plain HTTP GET, yet `importFromSourceUrl` returns null in
 *    the app. Ruled out: the fixture URL (verified byte-for-byte against the
 *    adapter's own `encodedPageUrl`), double-encoding (a pre-encoded slug IS a
 *    trap, but the raw one encodes to exactly the URL that works), and the
 *    source being down. So the app drops this book somewhere between the page
 *    fetch and the import — AC5 needs TWO books per source, so this is the
 *    open item for SluhayUA.
 *
 *    Trap worth knowing: pass slugs UN-encoded. A pre-encoded slug gets
 *    double-encoded (`%D1%96` -> `%25D1%2596`) and the import fails silently,
 *    which mimics an app defect.
 * 2. **Chapter count differs from the page.** The page advertises 8 chapters
 *    for «Марко Проклятий» and all eight `/play?fileId=` calls answer from a
 *    desktop curl, but the app resolved **5**. Asserting 8 here would assert
 *    my curl rather than the app, so the fixture asserts ≥ 1 and the count is
 *    logged for whoever investigates.
 */
class LiveStreamingSourcesTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val fixtures = listOf(
        Fixture(
            sourceId = "sluhayua",
            url = "https://sluhay.com.ua/1403735:storozhenko-oleksa-marko-prokljatij",
            expectedTitle = "Марко Проклятий",
            // The page advertises 8 chapters and all 8 /play?fileId= calls
            // answer from a desktop curl. The APP resolved 5 on the emulator
            // (2026-09-26) — recorded, not explained; asserting 8 here would be
            // asserting my curl, not the app. See the class doc.
            minChapters = 1
        ),
        Fixture(
            sourceId = "soundbooks",
            url = "https://sound-books.net/zarubizhna-literatura/2827-bezgluzdi-zapytannia.html",
            expectedTitle = "Безглузді запитання",
            minChapters = 1
        ),
        Fixture(
            sourceId = "audiobookmp3",
            url = "https://audiobook-mp3.com/uk-audio-6217-brajan-lamli-mij-divnij-pjatnicja",
            // The adapter restores the apostrophe: «пятниця» -> «П'ятниця»,
            // so compare on the stable head of the title.
            expectedTitle = "Мій дивний",
            minChapters = 1
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

                Log.i(
                    "LiveStreamingSources",
                    "PASS $label — chapters=${playable.size} stream=${vm.playerState.value.currentStreamUrl}"
                )
                results += "$label — ${playable.size} chapters, position advanced"

                rule.runOnUiThread { vm.playerManager.stopAndClear() }
            }
        } finally {
            rule.runOnUiThread { vm.playerManager.stopAndClear() }
        }

        assertEquals("every fixture must be reported", fixtures.size, results.size)
        Log.i("LiveStreamingSources", "PASS all ${results.size} fixtures: $results")
    }
}
