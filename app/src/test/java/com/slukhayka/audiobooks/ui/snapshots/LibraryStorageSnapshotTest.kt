package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.ui.screens.LibraryImportSheetContent
import com.slukhayka.audiobooks.ui.screens.StorageDestinationContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * spec-28 (#194) — snapshot pins for the Медіатека chrome collapse: the
 * «+ Додати» import sheet (files / folder) and the «Завантаження та пам'ять»
 * destination (storage card, rescan, danger zone with the destructive delete).
 * Pure `@Composable` inputs — the sheet and destination bodies are stateless,
 * so no `MainViewModel` is needed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class LibraryStorageSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // spec-28 #194 AC: one «+ Додати» action opens a sheet with the import
    // options (files / folder) instead of two competing buttons.
    @Test
    fun import_sheet_options() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                // The sheet body rendered on the ModalBottomSheet container
                // colour, so the snapshot is faithful to the real sheet.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    LibraryImportSheetContent(onImportFile = {}, onImportFolder = {})
                }
            }
        }

        composeTestRule.onNodeWithText("Додати файл").assertExists()
        composeTestRule.onNodeWithText("Додати папку").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/library_import_sheet.png"
        )
    }

    @Test
    fun import_sheet_options_forward_their_choice() {
        var fileClicked = false
        var folderClicked = false
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    LibraryImportSheetContent(
                        onImportFile = { fileClicked = true },
                        onImportFolder = { folderClicked = true }
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Додати файл").performClick()
        composeTestRule.onNodeWithText("Додати папку").performClick()
        assertEquals(true, fileClicked)
        assertEquals(true, folderClicked)
    }

    // spec-28 #194: the destination with downloads + local books — the
    // storage card, the rescan action and the destructive delete, each in
    // its own section (a destructive action never sits next to neutral data).
    @Test
    fun storage_destination_populated() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                StorageSurface {
                    StorageDestinationContent(
                        storageText = "1,2 ГБ · 5 аудіокниг offline",
                        hasLocalBooks = true,
                        showDelete = true,
                        onRescan = {},
                        onDeleteClick = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Пам'ять пристрою").assertExists()
        composeTestRule.onNodeWithText("Видалити завантажені файли").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/storage_destination.png"
        )
    }

    // spec-28 #194 AC: nothing downloaded → no destructive button at all,
    // just the storage line (the delete button only appears when there IS
    // something to delete).
    @Test
    fun storage_destination_nothing_to_delete() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                StorageSurface {
                    StorageDestinationContent(
                        storageText = "0 Б · 0 аудіокниг offline",
                        hasLocalBooks = false,
                        showDelete = false,
                        onRescan = {},
                        onDeleteClick = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Видалити завантажені файли").assertDoesNotExist()
        composeTestRule.onNodeWithText("Пам'ять пристрою").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/storage_destination_empty.png"
        )
    }

    // Spec-27 (#184) BUG-001: the delete button requests the confirmation
    // dialog (which quotes count + size) — it never deletes directly. The
    // dialog itself is pinned by LibraryComponentsSnapshotTest.
    @Test
    fun storage_destination_delete_requests_confirmation() {
        var deleteClicked = false
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                StorageSurface {
                    StorageDestinationContent(
                        storageText = "1,2 ГБ · 5 аудіокниг offline",
                        hasLocalBooks = false,
                        showDelete = true,
                        onRescan = {},
                        onDeleteClick = { deleteClicked = true }
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Видалити завантажені файли").performClick()
        assertEquals(true, deleteClicked)
    }
}

/** Same chrome as the other snapshot seams: scheme background, full size. */
@Composable
private fun StorageSurface(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        content()
    }
}
