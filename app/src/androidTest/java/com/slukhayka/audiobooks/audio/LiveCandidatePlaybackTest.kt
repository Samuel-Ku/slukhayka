package com.slukhayka.audiobooks.audio

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.ui.MainViewModel
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Explicit device smoke test. Uses an existing book and restores its chapter/position. */
class LiveCandidatePlaybackTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun playPauseSeekAndChapterChangeRestorePosition() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePlayback") == "true")
        val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        rule.waitUntil(20_000) { vm.libraryBooks.value.isNotEmpty() }
        val initial = vm.playerState.value
        val book = initial.currentBook ?: vm.libraryBooks.value.first { it.book.title == "Проблема з миром" }.book
        val saved = vm.libraryBooks.value.first { it.book.id == book.id }.progress
        val chapter = if (initial.currentBook?.id == book.id) initial.currentChapterIndex else saved?.currentChapterIndex ?: 0
        val position = if (initial.currentBook?.id == book.id) initial.currentPositionMs else (saved?.currentPositionSeconds ?: 0) * 1000
        val bookIds = vm.libraryBooks.value.map { it.book.id }.toSet()
        val dao = App.instance.audiobookDao
        val bookmarks = runBlocking { dao.getAllBookmarks().first() }
        val files = runBlocking { bookIds.flatMap { dao.getTracksForBookSync(it) } }.mapNotNull { it.localFilePath }
            .distinct().map(::File).filter { it.isFile }.associateWith { it.length() }
        try {
            rule.runOnUiThread {
                if (initial.currentBook?.id == book.id && initial.chapters.isNotEmpty()) {
                    vm.playerManager.pause()
                    vm.playerManager.prepareChapter(chapter, position, autoPlay = false)
                } else vm.playAudiobook(book, chapterIndex = chapter, autoPlay = false)
                vm.setShowFullPlayer(true)
            }
            rule.waitUntil(45_000) {
                vm.playerState.value.let { it.currentBook?.id == book.id && !it.isBuffering && it.durationMs > 0 }
            }
            rule.onNodeWithTag("player_play_pause_button").assertIsDisplayed().performTouchInput { click() }
            rule.waitUntil(30_000) { vm.playerState.value.isPlaying && !vm.playerState.value.isBuffering }
            val start = vm.playerState.value.currentPositionMs
            rule.waitUntil(10_000) { vm.playerState.value.currentPositionMs >= start + 1500 }
            Log.i("LiveCandidate", "AUDIO_STARTED title=${book.title}; chapter=$chapter; position=${vm.playerState.value.currentPositionMs}")
            // Audition window for the listener; no device volume or routing changes.
            SystemClock.sleep(30_000)
            rule.onNodeWithTag("player_play_pause_button").performTouchInput { click() }
            rule.waitUntil(10_000) { !vm.playerState.value.isPlaying }
            val paused = vm.playerState.value.currentPositionMs
            SystemClock.sleep(1500)
            assertTrue("Position advances while paused", kotlin.math.abs(vm.playerState.value.currentPositionMs - paused) < 1000)
            val target = (paused + 15_000).coerceAtMost(vm.playerState.value.durationMs - 1000).coerceAtLeast(0)
            rule.runOnUiThread { vm.playerManager.seekTo(target, recordInHistory = false) }
            rule.waitUntil(10_000) { kotlin.math.abs(vm.playerState.value.currentPositionMs - target) < 1500 }
            assertTrue("Book needs at least two chapters", vm.playerState.value.chapters.size > 1)
            val next = if (chapter + 1 < vm.playerState.value.chapters.size) chapter + 1 else chapter - 1
            rule.runOnUiThread { vm.playerManager.prepareChapter(next, 0, autoPlay = true) }
            rule.waitUntil(30_000) {
                vm.playerState.value.let { it.currentChapterIndex == next && it.isPlaying && !it.isBuffering && it.currentPositionMs > 1000 }
            }
            assertTrue("Playback reports an error", vm.playerState.value.lastErrorMsg.isBlank())
            Log.i("LiveCandidate", "PASSED play,pause,seek,chapter=$next")
        } finally {
            rule.runOnUiThread {
                vm.playerManager.pause()
                vm.playerManager.prepareChapter(chapter, position, autoPlay = false)
            }
            rule.waitUntil(30_000) {
                vm.playerState.value.let {
                    it.currentBook?.id == book.id && it.currentChapterIndex == chapter && !it.isBuffering &&
                        it.lastErrorMsg.isBlank() && it.durationMs > 0 && kotlin.math.abs(it.currentPositionMs - position) < 1500
                }
            }
            rule.runOnUiThread {
                vm.playerManager.pause()
                vm.setShowFullPlayer(false)
            }
            rule.waitUntil(15_000) {
                vm.libraryBooks.value.firstOrNull { it.book.id == book.id }?.progress?.let {
                    it.currentChapterIndex == chapter && kotlin.math.abs(it.currentPositionSeconds * 1000 - position) < 1500
                } == true
            }
            assertTrue("A library book disappeared", vm.libraryBooks.value.map { it.book.id }.containsAll(bookIds))
            assertEquals(bookmarks, runBlocking { dao.getAllBookmarks().first() })
            assertTrue("A downloaded file changed", files.all { (file, size) -> file.isFile && file.length() == size })
            rule.runOnUiThread { if (initial.isPlaying) vm.playerManager.play() }
            Log.i("LiveCandidate", "POSITION_RESTORED chapter=$chapter position=$position; books,bookmarks,files preserved; playback history updated")
        }
    }
}
