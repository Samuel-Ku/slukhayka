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
    /** Requires separate explicit approval; never writes PrivacySettingsStore. */
    @Test fun unavailable_private_routes_fail_closed_and_streaming_returns(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveRouteTest") == "true")
        val id = requireNotNull(args.getString("liveBookId"))
        val app = App.instance
        val prefs = app.privacySettings.load()
        val originalRoute = com.slukhayka.audiobooks.data.privacy.TransportPrivacy.current()
        assumeTrue(com.slukhayka.audiobooks.data.privacy.NetworkPrivacy.resolve(prefs) ==
            com.slukhayka.audiobooks.data.privacy.RouteResolution.Ok(originalRoute))
        val book = requireNotNull(app.audiobookDao.getAudiobookById(id)).toAudiobookEntity()
        val playable = app.sourceCatalog.getPlayableChapters(id, applyLocalLock = false)
        val local = playable.indexOfFirst { it.track?.localFilePath?.let { path -> File(path).isFile } == true }
        val remote = playable.indexOfFirst { it.track?.let { track -> track.localFilePath.isNullOrBlank() } == true }
        assumeTrue(local >= 0 && remote >= 0)
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var vm: MainViewModel
            scenario.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                vm = ViewModelProvider(it)[MainViewModel::class.java]
                vm.playerManager.loadAndPlayBook(book, playable.map { p -> p.chapter }, playable,
                    initialChapterIndex = remote, initialPositionSeconds = 42)
                vm.setShowFullPlayer(true)
            }
            try {
                awaitState("Initial streamed chapter plays", 35_000) { vm.playerState.value.isPlaying }
                for (mode in listOf(com.slukhayka.audiobooks.data.privacy.RouteMode.CUSTOM_PROXY,
                    com.slukhayka.audiobooks.data.privacy.RouteMode.MAX_PRIVACY)) {
                    com.slukhayka.audiobooks.data.privacy.TransportPrivacy.install(
                        prefs.copy(routeMode = mode, proxyAddress = "127.0.0.1:1"))
                    scenario.onActivity { vm.playerManager.prepareChapter(remote, 42_000, true) }
                    awaitState("Unavailable $mode terminates honestly", 45_000) {
                        val state = vm.playerState.value
                        !state.isPlaying && !state.isBuffering && state.lastErrorMsg.isNotBlank()
                    }
                    scenario.onActivity { vm.playerManager.prepareChapter(local, 42_000, true) }
                    awaitState("Local file plays while $mode is unavailable") { vm.playerState.value.isPlaying }
                    assertEquals(local, vm.playerState.value.currentChapterIndex)
                }
                com.slukhayka.audiobooks.data.privacy.TransportPrivacy.install(prefs)
                scenario.onActivity { vm.playerManager.prepareChapter(remote, 42_000, true) }
                awaitState("Streaming returns on the restored route", 35_000) { vm.playerState.value.isPlaying }
                assertEquals(remote, vm.playerState.value.currentChapterIndex)
                assertTrue(vm.playerState.value.currentPositionMs in 40_000..55_000)
                assertEquals(prefs, app.privacySettings.load())
                Log.i("LiveRecoveryTest", "privateRoutes=passed localOffline=passed networkReturn=passed")
            } finally {
                com.slukhayka.audiobooks.data.privacy.TransportPrivacy.install(prefs)
                scenario.onActivity { vm.playerManager.pause() }
                assertEquals(originalRoute, com.slukhayka.audiobooks.data.privacy.TransportPrivacy.current())
                assertEquals(prefs, app.privacySettings.load())
            }
        }
    }

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
        repeat(2) { attempt ->
            val beforeCount = dao.getTracksForBookSync(id).count { !it.localFilePath.isNullOrBlank() }
            val initialState = dao.getAudiobookById(id)?.downloadState
            if (attempt > 0) assertEquals("PAUSED", initialState)
            Log.i("LiveRecoveryTest", "cancelResumeStart state=$initialState files=${existing.size}")
            val outcome = java.util.concurrent.atomic.AtomicReference<String>("running")
            val initialBytes = downloads.downloadBytesProgress.value[id]?.downloadedBytes ?: 0L
            var madeProgress = false
            val job = launch(Dispatchers.IO) {
                downloads.registerDownloadJob(id, requireNotNull(currentCoroutineContext()[Job]))
                try {
                    outcome.set((if (initialState == "PAUSED") downloads.continueDownload(id)
                        else downloads.downloadAudiobookOffline(id)).toString())
                }
                finally {
                    Log.i("LiveRecoveryTest", "cancelResumeJob result=${outcome.get()} state=${dao.getAudiobookById(id)?.downloadState}")
                    downloads.unregisterDownloadJob(id, requireNotNull(currentCoroutineContext()[Job]))
                }
            }
            try {
                val waitMs = args.getString("liveDownloadWaitMs")?.toLongOrNull()?.coerceIn(30_000, 600_000) ?: 120_000
                withTimeout(waitMs) {
                    while (
                        dao.getTracksForBookSync(id).count { !it.localFilePath.isNullOrBlank() } <= beforeCount &&
                        (downloads.downloadBytesProgress.value[id]?.downloadedBytes ?: 0L) <= initialBytes
                    ) {
                        assertFalse("Queue ended without a new file: initial=$initialState result=${outcome.get()} state=${dao.getAudiobookById(id)?.downloadState}", job.isCompleted)
                        delay(250)
                    }
                    madeProgress = true
                }
            } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                throw AssertionError("Download timed out: initial=$initialState result=${outcome.get()} state=${dao.getAudiobookById(id)?.downloadState} bytes=${downloads.downloadBytesProgress.value[id]}", failure)
            } finally {
                downloads.cancelDownload(id)
                job.join()
            }
            val row = requireNotNull(dao.getAudiobookById(id))
            assertEquals("PAUSED", row.downloadState)
            assertTrue(row.downloadProgress > 0 && row.downloadProgress < 1)
            assertTrue(
                "Continue must download bytes or finish another chapter",
                madeProgress
            )
            (existing + controlFiles).forEach { (path, digest) -> assertEquals(digest, hash(File(path))) }
            assertFalse(File(app.filesDir, "audiobooks").listFiles().orEmpty().any {
                it.name.startsWith(id) && it.name.endsWith(".tmp")
            })
            Log.i("LiveRecoveryTest", "cancelResume=passed attempt=$attempt previousFiles=${existing.size} otherBookFiles=${controlFiles.size} state=${row.downloadState}")
        }
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
        val playable = App.instance.sourceCatalog.getPlayableChapters(id)
        val chapter = playable.indexOfFirst { item ->
            item.track?.let { track ->
                track.localFilePath.isNullOrBlank() && track.url?.startsWith("http") == true
            } == true
        }
        assertTrue("Requires a streamed chapter in the player's selected source", chapter >= 0)
        val remote = requireNotNull(playable[chapter].track)
        val position = 42_000L
        val book = requireNotNull(dao.getAudiobookById(id)).toAudiobookEntity()
        // Break only the in-memory media item. The saved source page, database
        // URLs and downloaded files remain untouched throughout the test.
        val expired = playable.map { item ->
            if (item.track?.id == remote.id) item.copy(
                track = remote.copy(url = "https://127.0.0.1:9/expired.mp3")
            ) else item
        }
        assertEquals("https://127.0.0.1:9/expired.mp3", expired[chapter].track?.url)
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

    @Test fun selected_chapters_play_and_transport_controls_work(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("liveBookId")
        assumeTrue(!id.isNullOrBlank())
        requireNotNull(id)
        val app = App.instance
        val book = requireNotNull(app.audiobookDao.getAudiobookById(id)).toAudiobookEntity()
        val playable = app.sourceCatalog.getPlayableChapters(id)
        fun local(index: Int) = playable[index].track?.localFilePath?.let { File(it).isFile } == true
        val remote = playable.indices.first { !local(it) && playable[it].track?.url?.startsWith("http") == true }
        val allRemote = args.getString("liveRequireUndownloaded") == "true"
        val first = if (allRemote) {
            assertTrue("The entire book must have no local audio", app.audiobookDao.getTracksForBookSync(id)
                .none { it.localFilePath?.let { path -> File(path).isFile } == true })
            remote
        } else playable.indices.first { local(it) }
        val second = if (allRemote) playable.indices.first { it != first && !local(it) } else remote
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var vm: MainViewModel
            scenario.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                vm = ViewModelProvider(it)[MainViewModel::class.java]
            }
            try {
                scenario.onActivity {
                    vm.playerManager.loadAndPlayBook(book, playable.map { it.chapter }, playable,
                        initialChapterIndex = first, initialPositionSeconds = 42, autoPlay = false)
                    vm.setShowFullPlayer(true)
                    vm.togglePlaybackFromPlayer(id, first, 42_000)
                }
                awaitState("First selected chapter plays", 150_000) {
                    vm.playerState.value.let { it.isPlaying && it.currentBook?.id == id && it.currentChapterIndex == first }
                }
                val start = vm.playerState.value.currentPositionMs
                awaitState("Audio position advances") { vm.playerState.value.currentPositionMs > start + 1_000 }
                scenario.onActivity { vm.playerManager.pause() }
                awaitState("Pause stops playback") { !vm.playerState.value.isPlaying }
                val paused = vm.playerState.value.currentPositionMs
                SystemClock.sleep(1_200)
                assertTrue("Pause holds position", kotlin.math.abs(vm.playerState.value.currentPositionMs - paused) < 500)
                scenario.onActivity { vm.playerManager.seekTo(60_000) }
                awaitState("Seek reaches requested position") { vm.playerState.value.currentPositionMs in 59_000..61_000 }
                scenario.onActivity { vm.togglePlaybackFromPlayer(id, first, 60_000) }
                awaitState("Resume plays") { vm.playerState.value.isPlaying }
                scenario.onActivity { vm.playerManager.selectChapter(second) }
                awaitState("Second selected chapter plays", 150_000) {
                    vm.playerState.value.let { it.isPlaying && it.currentChapterIndex == second }
                }
                assertTrue("Selected chapter starts at its beginning", vm.playerState.value.currentPositionMs in 0..15_000)
                assertEquals(id, vm.playerState.value.currentBook?.id)
                Log.i("LiveRecoveryTest", "transport=passed allRemote=$allRemote first=$first second=$second")
            } finally {
                scenario.onActivity { vm.playerManager.pause() }
            }
        }
    }

    @Test fun local_chapter_automatically_advances_to_stream(): Unit = runBlocking {
        val id = InstrumentationRegistry.getArguments().getString("liveBookId")
        assumeTrue(!id.isNullOrBlank())
        requireNotNull(id)
        val app = App.instance
        val book = requireNotNull(app.audiobookDao.getAudiobookById(id)).toAudiobookEntity()
        val playable = app.sourceCatalog.getPlayableChapters(id)
        fun local(index: Int) = playable[index].track?.localFilePath?.let { File(it).isFile } == true
        val from = (0 until playable.lastIndex).first { local(it) && !local(it + 1) }
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var vm: MainViewModel
            scenario.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                vm = ViewModelProvider(it)[MainViewModel::class.java]
                vm.playerManager.loadAndPlayBook(book, playable.map { it.chapter }, playable,
                    initialChapterIndex = from, initialPositionSeconds = 42)
                vm.setShowFullPlayer(true)
            }
            try {
                awaitState("Local chapter plays") { vm.playerState.value.isPlaying }
                val duration = vm.playerState.value.durationMs
                assertTrue("Real local chapter duration", duration > 5_000)
                scenario.onActivity { vm.playerManager.seekTo(duration - 1_500) }
                awaitState("Completion automatically starts the streamed chapter", 45_000) {
                    vm.playerState.value.let { it.isPlaying && it.currentChapterIndex == from + 1 }
                }
                assertEquals(id, vm.playerState.value.currentBook?.id)
                assertTrue(vm.playerState.value.currentPositionMs in 0..15_000)
                val start = vm.playerState.value.currentPositionMs
                awaitState("Stream after boundary advances") { vm.playerState.value.currentPositionMs > start + 1_000 }
                Log.i("LiveRecoveryTest", "automaticBoundary=passed from=$from to=${from + 1}")
            } finally { scenario.onActivity { vm.playerManager.pause() } }
        }
    }

    /** Run seed, force-stop the target package, then run verify in a new process. */
    @Test fun position_survives_process_restart(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("liveBookId")
        val phase = args.getString("liveRestartPhase")
        assumeTrue(!id.isNullOrBlank() && phase in listOf("seed", "verify"))
        requireNotNull(id)
        val app = App.instance
        val book = requireNotNull(app.audiobookDao.getAudiobookById(id)).toAudiobookEntity()
        val playable = app.sourceCatalog.getPlayableChapters(id)
        assertTrue("Pinned restart chapter is local", playable[0].track?.localFilePath?.let { File(it).isFile } == true)
        if (phase == "verify") {
            val saved = requireNotNull(app.listeningState.getProgressSync(id))
            assertEquals(0, saved.currentChapterIndex)
            assertTrue("Persisted position before any playback: $saved", saved.currentPositionSeconds in 89..95)
        }
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var vm: MainViewModel
            scenario.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                vm = ViewModelProvider(it)[MainViewModel::class.java]
                if (phase == "seed") vm.playerManager.loadAndPlayBook(book, playable.map { it.chapter }, playable,
                    initialChapterIndex = 0, initialPositionSeconds = 90)
                else vm.playAudiobook(book)
                vm.setShowFullPlayer(true)
            }
            try {
                awaitState("Restart phase plays", 45_000) { vm.playerState.value.isPlaying }
                assertEquals(id, vm.playerState.value.currentBook?.id)
                assertEquals(0, vm.playerState.value.currentChapterIndex)
                // Allow the documented smart rewind after the inter-process pause.
                assertTrue("Resume position", vm.playerState.value.currentPositionMs in 75_000..95_000)
                scenario.onActivity { vm.playerManager.pause() }
                if (phase == "seed") withTimeout(10_000) {
                    while ((app.listeningState.getProgressSync(id)?.currentPositionSeconds ?: -1L) !in 89L..95L) delay(100)
                }
                Log.i("LiveRecoveryTest", "restart=$phase passed pid=${android.os.Process.myPid()} position=${vm.playerState.value.currentPositionMs}")
            } finally { scenario.onActivity { vm.playerManager.pause() } }
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
