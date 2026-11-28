package com.slukhayka.audiobooks.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.data.ingest.ChannelImportSession
import com.slukhayka.audiobooks.data.ingest.ListenerSubmissionFlow
import com.slukhayka.audiobooks.data.ingest.PreviewRunState
import com.slukhayka.audiobooks.testing.assertNamedCheckboxRow
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
        compose.onNodeWithTag("submission_preview_add").assertTextEquals("Додати одну книжку з 3 розділами")
        compose.onNodeWithText("Розділ 1").assertExists()
    }

    @Test
    fun `confirmation follows the mode and checked entries after reordering`() {
        var orderedEntries by mutableStateOf((1..8).map { number ->
            ListenerSubmissionFlow.PreviewEntry("https://www.youtube.com/watch?v=$number", "Розділ $number")
        })
        val first = orderedEntries.first().watchUrl
        var state by mutableStateOf(PlaylistSelectionState(
            selected = orderedEntries.map { it.watchUrl }.toSet(),
            separateBooks = false
        ))
        compose.setContent {
            AudiobookTheme {
                PlaylistSelectionCard(
                    entries = orderedEntries,
                    state = state,
                    edits = edits,
                    callbacks = Calls().callbacks().copy(
                        onMoveEntry = { url, delta ->
                            val from = orderedEntries.indexOfFirst { it.watchUrl == url }
                            orderedEntries = orderedEntries.toMutableList().apply {
                                add(from + delta, removeAt(from))
                            }
                        },
                        onToggleEntry = { url -> state = state.copy(selected = state.selected - url) },
                        onSetSeparateBooks = { state = state.copy(separateBooks = it) }
                    )
                )
            }
        }

        val confirm = compose.onNodeWithTag("submission_preview_add")
        confirm.assertTextEquals("Додати одну книжку з 8 розділами")
        compose.onNodeWithTag("playlist_mode_separate_books").performClick()
        confirm.assertTextEquals("Додати 8 книжок")
        compose.onNodeWithTag("playlist_entry_down_$first").performClick()
        confirm.assertTextEquals("Додати 8 книжок")
        compose.onNodeWithTag("playlist_entry_$first").performClick()
        confirm.assertTextEquals("Додати 7 книжок")
        compose.onNodeWithTag("playlist_mode_one_book").performClick()
        confirm.assertTextEquals("Додати одну книжку з 7 розділами")
    }

    @Test
    fun `ticking a position forwards its watch url`() {
        val calls = Calls()
        show(PlaylistSelectionState(selected = setOf(entries[0].watchUrl), separateBooks = false), calls)

        compose.onNodeWithTag("playlist_entry_${entries[1].watchUrl}").performClick()

        assertEquals(listOf(entries[1].watchUrl), calls.toggled)
    }

    @Test
    fun `a position is one named checkbox and tapping its label toggles it`() {
        val calls = Calls()
        var state by mutableStateOf(
            PlaylistSelectionState(selected = setOf(entries[0].watchUrl), separateBooks = false)
        )
        compose.setContent {
            AudiobookTheme {
                PlaylistSelectionCard(
                    entries = entries,
                    state = state,
                    edits = edits,
                    callbacks = calls.callbacks().copy(
                        onToggleEntry = { url ->
                            calls.toggled += url
                            state = state.copy(
                                selected = if (url in state.selected) {
                                    state.selected - url
                                } else {
                                    state.selected + url
                                }
                            )
                        }
                    )
                )
            }
        }

        val url = entries[1].watchUrl
        compose.assertNamedCheckboxRow("playlist_entry_$url", "Розділ 2")
        compose.onNodeWithTag("playlist_entry_$url").assertIsOff()

        compose.onNodeWithText("Розділ 2").performClick()

        assertEquals(listOf(url), calls.toggled)
        compose.onNodeWithTag("playlist_entry_$url").assertIsOn()
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

        compose.onNodeWithTag("submission_preview_add").assertIsNotEnabled()
        compose.onNodeWithTag("submission_preview_add").assertTextEquals("Виберіть записи")
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
