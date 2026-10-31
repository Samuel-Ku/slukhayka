package com.slukhayka.audiobooks.ui

import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.testing.TestDataFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #1705 — the composition root asks only whether a book is present. The derived
 * flow must ignore the 1 Hz position tick and follow the book's presence, so
 * the root cannot be invalidated by playback progress.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainViewModelHasPlayerBookTest {

    private lateinit var app: App
    private lateinit var viewModel: MainViewModel
    private lateinit var collector: Job
    private val observed = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        viewModel = MainViewModel(app)
        collector = viewModel.viewModelScope.launch {
            viewModel.hasPlayerBook.collect { observed += it }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        collector.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `a position tick does not change hasPlayerBook`() {
        val book = TestDataFactory.dataBooks().first()
        app.playerManager.mirrorCastState {
            it.copy(
                currentBook = book,
                chapters = TestDataFactory.dataChapters(listOf(book)),
                currentPositionMs = 0L
            )
        }
        idle()

        app.playerManager.mirrorCastState { it.copy(currentPositionMs = 1_000L) }
        idle()
        app.playerManager.mirrorCastState { it.copy(currentPositionMs = 2_000L) }
        idle()

        assertEquals(listOf(false, true), observed)
    }

    @Test
    fun `hasPlayerBook follows the book presence and nothing else`() {
        val book = TestDataFactory.dataBooks().first()
        app.playerManager.mirrorCastState {
            it.copy(
                currentBook = book,
                chapters = TestDataFactory.dataChapters(listOf(book)),
                isPlaying = true
            )
        }
        idle()
        app.playerManager.mirrorCastState { it.copy(isPlaying = false) }
        idle()
        app.playerManager.mirrorCastState { it.copy(currentBook = null, chapters = emptyList()) }
        idle()

        assertEquals(listOf(false, true, false), observed)
    }
}
