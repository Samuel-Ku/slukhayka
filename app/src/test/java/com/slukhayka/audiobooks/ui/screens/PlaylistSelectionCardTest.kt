package com.slukhayka.audiobooks.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.data.ingest.ChannelImportSession
import com.slukhayka.audiobooks.data.ingest.ListenerSubmissionFlow
import com.slukhayka.audiobooks.data.ingest.PreviewRunState
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-53 T11 — the playlist selection card: every position picked by
 * default, one tap for the whole set, the one-book / separate-books choice,
 * and the honest progress/stop of a separate-books run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "uk-rUA")
class PlaylistSelectionCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val entries = listOf(
        ListenerSubmissionFlow.PreviewEntry("https://www.youtube.com/watch?v=a1", "Розділ 1", 600L),
        ListenerSubmissionFlow.PreviewEntry("https://www.youtube.com/watch?v=b2", "Розділ 2", 900L),
        ListenerSubmissionFlow.PreviewEntry("https://www.youtube.com/watch?v=c3", "Розділ 3", null)
    )

    private class Calls {
        val toggled = mutableListOf<String>()
        var selectAll = 0
        val modes = mutableListOf<Boolean>()
        var added: ListenerSubmissionFlow.PreviewEdits? = null
        var stopped = 0

        fun callbacks() = PlaylistSelectionCallbacks(
            onToggleEntry = { toggled += it },
            onSelectAll = { selectAll++ },
            onSetSeparateBooks = { modes += it },
            onAdd = { added = it },
            onStop = { stopped++ }
        )
    }

    private val edits = ListenerSubmissionFlow.PreviewEdits(title = "Виправлена книга")

    private fun show(state: PlaylistSelectionState, calls: Calls) {
        compose.setContent {
            AudiobookTheme {
                PlaylistSelectionCard(
                    entries = entries,
                    state = state,
                    edits = edits,
                    callbacks = calls.callbacks()
                )
            }
        }
    }

    @Test
    fun `every position starts picked and the add button counts them`() {
        val calls = Calls()
        show(
            PlaylistSelectionState(
                selected = entries.map { it.watchUrl }.toSet(),
                separateBooks = false
            ),
            calls
        )

        compose.onNodeWithText("Вибрано 3 з 3").assertExists()
        compose.onNodeWithText("Додати вибрані (3)").assertExists()
        compose.onNodeWithText("Розділ 1").assertExists()
    }

    @Test
    fun `ticking a position forwards its watch url`() {
        val calls = Calls()
        show(PlaylistSelectionState(selected = setOf(entries[0].watchUrl), separateBooks = false), calls)

        compose.onNodeWithTag("playlist_entry_${entries[1].watchUrl}").performClick()

        assertEquals(listOf(entries[1].watchUrl), calls.toggled)
    }

    @Test
    fun `select all and the mode toggle forward`() {
        val calls = Calls()
        show(PlaylistSelectionState(selected = emptySet(), separateBooks = false), calls)

        compose.onNodeWithTag("playlist_select_all").performClick()
        compose.onNodeWithTag("playlist_mode_separate_books").performClick()
        compose.onNodeWithTag("playlist_mode_one_book").performClick()

        assertEquals(1, calls.selectAll)
        assertEquals(listOf(true, false), calls.modes)
    }

    @Test
    fun `an empty selection cannot be added`() {
        val calls = Calls()
        show(PlaylistSelectionState(selected = emptySet(), separateBooks = false), calls)

        compose.onNodeWithText("Додати вибрані (0)").assertExists()
        compose.onNodeWithTag("submission_preview_add").performClick()

        assertEquals("the button is disabled, so nothing was forwarded", null, calls.added)
    }

    @Test
    fun `add forwards the current edits`() {
        val calls = Calls()
        show(PlaylistSelectionState(selected = entries.map { it.watchUrl }.toSet(), separateBooks = false), calls)

        compose.onNodeWithTag("submission_preview_add").performClick()

        assertEquals(edits, calls.added)
    }

    @Test
    fun `a running separate-books walk shows progress with stop`() {
        val calls = Calls()
        show(
            PlaylistSelectionState(
                selected = entries.map { it.watchUrl }.toSet(),
                separateBooks = true,
                run = PreviewRunState(
                    running = true,
                    progress = ChannelImportSession.Progress(1, 3, "Розділ 1")
                )
            ),
            calls
        )

        compose.onNodeWithTag("playlist_progress").assertExists()
        compose.onNodeWithText("1/3 · Розділ 1").assertExists()
        compose.onNodeWithTag("playlist_stop").performClick()

        assertEquals(1, calls.stopped)
    }

    @Test
    fun `a finished run reports its honest count`() {
        val calls = Calls()
        show(
            PlaylistSelectionState(
                selected = entries.map { it.watchUrl }.toSet(),
                separateBooks = true,
                run = PreviewRunState(running = false, added = 2, total = 3, stopped = true)
            ),
            calls
        )

        compose.onNodeWithText("Зупинено · додано 2 з 3").assertExists()
    }
}
