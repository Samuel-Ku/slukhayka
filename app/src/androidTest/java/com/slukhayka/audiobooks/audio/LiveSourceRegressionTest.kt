package com.slukhayka.audiobooks.audio

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.collective.*
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.catalog.CatalogCardActionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Opt-in live regression over the real network sources. Never modifies the
 * listener's installed library — that protection is the SUITE's, not this
 * test's: every instrumented run goes through `IsolatedDatabaseTestRunner`
 * (`testInstrumentationRunner` in app/build.gradle.kts), which points the app
 * at the scratch database `read4_audiobook_database_test` and deletes it on
 * entry. Production's `read4_audiobook_database` is untouched by construction.
 *
 * This used to `require(packageName.endsWith(".regression"))` — a guard from
 * 2026-09-14, when imports really did write the listener's database. The
 * runner landed on 2026-09-16 and made that guard both obsolete and
 * impossible: no `.regression` build type exists, so the test FAILED the
 * moment anyone ran it as documented (`-P…liveSources=true`). It is now
 * skipped unless opted in, and runs against the isolated database.
 *
 * Supply an expired Soundbooks URL as an instrumentation argument; signed
 * URLs are never committed.
 */
class LiveSourceRegressionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun realSourcesRecoverAndKeepBookMetadata() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveSources") == "true")
        // Isolation is the runner's job (IsolatedDatabaseTestRunner); assert it
        // is really in force rather than trusting a build-type suffix that does
        // not exist. See the class doc.
        require(
            com.slukhayka.audiobooks.data.db.AudiobookDatabase.databaseNameOverride != null
        ) { "Instrumented runs must use IsolatedDatabaseTestRunner" }
        rule.runOnUiThread {
            rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val app = App.instance
        val dao = app.audiobookDao
        val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        try {
            // Cold Play from the catalogue, including the production import
            // timeout and source request gate, not a warmed adapter-only probe.
            rule.runOnUiThread {
                vm.playCatalogBook(CatalogBook(
                    "live-lihtar-love", "І знову про любов", "Олена і Тимур Литовченки",
                    "https://lihtar.in.ua/biblioteka/khudozhnja-literatura/i-znovu-pro-lubov", null
                ))
                vm.setShowFullPlayer(true)
            }
            rule.waitUntil(190_000) {
                vm.catalogCardActionState.value is CatalogCardActionState.Failed ||
                    vm.playerState.value.let { it.currentBook?.sourceUrl?.contains("lihtar.in.ua") == true && it.isPlaying }
            }
            assertTrue("Lihtar Play: ${vm.catalogCardActionState.value}", vm.playerState.value.isPlaying)
            assertEquals(7, vm.playerState.value.chapters.size)
            assertTrue(vm.playerState.value.currentStreamUrl.contains("/audio/library/"))
            assertAdvances(vm)
            rule.runOnUiThread { vm.playerManager.nextChapter() }
            rule.waitUntil(30_000) { vm.playerState.value.let { it.currentChapterIndex == 1 && it.isPlaying } }
            assertAdvances(vm)
            rule.runOnUiThread { vm.playerManager.nextChapter() }
            rule.waitUntil(30_000) { vm.playerState.value.let { it.currentChapterIndex == 2 && it.isPlaying } }
            assertAdvances(vm)
            screenshot("lihtar-love")
            rule.runOnUiThread { vm.playerManager.stopAndClear() }
            Log.i("LiveSourceRegression", "PASS Lihtar: cold catalogue Play, seven chapters, position advances")

            val book = runBlocking {
                app.libraryImport.importFromSourceUrl(
                    "soundbooks", "https://sound-books.net/zarubizhna-literatura/2851-temna-materiia.html"
                )
            } ?: error("Soundbooks import failed")
            assertEquals("Темна матерія", book.title)
            val expired = requireNotNull(args.getString("expiredSoundbooksUrl"))
            require(expired.startsWith("https://arch.sound-books.net/"))
            require(android.net.Uri.parse(expired).getQueryParameter("expires")!!.toLong() * 1000 < System.currentTimeMillis())
            runBlocking {
                val track = dao.getTracksForBookSync(book.id).first { it.trackIndex == 0 }
                dao.insertTracks(listOf(track.copy(url = expired)))
                val seo = "Аудіокнига Темна матерія - Блейк Крауч 🎶слухати онлайн українською"
                val workId = requireNotNull(dao.findWorkByMergeKey(
                    com.slukhayka.audiobooks.data.merge.MergeKey.keyFor(book.title, book.author)
                )).id
                dao.updateBookTitle(book.id, seo)
                dao.updateWorkTitle(workId, seo)
                app.storedMetadataScrub.scrubOnce()
                assertEquals("Темна матерія", dao.getAudiobookById(book.id)!!.title)
                assertEquals("Темна матерія", dao.getAllWorkTitleRows().first { it.id == workId }.title)
            }
            rule.runOnUiThread {
                vm.playAudiobook(book, chapterIndex = 0)
                vm.setShowFullPlayer(true)
            }
            rule.waitUntil(90_000) { vm.playerState.value.let { it.currentBook?.id == book.id && it.isPlaying } }
            assertTrue(vm.playerState.value.currentStreamUrl.startsWith("https://arch.sound-books.net/"))
            assertEquals("Темна матерія", vm.playerState.value.currentBook!!.title)
            assertAdvances(vm)
            screenshot("soundbooks-healed")
            // CDN cache can still serve real audio for an expired signature.
            // Both paths are valid; don't claim a heal unless the URL changed.
            // Forced rejection -> fresh URL is deterministic in the player test.
            val refreshed = vm.playerState.value.currentStreamUrl != expired
            Log.i("LiveSourceRegression", "PASS Soundbooks: audio advances, URL refreshed=$refreshed, same source, clean stored/player title")
            rule.runOnUiThread { vm.playerManager.stopAndClear() }

            assertDoctorSleepPlays(vm)
            rule.runOnUiThread { vm.playerManager.stopAndClear() }

            val archive = requireNotNull(com.slukhayka.audiobooks.data.source.HttpFetcher().getRangeStream(
                "http://archive.org/download/peppi-2-05-peppi-zaznaie-korabelnoi-avarii/Peppi_2_01_Peppi_khodyt_po_kramnytsiakh.mp3",
                mapOf("Range" to "bytes=0-0")
            ))
            archive.stream.use { assertTrue(it.read() >= 0) }
            assertEquals(206, archive.status)
            Log.i("LiveSourceRegression", "PASS Archive: legacy HTTP URL reaches audio through HTTPS")

            // The actual Home block store, including the time-fresh stale
            // parser output left behind by the previous application build.
            runBlocking {
                val store = RoomCollectiveFeedBlockStore(dao)
                for (source in listOf("lihtar", "audiobookmp3")) {
                    val old = if (source == "lihtar") CollectiveBlockCard(
                        source, "https://lihtar.in.ua/biblioteka/khudozhnja-literatura/ja-kamin-1", "Ja kamin 1", ""
                    ) else CollectiveBlockCard(
                        source, "https://audiobook-mp3.com/uk-audio-5523-jak-priborkati-drakona", "Як приборкати дракона", "Як приборкати дракона"
                    )
                    val now = System.currentTimeMillis()
                    val key = newArrivalsBlockKey(source)
                    store.activate(CollectiveFeedBlock(
                        key, source, CollectiveBlockKind.NEW_ARRIVALS, "Новинки", old.sourceUrl,
                        listOf(old), now, now + CollectiveBlockPolicy.NEW_ARRIVALS_TTL_MS, 1,
                        CollectiveAttempt(now, CollectiveAttemptStatus.SUCCESS)
                    ))
                    val repaired = requireNotNull(app.collectiveFeedRefresh.read(key))
                    assertTrue("$source still has legacy cards", repaired.cards != listOf(old))
                    assertTrue("$source still has no covers", repaired.cards.any { !it.coverUrl.isNullOrBlank() })
                    Log.i("LiveSourceRegression", "PASS $source: Home block refreshed with covers")
                }
            }
        } finally {
            rule.runOnUiThread { vm.playerManager.stopAndClear() }
        }
    }

    @Test fun reasdDoctorSleepPlaysBookNotNotice() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSources") == "true")
        // Isolation is the runner's job (IsolatedDatabaseTestRunner); assert it
        // is really in force rather than trusting a build-type suffix that does
        // not exist. See the class doc.
        require(
            com.slukhayka.audiobooks.data.db.AudiobookDatabase.databaseNameOverride != null
        ) { "Instrumented runs must use IsolatedDatabaseTestRunner" }
        rule.runOnUiThread {
            rule.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        try {
            assertDoctorSleepPlays(vm)
        } finally {
            rule.runOnUiThread { vm.playerManager.stopAndClear() }
        }
    }

    private fun assertDoctorSleepPlays(vm: MainViewModel) {
        // Go straight from the source page to Media3. A preliminary browser
        // or HTTP audio probe would warm the CDN and mask missing headers.
        val doctor = runBlocking {
            App.instance.libraryImport.importFromSourceUrl(
                "soundbooks", "https://sound-books.net/zarubizhna-literatura/2841-doktor-son.html"
            )
        } ?: error("Doctor Sleep import failed")
        assertEquals("Доктор Сон", doctor.title)
        rule.runOnUiThread {
            vm.playAudiobook(doctor, chapterIndex = 0)
            vm.setShowFullPlayer(true)
        }
        rule.waitUntil(90_000) {
            vm.playerState.value.let { it.currentBook?.id == doctor.id && it.isPlaying }
        }
        assertEquals(24, vm.playerState.value.chapters.size)
        assertTrue(vm.playerState.value.currentStreamUrl.startsWith("https://reasd.org/4769/"))
        assertTrue("The chapter is longer than the 52-second notice", vm.playerState.value.durationMs > 60_000)
        assertAdvances(vm)
        rule.runOnUiThread { vm.playerManager.seekTo(60_000) }
        rule.waitUntil(30_000) {
            vm.playerState.value.let { it.currentChapterIndex == 0 && it.isPlaying && it.currentPositionMs > 61_500 }
        }
        rule.runOnUiThread { vm.playerManager.nextChapter() }
        rule.waitUntil(30_000) {
            vm.playerState.value.let {
                it.currentChapterIndex == 1 && it.isPlaying && !it.isBuffering && it.durationMs > 60_000
            }
        }
        assertTrue(vm.playerState.value.durationMs > 60_000)
        assertAdvances(vm)
        rule.waitForIdle()
        screenshot("reasd-doctor-playing")
        Log.i("LiveSourceRegression", "PASS Doctor Sleep: reasd audio, 24 chapters, seek past 60 seconds, next chapter plays")
    }

    private fun assertAdvances(vm: MainViewModel) {
        val start = vm.playerState.value.currentPositionMs
        rule.waitUntil(15_000) { vm.playerState.value.currentPositionMs >= start + 1500 }
        assertTrue(vm.playerState.value.lastErrorMsg.isBlank())
    }

    private fun screenshot(name: String) {
        // Capture this test's Window without competing for Android's singleton
        // UiAutomation service (Compose/Espresso can already own that service).
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var status = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(rule.activity.window, bitmap, {
            status = it
            copied.countDown()
        }, Handler(Looper.getMainLooper()))
        assertTrue("Window screenshot timed out", copied.await(10, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, status)
        File(rule.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
