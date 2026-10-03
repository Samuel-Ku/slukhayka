package com.slukhayka.audiobooks.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.slukhayka.audiobooks.data.imports.*
import com.slukhayka.audiobooks.ui.screens.ImportPreviewDialog
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream

/** Interaction uses Robolectric’s default density; Pixel 8 rendering is covered by ImportCorrectionsTest. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [36], qualifiers = "uk-rUA")
class ImportCorrectionsFlowTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `preview edits all metadata and splits without reading or importing audio`() {
        var streamReads = 0
        val files = listOf("01.mp3", "02.mp3").map { name -> LocalAudioEntry(name, "Кобзар") { streamReads++; ByteArrayInputStream(byteArrayOf(1)) } }
        var plan by mutableStateOf(ImportPlanner.buildPlan(SourceRef.Folder("content://tree"), files))
        var confirmed: ImportPlan? = null
        compose.setContent {
            AudiobookTheme {
                ImportPreviewDialog(
                    preview = MainViewModel.ImportPreviewState(plan, "content://tree"),
                    onAcceptMerge = {}, onRejectMerge = {}, onReorderChapters = { id, order -> plan = ImportPlanner.reorderChapters(plan, id, order) },
                    onEditBookTitle = { _, _ -> }, onConfirm = { confirmed = plan }, onDismiss = {},
                    onSplitBook = { id, index -> plan = ImportPlanner.splitBook(plan, id, index) },
                    onEditBookMetadata = { id, book -> plan = ImportPlanner.editBook(plan, id, book.title, book.author, book.narrator, book.seriesTitle.orEmpty(), book.seriesIndex, clearSeriesIndex = true) }
                )
            }
        }
        compose.onNodeWithTag("import_preview_rename_folder:Кобзар").performScrollTo().performClick()
        compose.onNodeWithTag("metadata_correction_dialog").assertExists()
        compose.onNodeWithTag("metadata_edit_title").performTextReplacement("Поезії")
        compose.onNodeWithTag("metadata_edit_author").performTextReplacement("Тарас Шевченко")
        compose.onNodeWithTag("metadata_edit_narrator").performScrollTo().performTextReplacement("Диктор")
        compose.onNodeWithTag("metadata_edit_series").performScrollTo().performTextReplacement("Збірки")
        compose.onNodeWithTag("metadata_edit_series_index").performScrollTo().performTextReplacement("2")
        compose.onNodeWithTag("metadata_edit_save").performClick()
        compose.onNodeWithTag("import_preview_rename_folder:Кобзар").assertIsFocused()
        val edited = plan.books.single()
        assertEquals("Поезії", edited.title)
        assertEquals("Тарас Шевченко", edited.author)
        assertEquals("Диктор", edited.narrator)
        assertEquals("Збірки", edited.seriesTitle)
        assertEquals(2, edited.seriesIndex)
        assertNull(confirmed)
        assertEquals(0, streamReads)
        compose.onNodeWithTag("import_preview_split_folder:Кобзар_1").performScrollTo().performClick()
        assertEquals(listOf(1, 1), plan.books.map { it.chapters.size })
        compose.onNodeWithTag("import_preview_rename_folder:Кобзар#2").assertExists()
        compose.onNodeWithTag("library_import_preview_confirm").performClick()
        assertEquals(2, confirmed!!.books.size)
        assertEquals(0, streamReads)
    }
}
