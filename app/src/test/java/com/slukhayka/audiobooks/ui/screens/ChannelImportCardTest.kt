package com.slukhayka.audiobooks.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.data.ingest.ChannelCardState
import com.slukhayka.audiobooks.data.ingest.ChannelImportSession
import com.slukhayka.audiobooks.data.ingest.ChannelItemKind
import com.slukhayka.audiobooks.data.ingest.ChannelListItem
import com.slukhayka.audiobooks.data.ingest.ChannelTab
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-53 T10 — the channel card shows engine rows honestly and every
 * control forwards to the ViewModel seam: ticks, tabs, «останні N», the
 * skipped row, progress with stop, and the resolved add count.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "uk-rUA")
class ChannelImportCardTest {

    @get:Rule
    val compose = createComposeRule()

    private fun video(id: String) = ChannelListItem(
        id, ChannelItemKind.VIDEO,
        "Відео $id", "https://www.youtube.com/watch?v=$id", durationSeconds = 65L
    )

    private fun playlist(id: String) = ChannelListItem(
        id, ChannelItemKind.PLAYLIST,
        "Плейлист $id", "https://www.youtube.com/playlist?list=$id"
    )

    private class Calls {
        var closed = 0
        var retried = 0
        val tabs = mutableListOf<ChannelTab>()
        var more = 0
        val toggled = mutableListOf<String>()
        val lastN = mutableListOf<Int>()
        var includeSkipped = 0
        var started = 0
        var stopped = 0

        fun callbacks() = ChannelCardCallbacks(
            onClose = { closed++ },
            onRetryLoad = { retried++ },
            onTabSelect = { tabs += it },
            onLoadMore = { more++ },
            onToggleItem = { toggled += it },
            onSelectLastN = { lastN += it },
            onToggleIncludeSkipped = { includeSkipped++ },
            onStartImport = { started++ },
            onStopImport = { stopped++ }
        )
    }

    private fun show(state: ChannelCardState, calls: Calls) {
        compose.setContent {
            AudiobookTheme {
                ChannelImportCard(state = state, callbacks = calls.callbacks())
            }
        }
    }

    private fun card(vararg items: ChannelListItem) = ChannelCardState(
        url = "https://www.youtube.com/@chan",
        title = "Канал",
        tab = ChannelTab.VIDEOS,
        items = items.toList(),
        hasMore = false
    )

    @Test
    fun `rows tick through the seam and the add count follows`() {
        val calls = Calls()
        var state by mutableStateOf(card(video("v111111"), playlist("PL1")))
        compose.setContent {
            AudiobookTheme {
                ChannelImportCard(
                    state = state,
                    callbacks = calls.callbacks().copy(
                        onToggleItem = {
                            calls.toggled += it
                            state = state.copy(
                                checkedIds = if (it in state.checkedIds) {
                                    state.checkedIds - it
                                } else {
                                    state.checkedIds + it
                                }
                            )
                        }
                    )
                )
            }
        }

        compose.onNodeWithTag("channel_add_selected").assertExists()
        compose.onNodeWithText("Додати вибрані (0)").assertExists()
        compose.onNodeWithTag("channel_item_v111111").performClick()
        compose.onNodeWithTag("channel_item_PL1").performClick()

        assertEquals(listOf("v111111", "PL1"), calls.toggled)
        compose.onNodeWithText("Додати вибрані (2)").assertExists()
    }

    @Test
    fun `the skipped row names the count and offers them back`() {
        val calls = Calls()
        show(
            ChannelCardState(
                url = "https://www.youtube.com/@chan",
                title = "Канал",
                tab = ChannelTab.VIDEOS,
                items = listOf(
                    playlist("PL1"),
                    video("aaa111"),
                    video("zzz999")
                ),
                checkedIds = setOf("PL1", "aaa111", "zzz999"),
                playlistMembers = mapOf(
                    "PL1" to setOf("https://www.youtube.com/watch?v=aaa111")
                )
            ),
            calls
        )

        compose.onNodeWithTag("channel_skipped").assertExists()
        compose.onNodeWithText("Пропущено 1 відео").assertExists()
        compose.onNodeWithText("Додати вибрані (2)").assertExists()
        compose.onNodeWithTag("channel_include_skipped").performClick()

        assertEquals(1, calls.includeSkipped)
    }

    @Test
    fun `tabs, more, last-N and close forward`() {
        val calls = Calls()
        show(card(video("v111111")).copy(hasMore = true), calls)

        compose.onNodeWithTag("channel_tab_playlists").performClick()
        compose.onNodeWithTag("channel_more").performClick()
        compose.onNodeWithTag("channel_lastn_10").performClick()
        compose.onNodeWithTag("channel_card_close").performClick()

        assertEquals(listOf(ChannelTab.PLAYLISTS), calls.tabs)
        assertEquals(1, calls.more)
        assertEquals(listOf(10), calls.lastN)
        assertEquals(1, calls.closed)
    }

    @Test
    fun `progress with stop replaces the add button while running`() {
        val calls = Calls()
        show(
            card(video("v111111")).copy(
                checkedIds = setOf("v111111"),
                running = true,
                progress = ChannelImportSession.Progress(1, 2, "Відео v111111")
            ),
            calls
        )

        compose.onNodeWithTag("channel_progress").assertExists()
        compose.onNodeWithText("1/2 · Відео v111111").assertExists()
        compose.onNodeWithTag("channel_stop").performClick()

        assertEquals(1, calls.stopped)
    }

    @Test
    fun `a finished run reports its honest count`() {
        val calls = Calls()
        show(
            card(video("v111111")).copy(doneAdded = 2, doneTotal = 3, doneStopped = false),
            calls
        )

        compose.onNodeWithText("Додано 2 з 3").assertExists()
    }

    @Test
    fun `loading state shows its spinner`() {
        show(ChannelCardState(url = "https://www.youtube.com/@chan", loading = true), Calls())

        compose.onNodeWithTag("channel_loading").assertExists()
    }

    @Test
    fun `failure state is honest with a retry door`() {
        val calls = Calls()
        show(
            ChannelCardState(url = "https://www.youtube.com/@chan", loadFailed = true),
            calls
        )

        compose.onNodeWithTag("channel_failed").assertExists()
        compose.onNodeWithText("Не вдалося прочитати канал.").assertExists()
        compose.onNodeWithTag("channel_retry").performClick()

        assertEquals(1, calls.retried)
    }
}
