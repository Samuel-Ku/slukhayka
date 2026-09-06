package com.slukhayka.audiobooks.audio

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.MainViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in real two-book interaction; never presses download/delete or writes fixtures to Room. */
class LiveBookDetailActionsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun viewedBookAndMiniPlayerKeepTheirOwnPlaybackActions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePlayback") == "true")
        val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        rule.waitUntil(20_000) { vm.libraryBooks.value.any { it.book.title == "Трохи ненависті" } }
        val library = vm.libraryBooks.value
        val viewed = library.first { it.book.title == "Трохи ненависті" }.book
        val other = library.first { it.book.title == "Проблема з миром" }.book
        val initial = vm.playerState.value
        val initialSelected = vm.selectedBookId.value
        val books = (listOf(viewed, other) + listOfNotNull(initial.currentBook)).distinctBy { it.id }
        val positions = books.associate { book ->
            val progress = library.first { it.book.id == book.id }.progress
            book.id to if (initial.currentBook?.id == book.id) initial.currentChapterIndex to initial.currentPositionMs
            else (progress?.currentChapterIndex ?: 0) to ((progress?.currentPositionSeconds ?: 0) * 1000L)
        }
        val dao = App.instance.audiobookDao
        val bookmarks = runBlocking { dao.getAllBookmarks().first() }
        val files = runBlocking { books.flatMap { dao.getTracksForBookSync(it.id) } }
            .mapNotNull { it.localFilePath }.distinct().map(::File).filter { it.isFile }.associateWith { it.length() }
        fun prepare(book: AudiobookEntity, chapter: Int, position: Long) {
            rule.runOnUiThread { vm.playerManager.pause(); vm.playAudiobook(book, chapterIndex = chapter, autoPlay = false) }
            rule.waitUntil(45_000) { vm.playerState.value.let { it.currentBook?.id == book.id && !it.isBuffering && it.durationMs > 0 } }
            rule.runOnUiThread { vm.playerManager.prepareChapter(chapter, position, autoPlay = false) }
            rule.waitUntil(30_000) { vm.playerState.value.let { !it.isBuffering && !it.isPlaying && kotlin.math.abs(it.currentPositionMs - position) < 1500 } }
        }
        fun playing(book: AudiobookEntity) {
            rule.waitUntil(30_000) { vm.playerState.value.let { it.currentBook?.id == book.id && it.isPlaying && !it.isBuffering } }
            val position = vm.playerState.value.currentPositionMs
            rule.waitUntil(10_000) { vm.playerState.value.currentPositionMs > position + 1200 }
            assertTrue(vm.playerState.value.lastErrorMsg.isBlank())
        }
        try {
            rule.runOnUiThread {
                vm.playerManager.pause()
                vm.playerManager.stopAndClear()
                vm.setShowFullPlayer(false)
                vm.selectBook(viewed.id)
            }
            rule.onNodeWithTag("book_detail_title").assertExists()
            rule.onNodeWithTag("mini_player_bar").assertDoesNotExist()
            assertNull(vm.playerState.value.currentBook)
            prepare(other, positions.getValue(other.id).first, positions.getValue(other.id).second)
            rule.runOnUiThread { vm.selectBook(null); vm.selectBook(viewed.id) }
            rule.waitForIdle()
            assertEquals(other.id, vm.playerState.value.currentBook?.id)
            assertFalse(vm.playerState.value.isPlaying)
            rule.onNodeWithTag("mini_player_summary").assertTextContains(other.title)
                .assertTextContains("У плеєрі", substring = true)
            rule.onNodeWithTag("mini_player_play_pause").performTouchInput { click() }
            playing(other)
            val positionBeforeNavigation = vm.playerState.value.currentPositionMs
            rule.onNodeWithTag("book_detail_back_button").performTouchInput { click() }
            rule.runOnUiThread { vm.selectBook(viewed.id) }
            rule.waitForIdle()
            assertEquals(other.id, vm.playerState.value.currentBook?.id)
            assertTrue("navigation must keep audio playing", vm.playerState.value.isPlaying)
            assertTrue("navigation must not rewind the other book", vm.playerState.value.currentPositionMs >= positionBeforeNavigation - 250)
            rule.onNodeWithTag("mini_player_play_pause").performTouchInput { click() }
            rule.waitUntil(10_000) { !vm.playerState.value.isPlaying }
            rule.onNodeWithTag("play_book_button").performScrollTo().assertIsDisplayed()
            val play = rule.onNodeWithTag("play_book_button").getUnclippedBoundsInRoot()
            val description = rule.onNodeWithTag("book_detail_description").getUnclippedBoundsInRoot()
            assertTrue("book action should precede its description", play.bottom <= description.top)
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(rule.activity.getExternalFilesDir(null), "554-different-books.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            val scroll = rule.onNodeWithTag("book_detail_screen")
            repeat(12) {
                if (rule.onNodeWithTag("book_detail_description").getUnclippedBoundsInRoot().bottom > rule.onNodeWithTag("mini_player_bar").getUnclippedBoundsInRoot().top) {
                    scroll.performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.3f) }
                }
            }
            assertTrue("description end must scroll above the other book's mini-player",
                rule.onNodeWithTag("book_detail_description").getUnclippedBoundsInRoot().bottom <= rule.onNodeWithTag("mini_player_bar").getUnclippedBoundsInRoot().top)
            val descriptionScreenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(rule.activity.getExternalFilesDir(null), "554-description-above-mini.png").outputStream().use { descriptionScreenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            descriptionScreenshot.recycle()
            rule.onNodeWithTag("play_book_button").performScrollTo().performTouchInput { click() }
            playing(viewed)
            val saved = positions.getValue(viewed.id)
            assertEquals(saved.first, vm.playerState.value.currentChapterIndex)
            assertTrue("opened book should resume near its own position", kotlin.math.abs(vm.playerState.value.currentPositionMs - saved.second) < 15_000)
            rule.runOnUiThread { vm.setShowFullPlayer(false) }
            rule.onNodeWithTag("mini_player_summary").assertTextContains(viewed.title)
            rule.onNodeWithTag("play_book_button").performScrollTo().performTouchInput { click() }
            rule.waitUntil(10_000) { !vm.playerState.value.isPlaying }
            Log.i("LiveBookActions", "PASSED: no session, other book mini play/pause, viewed book resume/pause, actions before description")
        } finally {
            val restorationFailures = mutableListOf<Throwable>()
            fun restoreAttempt(action: () -> Unit) {
                try { action() } catch (failure: Throwable) { restorationFailures += failure }
            }
            for (book in books) restoreAttempt {
                val (chapter, position) = positions.getValue(book.id)
                prepare(book, chapter, position)
                rule.runOnUiThread { vm.playerManager.pause() }
                rule.waitUntil(15_000) { vm.libraryBooks.value.first { it.book.id == book.id }.progress?.let {
                    it.currentChapterIndex == chapter && kotlin.math.abs(it.currentPositionSeconds * 1000 - position) < 1500
                } == true }
            }
            restoreAttempt {
                initial.currentBook?.let { book -> prepare(book, initial.currentChapterIndex, initial.currentPositionMs) }
            }
            restoreAttempt {
                rule.runOnUiThread {
                    if (initial.currentBook == null) vm.playerManager.stopAndClear()
                    else if (initial.isPlaying && vm.playerState.value.currentBook?.id == initial.currentBook.id) vm.playerManager.play()
                    vm.setShowFullPlayer(false)
                    vm.selectBook(initialSelected)
                }
            }
            restoreAttempt { assertTrue(vm.libraryBooks.value.map { it.book.id }.containsAll(library.map { it.book.id })) }
            restoreAttempt { assertEquals(bookmarks, runBlocking { dao.getAllBookmarks().first() }) }
            restoreAttempt { assertTrue(files.all { (file, size) -> file.isFile && file.length() == size }) }
            if (restorationFailures.isNotEmpty()) {
                throw AssertionError("${restorationFailures.size} restoration checks failed; all were attempted").apply {
                    restorationFailures.forEach(::addSuppressed)
                }
            }
            Log.i("LiveBookActions", "RESTORED: both positions; library, bookmarks and files preserved; ordinary playback history updated")
        }
    }
}
