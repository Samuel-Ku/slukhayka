package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.downloads.DownloadQueueItem
import com.slukhayka.audiobooks.data.downloads.DownloadQueueStatus
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #899 — the download manager's body: the honest empty state, the statuses
 * each queue item shows, and that every control forwards exactly the book it
 * names. The destructive removals always ask first (wayfinder #28: removing
 * an offline copy never silently destroys files), while «Скасувати» stops the
 * queue and keeps the files.
 *
 * The window is pinned to a phone shape: the manager is a two-region screen
 * (a scrollable queue over the fixed storage tools) whose rows are taller
 * than Robolectric's tiny default surface, and the merged layout is the
 * production one — only the test viewport was too small.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "uk-rUA-w411dp-h891dp")
class DownloadManagerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun item(
        bookId: String,
        title: String = "Книга $bookId",
        author: String = "Автор",
        status: DownloadQueueStatus = DownloadQueueStatus.PAUSED,
        downloadedChapters: Int = 4,
        totalChapters: Int = 10,
        progress: Float = 0.4f,
        bytesOnDisk: Long = 350L * 1024 * 1024
    ) = DownloadQueueItem(
        bookId = bookId,
        title = title,
        author = author,
        status = status,
        downloadedChapters = downloadedChapters,
        totalChapters = totalChapters,
        progress = progress,
        bytesOnDisk = bytesOnDisk
    )

    @Test
    fun `empty queue explains honestly and offers no fabricated item`() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                TestSurface {
                    DownloadManagerPane(
                        items = emptyList(),
                        storageText = "0 МБ зайнято · 0 МБ вільно · 0 аудіокниг офлайн",
                        hasLocalBooks = false,
                        showDelete = false,
                        bookCount = 0,
                        bytes = 0L,
                        onPause = {},
                        onContinue = {},
                        onCancel = {},
                        onRemove = {},
                        onRemoveCompleted = {},
                        onRescan = {},
                        onDeleteAllConfirmed = {}
                    )
                }
            }
        }

        compose.onNodeWithText("Немає завантажень").assertIsDisplayed()
        compose.onNodeWithTag("download_queue_empty").assertIsDisplayed()
        compose.onNodeWithTag("download_queue_list").assertDoesNotExist()
        compose.onNodeWithTag("remove_completed_button").assertDoesNotExist()
        // The memory tools stay reachable in the empty state.
        compose.onNodeWithText("Пам'ять пристрою").assertIsDisplayed()
    }

    @Test
    fun `queue shows each existing state with its chapter and size detail`() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                TestSurface {
                    DownloadManagerPane(
                        items = listOf(
                            item("live", status = DownloadQueueStatus.DOWNLOADING, progress = 0.3f),
                            item("queued", status = DownloadQueueStatus.QUEUED),
                            item("paused", status = DownloadQueueStatus.PAUSED),
                            item("error", status = DownloadQueueStatus.ERROR, downloadedChapters = 2),
                            item("done", status = DownloadQueueStatus.DONE, downloadedChapters = 10, progress = 1f)
                        ),
                        storageText = "350 МБ зайнято · 8,0 ГБ вільно · 1 аудіокнига офлайн",
                        hasLocalBooks = false,
                        showDelete = false,
                        bookCount = 1,
                        bytes = 350L * 1024 * 1024,
                        onPause = {},
                        onContinue = {},
                        onCancel = {},
                        onRemove = {},
                        onRemoveCompleted = {},
                        onRescan = {},
                        onDeleteAllConfirmed = {}
                    )
                }
            }
        }

        val list = compose.onNodeWithTag("download_queue_list")
        fun assertStatus(bookId: String, label: String) {
            val tag = "download_queue_status_$bookId"
            list.performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsDisplayed().assertTextEquals(label)
        }
        assertStatus("live", "Завантажується")
        assertStatus("queued", "У черзі")
        assertStatus("paused", "Призупинено")
        assertStatus("error", "Помилка: потрібне оновлення джерела")
        assertStatus("done", "Готово")
        list.performScrollToNode(hasTestTag("download_queue_detail_live"))
        compose.onNodeWithTag("download_queue_detail_live")
            .assertIsDisplayed()
            .assertTextEquals("350 МБ · 4 з 10 розділів")
        compose.onNodeWithTag("download_queue_heading")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
    }

    @Test
    fun `a running download offers pause and forwards its book`() {
        val paused = mutableListOf<String>()
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                TestSurface {
                    DownloadManagerPane(
                        items = listOf(item("b1", status = DownloadQueueStatus.DOWNLOADING)),
                        storageText = "0 МБ зайнято · 0 МБ вільно · 0 аудіокниг офлайн",
                        hasLocalBooks = false,
                        showDelete = false,
                        bookCount = 0,
                        bytes = 0L,
                        onPause = { paused += it },
                        onContinue = {},
                        onCancel = {},
                        onRemove = {},
                        onRemoveCompleted = {},
                        onRescan = {},
                        onDeleteAllConfirmed = {}
                    )
                }
            }
        }

        compose.onNodeWithTag("download_pause_b1")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        // A running download is never offered «Продовжити».
        compose.onNodeWithTag("download_continue_b1").assertDoesNotExist()
        assertEquals(listOf("b1"), paused)
    }

    @Test
    fun `a queued or paused download offers continue and forwards its book`() {
        val continued = mutableListOf<String>()
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                TestSurface {
                    DownloadManagerPane(
                        items = listOf(
                            item("queued", status = DownloadQueueStatus.QUEUED),
                            item("paused", status = DownloadQueueStatus.PAUSED)
                        ),
                        storageText = "0 МБ зайнято · 0 МБ вільно · 0 аудіокниг офлайн",
                        hasLocalBooks = false,
                        showDelete = false,
                        bookCount = 0,
                        bytes = 0L,
                        onPause = {},
                        onContinue = { continued += it },
                        onCancel = {},
                        onRemove = {},
                        onRemoveCompleted = {},
                        onRescan = {},
                        onDeleteAllConfirmed = {}
                    )
                }
            }
        }

        val list = compose.onNodeWithTag("download_queue_list")
        list.performScrollToNode(hasTestTag("download_continue_queued"))
        compose.onNodeWithTag("download_continue_queued")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        list.performScrollToNode(hasTestTag("download_continue_paused"))
        compose.onNodeWithTag("download_continue_paused")
            .assertIsDisplayed()
            .performClick()
        // A stopped queue is never offered «Пауза».
        compose.onNodeWithTag("download_pause_queued").assertDoesNotExist()
        compose.onNodeWithTag("download_pause_paused").assertDoesNotExist()
        assertEquals(listOf("queued", "paused"), continued)
    }

    @Test
    fun `finished and failed downloads offer neither pause nor continue`() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                TestSurface {
                    DownloadManagerPane(
                        items = listOf(
                            item("done", status = DownloadQueueStatus.DONE, downloadedChapters = 10),
                            item("error", status = DownloadQueueStatus.ERROR)
                        ),
                        storageText = "0 МБ зайнято · 0 МБ вільно · 0 аудіокниг офлайн",
                        hasLocalBooks = false,
                        showDelete = false,
                        bookCount = 0,
                        bytes = 0L,
                        onPause = {},
                        onContinue = {},
                        onCancel = {},
                        onRemove = {},
                        onRemoveCompleted = {},
                        onRescan = {},
                        onDeleteAllConfirmed = {}
                    )
                }
            }
        }

        compose.onNodeWithTag("download_pause_done").assertDoesNotExist()
        compose.onNodeWithTag("download_continue_done").assertDoesNotExist()
        compose.onNodeWithTag("download_pause_error").assertDoesNotExist()
        compose.onNodeWithTag("download_continue_error").assertDoesNotExist()
        // There is nothing left to stop: «Скасувати» would be a dead button,
        // so only the (confirmed) removal stays available for both.
        compose.onNodeWithTag("download_cancel_done").assertDoesNotExist()
        compose.onNodeWithTag("download_cancel_error").assertDoesNotExist()
        compose.onNodeWithTag("download_remove_done").assertExists()
        compose.onNodeWithTag("download_remove_error").assertExists()
    }

    @Test
    fun `removing one book asks first and forwards its id on confirm`() {
        val removed = mutableListOf<String>()
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                TestSurface {
                    DownloadManagerPane(
                        items = listOf(item("b1", title = "Тіні забутих предків", status = DownloadQueueStatus.PAUSED)),
                        storageText = "0 МБ зайнято · 0 МБ вільно · 0 аудіокниг офлайн",
                        hasLocalBooks = false,
                        showDelete = false,
                        bookCount = 0,
                        bytes = 0L,
                        onPause = {},
                        onContinue = {},
                        onCancel = {},
                        onRemove = { removed += it },
                        onRemoveCompleted = {},
                        onRescan = {},
                        onDeleteAllConfirmed = {}
                    )
                }
            }
        }

        // An unfinished queue names the destructive action exactly.
        compose.onNodeWithTag("download_remove_b1")
            .assertIsDisplayed()
            .assertTextEquals("Видалити файли")
            .performClick()
        compose.onNodeWithTag("remove_download_dialog").assertIsDisplayed()
        compose.onNodeWithText("Видалити завантажені файли?").assertIsDisplayed()
        assertEquals(0, removed.size)
        compose.onNodeWithText("Завантажені файли «Тіні забутих предків» буде видалено з пристрою. Цю дію не можна скасувати.")
            .assertIsDisplayed()
        compose.onNodeWithTag("remove_download_confirm")
            .assertTextEquals("Видалити файли")
            .performClick()
        assertEquals(listOf("b1"), removed)
    }

    @Test
    fun `cancelling the removal changes nothing`() {
        var removed: String? = null
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                TestSurface {
                    DownloadManagerPane(
                        items = listOf(item("b1")),
                        storageText = "0 МБ зайнято · 0 МБ вільно · 0 аудіокниг офлайн",
                        hasLocalBooks = false,
                        showDelete = false,
                        bookCount = 0,
                        bytes = 0L,
                        onPause = {},
                        onContinue = {},
                        onCancel = {},
                        onRemove = { removed = it },
                        onRemoveCompleted = {},
                        onRescan = {},
                        onDeleteAllConfirmed = {}
                    )
                }
            }
        }

        compose.onNodeWithTag("download_remove_b1").performClick()
        compose.onNodeWithTag("remove_download_cancel").performClick()
        compose.onNodeWithTag("remove_download_dialog").assertDoesNotExist()
        assertNull(removed)
    }

    @Test
    fun `remove completed exists only with finished downloads and quotes their count`() {
        var removeCompletedCalls = 0
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                TestSurface {
                    DownloadManagerPane(
                        items = listOf(
                            item("done1", status = DownloadQueueStatus.DONE, downloadedChapters = 10),
                            item("done2", status = DownloadQueueStatus.DONE, downloadedChapters = 10),
                            item("paused", status = DownloadQueueStatus.PAUSED)
                        ),
                        storageText = "0 МБ зайнято · 0 МБ вільно · 0 аудіокниг офлайн",
                        hasLocalBooks = false,
                        showDelete = false,
                        bookCount = 0,
                        bytes = 0L,
                        onPause = {},
                        onContinue = {},
                        onCancel = {},
                        onRemove = {},
                        onRemoveCompleted = { removeCompletedCalls += 1 },
                        onRescan = {},
                        onDeleteAllConfirmed = {}
                    )
                }
            }
        }

        compose.onNodeWithTag("remove_completed_button")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithTag("remove_completed_dialog").assertIsDisplayed()
        compose.onNodeWithText("Прибрати 2 завершені завантаження? Завантажені файли буде видалено з пристрою.")
            .assertIsDisplayed()
        compose.onNodeWithTag("remove_completed_confirm").performClick()
        assertEquals(1, removeCompletedCalls)
    }

    @Test
    fun `remove completed is absent when nothing is finished`() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                TestSurface {
                    DownloadManagerPane(
                        items = listOf(item("paused", status = DownloadQueueStatus.PAUSED)),
                        storageText = "0 МБ зайнято · 0 МБ вільно · 0 аудіокниг офлайн",
                        hasLocalBooks = false,
                        showDelete = false,
                        bookCount = 0,
                        bytes = 0L,
                        onPause = {},
                        onContinue = {},
                        onCancel = {},
                        onRemove = {},
                        onRemoveCompleted = {},
                        onRescan = {},
                        onDeleteAllConfirmed = {}
                    )
                }
            }
        }

        compose.onNodeWithTag("remove_completed_button").assertDoesNotExist()
    }

    @Test
    fun `a running download offers pause, cancel and delete side by side`() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                TestSurface {
                    DownloadManagerPane(
                        items = listOf(item("b1", status = DownloadQueueStatus.DOWNLOADING)),
                        storageText = "0 МБ зайнято · 0 МБ вільно · 0 аудіокниг офлайн",
                        hasLocalBooks = false,
                        showDelete = false,
                        bookCount = 0,
                        bytes = 0L,
                        onPause = {},
                        onContinue = {},
                        onCancel = {},
                        onRemove = {},
                        onRemoveCompleted = {},
                        onRescan = {},
                        onDeleteAllConfirmed = {}
                    )
                }
            }
        }

        compose.onNodeWithTag("download_pause_b1").assertIsDisplayed()
        compose.onNodeWithTag("download_cancel_b1")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertTextEquals("Скасувати")
            // The one-word label must not hide which of the two it is.
            .assertContentDescriptionEquals(
                "Скасувати завантаження. Завантажені файли лишаться на пристрої."
            )
        compose.onNodeWithTag("download_remove_b1")
            .assertIsDisplayed()
            .assertTextEquals("Видалити файли")
    }

    @Test
    fun `cancel stops the download without asking and never deletes`() {
        val cancelled = mutableListOf<String>()
        val removed = mutableListOf<String>()
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                TestSurface {
                    DownloadManagerPane(
                        items = listOf(
                            item("running", status = DownloadQueueStatus.DOWNLOADING),
                            item("queued", status = DownloadQueueStatus.QUEUED),
                            item("paused", status = DownloadQueueStatus.PAUSED)
                        ),
                        storageText = "0 МБ зайнято · 0 МБ вільно · 0 аудіокниг офлайн",
                        hasLocalBooks = false,
                        showDelete = false,
                        bookCount = 0,
                        bytes = 0L,
                        onPause = {},
                        onContinue = {},
                        onCancel = { cancelled += it },
                        onRemove = { removed += it },
                        onRemoveCompleted = {},
                        onRescan = {},
                        onDeleteAllConfirmed = {}
                    )
                }
            }
        }

        val list = compose.onNodeWithTag("download_queue_list")
        listOf("running", "queued", "paused").forEach { bookId ->
            list.performScrollToNode(hasTestTag("download_cancel_$bookId"))
            compose.onNodeWithTag("download_cancel_$bookId")
                .assertIsDisplayed()
                .performClick()
        }

        // Every unfinished queue can be stopped, and stopping is NOT a
        // destructive action: no confirmation, no delete callback.
        assertEquals(listOf("running", "queued", "paused"), cancelled)
        assertEquals(emptyList<String>(), removed)
        compose.onNodeWithTag("remove_download_dialog").assertDoesNotExist()
    }

    @Test
    fun `a finished download keeps the single remove action`() {
        val removed = mutableListOf<String>()
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                TestSurface {
                    DownloadManagerPane(
                        items = listOf(
                            item("done", status = DownloadQueueStatus.DONE, downloadedChapters = 10, progress = 1f)
                        ),
                        storageText = "0 МБ зайнято · 0 МБ вільно · 1 аудіокнига офлайн",
                        hasLocalBooks = false,
                        showDelete = true,
                        bookCount = 1,
                        bytes = 350L * 1024 * 1024,
                        onPause = {},
                        onContinue = {},
                        onCancel = {},
                        onRemove = { removed += it },
                        onRemoveCompleted = {},
                        onRescan = {},
                        onDeleteAllConfirmed = {}
                    )
                }
            }
        }

        // No partial files exist, so there is nothing to «Скасувати».
        compose.onNodeWithTag("download_cancel_done").assertDoesNotExist()
        compose.onNodeWithTag("download_remove_done")
            .assertIsDisplayed()
            .assertTextEquals("Прибрати")
            .performClick()
        // Deletion still asks first, and the dialog keeps the label the
        // listener just pressed.
        compose.onNodeWithText("Прибрати завантаження?").assertIsDisplayed()
        assertEquals(0, removed.size)
        compose.onNodeWithTag("remove_download_confirm")
            .assertTextEquals("Прибрати")
            .performClick()
        assertEquals(listOf("done"), removed)
    }

    @Test
    fun `the delete confirmation can be refused with a label that does not read as cancel`() {
        val removed = mutableListOf<String>()
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                TestSurface {
                    DownloadManagerPane(
                        items = listOf(item("b1", status = DownloadQueueStatus.PAUSED)),
                        storageText = "0 МБ зайнято · 0 МБ вільно · 0 аудіокниг офлайн",
                        hasLocalBooks = false,
                        showDelete = false,
                        bookCount = 0,
                        bytes = 0L,
                        onPause = {},
                        onContinue = {},
                        onCancel = {},
                        onRemove = { removed += it },
                        onRemoveCompleted = {},
                        onRescan = {},
                        onDeleteAllConfirmed = {}
                    )
                }
            }
        }

        compose.onNodeWithTag("download_remove_b1").performClick()
        // «Скасувати» on the row stops a download; the dialog's refusal must
        // not reuse that word for "do not delete my files".
        compose.onNodeWithTag("remove_download_cancel")
            .assertTextEquals("Не видаляти")
            .performClick()
        compose.onNodeWithTag("remove_download_dialog").assertDoesNotExist()
        assertEquals(emptyList<String>(), removed)
    }
}

/**
 * A bounded phone-shaped surface: the queue owns the flexible space and the
 * storage pane keeps its tools at the bottom, so the seam needs a real size.
 */
@Composable
private fun TestSurface(content: @Composable () -> Unit) {
    Box(modifier = Modifier.width(400.dp).height(900.dp)) {
        content()
    }
}
