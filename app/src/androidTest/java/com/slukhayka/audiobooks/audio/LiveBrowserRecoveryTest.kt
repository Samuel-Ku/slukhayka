package com.slukhayka.audiobooks.audio

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Opt-in physical-device check; pass -e liveBookId for an existing 4read book. */
@RunWith(AndroidJUnit4::class)
class LiveBrowserRecoveryTest {
    @Test fun cancel_and_resume_preserve_completed_files_of_both_books(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("liveBookId")
        val controlId = args.getString("liveControlBookId")
        assumeTrue(!id.isNullOrBlank() && !controlId.isNullOrBlank())
        requireNotNull(id)
        requireNotNull(controlId)
        val app = App.instance
        val dao = app.audiobookDao
        val downloads = app.offlineDownloads
        withContext(Dispatchers.IO) { downloads.downloadAudiobookOffline(controlId) }
        val controlFiles = dao.getTracksForBookSync(controlId).mapNotNull { it.localFilePath }
            .associateWith { hash(File(it)) }
        assertTrue("Control book must have downloaded audio", controlFiles.isNotEmpty())
        val existing = dao.getTracksForBookSync(id).mapNotNull { it.localFilePath }
            .associateWith { hash(File(it)) }
        assertTrue(existing.isNotEmpty())
        val job = launch(Dispatchers.IO) {
            downloads.registerDownloadJob(id, requireNotNull(currentCoroutineContext()[Job]))
            try { downloads.continueDownload(id) }
            finally { downloads.unregisterDownloadJob(id, requireNotNull(currentCoroutineContext()[Job])) }
        }
        try {
            withTimeout(120_000) {
                while (dao.getTracksForBookSync(id).count { !it.localFilePath.isNullOrBlank() } <= existing.size) delay(250)
            }
        } finally {
            downloads.cancelDownload(id)
            job.join()
        }
        val row = requireNotNull(dao.getAudiobookById(id))
        assertEquals("PAUSED", row.downloadState)
        assertTrue(row.downloadProgress > 0 && row.downloadProgress < 1)
        (existing + controlFiles).forEach { (path, digest) -> assertEquals(digest, hash(File(path))) }
        assertFalse(File(app.filesDir, "audiobooks").listFiles().orEmpty().any {
            it.name.startsWith(id) && it.name.endsWith(".tmp")
        })
        Log.i("LiveRecoveryTest", "cancelResume=passed previousFiles=${existing.size} otherBookFiles=${controlFiles.size} state=${row.downloadState}")
    }

    @Test fun failed_source_stops_old_audio_and_search_waits_for_action(): Unit = runBlocking {
        val id = InstrumentationRegistry.getArguments().getString("liveBookId")
        assumeTrue(!id.isNullOrBlank())
        val dao = App.instance.audiobookDao
        val book = requireNotNull(dao.getAudiobookById(requireNotNull(id))).toAudiobookEntity()
        val playable = App.instance.sourceCatalog.getPlayableChapters(id)
        val fixtureId = "device-unavailable-fixture"
        val fixture = book.copy(id = fixtureId, title = "Недоступне тестове джерело", sourceUrl = "")
        val broken = playable.first().let {
            it.copy(
                chapter = it.chapter.copy(id = "$fixtureId-chapter", bookId = fixtureId),
                track = requireNotNull(it.track).copy(
                    id = "$fixtureId-track", url = "https://127.0.0.1:9/unavailable.mp3",
                    localFilePath = null, isDownloaded = false
                ), sourceId = "unknown", sourceUrl = ""
            )
        }
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var vm: MainViewModel
            scenario.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                vm = ViewModelProvider(it)[MainViewModel::class.java]
            }
            try {
                scenario.onActivity {
                    vm.playerManager.loadAndPlayBook(book, playable.map { it.chapter }, playable)
                    vm.setShowFullPlayer(true)
                }
                awaitState("Local chapter starts") { vm.playerState.value.isPlaying }
                scenario.onActivity {
                    vm.playerManager.loadAndPlayBook(fixture, listOf(broken.chapter), listOf(broken), autoPlay = false)
                    vm.togglePlaybackFromPlayer(fixtureId, 0, 0)
                }
                awaitState("Failure must terminate", 35_000) {
                    val state = vm.playerState.value
                    !state.isBuffering && state.lastErrorMsg.isNotBlank()
                }
                assertFalse(vm.playerState.value.isPlaying)
                assertEquals(fixtureId, vm.playerState.value.currentBook?.id)
                assertNull(vm.selectedWebSource.value)
                assertEquals("", vm.searchQuery.value)
                SystemClock.sleep(1_000)
                val texts = mutableListOf<String>()
                fun collect(node: android.view.accessibility.AccessibilityNodeInfo?) {
                    if (node == null) return
                    node.text?.let { texts += it.toString() }
                    repeat(node.childCount) { collect(node.getChild(it)) }
                }
                awaitState("Failure actions appear on screen", 8_000) {
                    texts.clear()
                    collect(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
                    texts.any { it == "Повторити" }
                }
                assertTrue("Retry action visible: $texts", texts.any { it == "Повторити" })
                assertTrue("Alternative action visible: $texts", texts.any { it == "Знайти в іншому джерелі" })
                scenario.onActivity { vm.findAnotherSource(book.title) }
                assertEquals(book.title, vm.searchQuery.value)
                scenario.onActivity {
                    vm.playerManager.loadAndPlayBook(book, playable.map { it.chapter }, playable, initialChapterIndex = 3)
                    vm.setShowFullPlayer(true)
                }
                awaitState("A working source still plays after failure") { vm.playerState.value.isPlaying }
                val differentNarration = book.copy(narrator = "Контрольна інша начитка").also {
                    it.mergeKey = book.mergeKey
                    it.workId = book.workId
                }
                scenario.onActivity { vm.playAudiobook(differentNarration) }
                assertNotNull("Another narration needs confirmation", vm.narrationSwitchPrompt.value)
                assertEquals(book.narrator, vm.playerState.value.currentBook?.narrator)
                scenario.onActivity { vm.dismissNarrationSwitch() }
                assertNull(vm.narrationSwitchPrompt.value)
                Log.i("LiveRecoveryTest", "failedSource=bounded oldAudio=stopped retry=visible search=explicit narration=confirmation")
            } finally {
                scenario.onActivity { vm.playerManager.pause() }
                dao.deletePlaybackProgressForBook(fixtureId)
                dao.deletePlaybackEventsForBook(fixtureId)
            }
        }
    }

    @Test fun saved_page_recovers_same_chapter_without_losing_local_data(): Unit = runBlocking {
        val bookId = InstrumentationRegistry.getArguments().getString("liveBookId")
        assumeTrue("Requires an explicitly selected live test book", !bookId.isNullOrBlank())
        val id = requireNotNull(bookId)
        val dao = App.instance.audiobookDao
        val beforeBooks = dao.getAllAudiobooksOnce().map { it.id }.toSet()
        val bookmarks = dao.getBookmarksForBookSync(id)
        val tracks = dao.getTracksForBookSync(id)
        val files = tracks.mapNotNull { it.localFilePath }.distinct().associateWith { hash(File(it)) }
        assertTrue("Requires a partially downloaded book", files.isNotEmpty())
        val remote = tracks.first { it.localFilePath.isNullOrBlank() }
        val chapter = remote.trackIndex
        val position = 42_000L
        val book = requireNotNull(dao.getAudiobookById(id)).toAudiobookEntity()
        // Break only the in-memory media item. The saved source page, database
        // URLs and downloaded files remain untouched throughout the test.
        val expired = App.instance.sourceCatalog.getPlayableChapters(id).map { item ->
            if (item.track?.id == remote.id) item.copy(
                track = remote.copy(url = "https://127.0.0.1:9/expired.mp3")
            ) else item
        }
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, MainActivity::class.java)
            .putExtra("openBookDetail", true).putExtra("bookId", id)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var vm: MainViewModel
            scenario.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                vm = ViewModelProvider(it)[MainViewModel::class.java]
            }
            scenario.onActivity {
                vm.playerManager.loadAndPlayBook(
                    book, expired.map { it.chapter }, expired,
                    initialChapterIndex = chapter,
                    initialPositionSeconds = position / 1000,
                    autoPlay = false
                )
                vm.setShowFullPlayer(true)
                vm.togglePlaybackFromPlayer(id, chapter, position)
            }
            var sawBrowser = false
            val deadline = SystemClock.elapsedRealtime() + 150_000
            while (SystemClock.elapsedRealtime() < deadline) {
                sawBrowser = sawBrowser || vm.selectedWebSource.value != null
                val state = vm.playerState.value
                if (sawBrowser && vm.selectedWebSource.value == null && state.isPlaying &&
                    state.currentBook?.id == id && state.currentChapterIndex == chapter) break
                SystemClock.sleep(200)
            }
            val state = vm.playerState.value
            Log.i("LiveRecoveryTest", "browser=$sawBrowser closed=${vm.selectedWebSource.value == null} playing=${state.isPlaying} chapter=${state.currentChapterIndex} position=${state.currentPositionMs} error=${state.lastErrorMsg}")
            assertTrue("Browser recovery must have started", sawBrowser)
            assertNull("Browser closes after audio actually starts", vm.selectedWebSource.value)
            assertTrue("Recovered audio must play: ${state.lastErrorMsg}", state.isPlaying)
            assertEquals(id, state.currentBook?.id)
            assertEquals(chapter, state.currentChapterIndex)
            assertTrue("Resume position: ${state.currentPositionMs}", state.currentPositionMs in 40_000..55_000)
            assertEquals(beforeBooks, dao.getAllAudiobooksOnce().map { it.id }.toSet())
            assertEquals(bookmarks, dao.getBookmarksForBookSync(id))
            files.forEach { (path, digest) -> assertEquals("Preserved $path", digest, hash(File(path))) }
            scenario.onActivity { vm.playerManager.pause() }
        }
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun awaitState(message: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(message, condition())
    }
}
