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
    @Test fun `a folder without a display name needs a confirmed book title before import`() {
        var reads = 0
        val file = LocalAudioEntry("Назва яку не слід вгадувати.mp3", null) { reads++; ByteArrayInputStream(byteArrayOf(1)) }
        var plan by mutableStateOf(ImportPlanner.buildPlan(SourceRef.Folder("content://tree", grouping = LocalFolderGrouping.ONE_BOOK), listOf(file)))
        var confirmed: ImportPlan? = null
        compose.setContent { AudiobookTheme {
            ImportPreviewDialog(
                MainViewModel.ImportPreviewState(plan, "content://tree"), {}, {}, { _, _ -> }, { _, _ -> }, { confirmed = plan }, {},
                onEditBookMetadata = { id, book -> plan = ImportPlanner.editBook(plan, id, title = book.title) }
            )
        } }
        assertEquals("", plan.books.single().title)
        compose.onNodeWithTag("library_import_preview_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("import_preview_rename_root-folder").performScrollTo().performClick()
        compose.onNodeWithTag("metadata_edit_title").performTextReplacement("Моя книга")
        compose.onNodeWithTag("metadata_edit_save").performClick()
        compose.onNodeWithTag("import_preview_rename_root-folder").assertIsFocused()
        compose.onNodeWithTag("library_import_preview_confirm").assertIsEnabled().performClick()
        assertEquals("Моя книга", confirmed!!.books.single().title)
        assertEquals(0, reads)
    }

    @Test fun `planned merge requires an explicit target even when titles match and restores focus`() {
        var reads = 0
        fun file(name: String, folder: String? = null) = LocalAudioEntry(name, folder) { reads++; ByteArrayInputStream(byteArrayOf(1)) }
        var draft = ImportPlanner.buildPlan(SourceRef.Folder("content://tree", "Кобзар"), listOf(file("00.mp3"), file("01.mp3", "A"), file("01.mp3", "B")))
        draft = ImportPlanner.editBook(draft, "folder:A", title = "Поезії", author = "Автор A")
        draft = ImportPlanner.editBook(draft, "folder:B", title = "Поезії", author = "Автор B", narrator = "Диктор B")
        val before = draft
        val target = before.books.single { it.id == "folder:B" }
        val unrelated = before.books.single { it.id == "folder:A" }
        var plan by mutableStateOf(before)
        var confirmed: ImportPlan? = null
        compose.setContent { AudiobookTheme {
            ImportPreviewDialog(
                MainViewModel.ImportPreviewState(plan, "content://tree"), {}, {}, { _, _ -> }, { _, _ -> }, { confirmed = plan }, {},
                onMergePlannedBooks = { source, destination -> plan = ImportPlanner.mergePlannedBooks(plan, source, destination) }
            )
        } }
        compose.onNodeWithTag("import_preview_merge_root:00.mp3").performScrollTo().performClick()
        compose.onNodeWithTag("import_preview_merge_dialog").assertExists()
        assertEquals(before, plan)
        compose.onNodeWithTag("import_preview_merge_target_folder:A").assertTextContains("A/01.mp3")
        compose.onNodeWithTag("import_preview_merge_target_folder:B").assertTextContains("B/01.mp3")
        compose.onNodeWithTag("import_preview_merge_cancel").performClick()
        assertEquals(before, plan)
        compose.onNodeWithTag("import_preview_merge_root:00.mp3").assertIsFocused().performClick()
        compose.onNodeWithTag("import_preview_merge_target_folder:B").performScrollTo().performClick()
        compose.onNodeWithTag("import_preview_rename_folder:B").assertIsFocused()
        assertEquals(2, plan.books.size)
        assertEquals(unrelated, plan.books.single { it.id == "folder:A" })
        val merged = plan.books.single { it.id == "folder:B" }
        assertEquals(target.copy(chapters = target.chapters + before.books.single { it.id == "root:00.mp3" }.chapters), merged)
        assertEquals(listOf("01.mp3", "00.mp3"), merged.chapters.map { it.file.fileName })
        assertEquals(0, reads)
        assertNull(confirmed)
        compose.onNodeWithTag("library_import_preview_confirm").performClick()
        assertEquals(plan, confirmed)
        assertEquals(0, reads)
    }

    @Test fun `changing folder mode asks before discarding merged corrections and restores focus on cancel`() {
        var reads = 0
        fun file(name: String, folder: String? = null) = LocalAudioEntry(name, folder) { reads++; ByteArrayInputStream(byteArrayOf(1)) }
        var draft = ImportPlanner.buildPlan(SourceRef.Folder("content://tree", "Кобзар"), listOf(file("00.mp3"), file("01.mp3", "Інша"), file("02.mp3", "Інша")))
        draft = ImportPlanner.editBook(draft, "folder:Інша", title = "Моя папка", author = "Автор")
        draft = ImportPlanner.splitBook(draft, "folder:Інша", 1)
        draft = ImportPlanner.editBook(draft, "folder:Інша#2", narrator = "Диктор")
        draft = ImportPlanner.mergePlannedBooks(draft, "folder:Інша#2", "root:00.mp3")
        draft = ImportPlanner.editBook(draft, "root:00.mp3", title = "Мій вступ")
        val before = draft
        val untouched = before.books.single { it.id == "folder:Інша" }
        val keptCorrections = before.corrections.filter { it.plannedBookId == untouched.id }
        var plan by mutableStateOf(before)
        var confirmed: ImportPlan? = null
        compose.setContent { AudiobookTheme {
            ImportPreviewDialog(
                MainViewModel.ImportPreviewState(plan, "content://tree"), {}, {}, { _, _ -> }, { _, _ -> }, { confirmed = plan }, {},
                onFolderGroupingChange = { plan = ImportPlanner.changeFolderGrouping(plan, it) }
            )
        } }
        compose.onNodeWithTag("import_folder_grouping_one").performScrollTo().performClick()
        assertEquals("a mode click cannot discard corrections before consent", before, plan)
        compose.onNodeWithTag("import_folder_grouping_reset_dialog").assertExists()
        compose.onNodeWithTag("import_folder_grouping_reset_cancel").performClick()
        assertEquals(before, plan)
        compose.onNodeWithTag("import_folder_grouping_one").assertIsFocused()
        compose.onNodeWithTag("import_folder_grouping_one").performClick()
        compose.onNodeWithTag("import_folder_grouping_reset_confirm").performClick()
        compose.onNodeWithTag("import_folder_grouping_one").assertIsSelected().assertIsFocused()
        assertEquals("Кобзар", plan.books.single { it.id == "root-folder" }.title)
        assertEquals(untouched, plan.books.single { it.id == untouched.id })
        assertEquals(3, plan.books.size)
        assertEquals(3, plan.books.map { it.id }.toSet().size)
        assertEquals(setOf("00.mp3", "01.mp3", "02.mp3"), plan.books.flatMap { it.chapters }.map { it.file.fileName }.toSet())
        assertEquals("reset clears discarded source edits but keeps the unaffected split", keptCorrections, plan.corrections)
        assertNull(confirmed)
        assertEquals(0, reads)
        compose.onNodeWithTag("library_import_preview_confirm").performClick()
        assertEquals(plan, confirmed)
        assertEquals(0, reads)
    }

    @Test fun `folder mode groups only direct files and stays a draft until confirmation`() {
        var reads = 0
        val files = listOf("03.mp3", "01.mp3", "02.mp3").map { name -> LocalAudioEntry(name, null) { reads++; ByteArrayInputStream(byteArrayOf(1)) } } +
            LocalAudioEntry("01.mp3", "Інша") { reads++; ByteArrayInputStream(byteArrayOf(2)) }
        var plan by mutableStateOf(ImportPlanner.buildPlan(SourceRef.Folder("content://tree", "Кобзар"), files))
        val unrelated = plan.books.single { it.id == "folder:Інша" }
        var confirmed: ImportPlan? = null
        compose.setContent { AudiobookTheme {
            ImportPreviewDialog(
                MainViewModel.ImportPreviewState(plan, "content://tree"), {}, {}, { _, _ -> }, { _, _ -> }, { confirmed = plan }, {},
                onFolderGroupingChange = { plan = ImportPlanner.changeFolderGrouping(plan, it) }
            )
        } }
        compose.onNodeWithTag("import_folder_grouping_separate").assertIsSelected()
        assertEquals(4, plan.books.size)
        compose.onNodeWithTag("import_folder_grouping_one").performScrollTo().performClick().assertIsSelected()
        assertEquals(2, plan.books.size)
        assertEquals("Кобзар", plan.books.first().title)
        assertEquals(listOf("01.mp3", "02.mp3", "03.mp3"), plan.books.first().chapters.map { it.file.fileName })
        assertEquals(unrelated, plan.books.last())
        assertEquals(LocalFolderGrouping.ONE_BOOK, (plan.source as SourceRef.Folder).grouping)
        assertNull(confirmed)
        assertEquals(0, reads)
        compose.onNodeWithTag("library_import_preview_confirm").performClick()
        assertEquals(plan, confirmed)
        assertEquals(0, reads)
    }

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
