package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.data.downloads.DownloadQueueItem
import com.slukhayka.audiobooks.data.downloads.DownloadQueueStatus
import com.slukhayka.audiobooks.ui.screens.DownloadManagerPane
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #899 — snapshot pins for the download manager destination: the populated
 * queue with its states, sizes and controls, and the honest empty state with
 * the memory tools still reachable. The pane is a pure composable seam, so a
 * fixture list is the whole input.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class DownloadManagerSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun item(
        bookId: String,
        title: String,
        author: String,
        status: DownloadQueueStatus,
        downloaded: Int,
        total: Int,
        progress: Float,
        bytes: Long
    ) = DownloadQueueItem(
        bookId = bookId,
        title = title,
        author = author,
        status = status,
        downloadedChapters = downloaded,
        totalChapters = total,
        progress = progress,
        bytesOnDisk = bytes
    )

    @Test
    fun download_manager_populated() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ManagerSurface {
                    DownloadManagerPane(
                        items = listOf(
                            item(
                                "b1", "Тіні забутих предків", "Михайло Коцюбинський",
                                DownloadQueueStatus.DOWNLOADING, 7, 20, 0.35f, 412L * 1024 * 1024
                            ),
                            item(
                                "b2", "Місто", "Валер'ян Підмогильний",
                                DownloadQueueStatus.QUEUED, 3, 18, 0.16f, 180L * 1024 * 1024
                            ),
                            item(
                                "b3", "Захар Беркут", "Іван Франко",
                                DownloadQueueStatus.PAUSED, 5, 12, 0.41f, 224L * 1024 * 1024
                            ),
                            item(
                                "b4", "Кайдашева сім'я", "Іван Нечуй-Левицький",
                                DownloadQueueStatus.ERROR, 2, 14, 0.14f, 96L * 1024 * 1024
                            ),
                            item(
                                "b5", "Лісова пісня", "Леся Українка",
                                DownloadQueueStatus.DONE, 9, 9, 1f, 305L * 1024 * 1024
                            )
                        ),
                        storageText = "1,2 ГБ зайнято · 42,6 ГБ вільно · 1 аудіокнига офлайн",
                        hasLocalBooks = false,
                        showDelete = true,
                        bookCount = 1,
                        bytes = 1_200_000_000L,
                        onPause = {},
                        onContinue = {},
                        onRemove = {},
                        onRemoveCompleted = {},
                        onRescan = {},
                        onDeleteAllConfirmed = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Черга завантажень").assertExists()
        composeTestRule.onNodeWithText("Прибрати завершені").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/download_manager_populated.png"
        )
    }

    @Test
    fun download_manager_empty() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ManagerSurface {
                    DownloadManagerPane(
                        items = emptyList(),
                        storageText = "0 МБ зайнято · 42,6 ГБ вільно · 0 аудіокниг офлайн",
                        hasLocalBooks = false,
                        showDelete = false,
                        bookCount = 0,
                        bytes = 0L,
                        onPause = {},
                        onContinue = {},
                        onRemove = {},
                        onRemoveCompleted = {},
                        onRescan = {},
                        onDeleteAllConfirmed = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Немає завантажень").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/download_manager_empty.png"
        )
    }
}

/** Same chrome as the other snapshot seams: scheme background, full size. */
@Composable
private fun ManagerSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        content()
    }
}
