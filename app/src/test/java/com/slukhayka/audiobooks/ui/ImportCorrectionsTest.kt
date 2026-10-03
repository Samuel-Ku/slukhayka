package com.slukhayka.audiobooks.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.imports.*
import com.slukhayka.audiobooks.ui.components.ChapterOrderDialog
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8)
class ImportCorrectionsTest {
    @get:Rule val compose = createComposeRule()
    private val chapters = listOf(
        ChapterEntity(id = "one", bookId = "book", chapterIndex = 0, title = "Перший розділ", durationSeconds = 100L),
        ChapterEntity(id = "two", bookId = "book", chapterIndex = 1, title = "Другий розділ", durationSeconds = 100L),
        ChapterEntity(id = "three", bookId = "book", chapterIndex = 2, title = "Третій розділ", durationSeconds = 100L)
    )

    @Test fun `reorder is a draft until save and sends stable ids`() {
        var saved: List<String>? = null
        compose.setContent { AudiobookTheme { ChapterOrderDialog(chapters, false, null, { saved = it }, {}) } }
        compose.onNodeWithTag("chapter_order_save").assertIsNotEnabled()
        compose.onNodeWithTag("chapter_order_reverse").performClick()
        assertNull(saved)
        compose.onNodeWithText("1. Третій розділ").assertExists()
        compose.onRoot().captureRoboImage("src/test/snapshots/import_corrections_order.png")
        compose.onNodeWithTag("chapter_order_save").performClick()
        assertEquals(listOf("three", "two", "one"), saved)
        assertEquals(listOf("one", "two", "three"), chapters.map { it.id })
    }

    @Test fun `cancel discards moved chapters`() {
        var saved = false
        var dismissed = false
        compose.setContent { AudiobookTheme { ChapterOrderDialog(chapters, false, null, { saved = true }, { dismissed = true }) } }
        compose.onNodeWithTag("chapter_order_down_one").performClick()
        compose.onNodeWithTag("chapter_order_cancel").performClick()
        assertTrue(dismissed)
        assertFalse(saved)
    }

    @Test fun `saving disables moves and preserves an honest failure message`() {
        compose.setContent { AudiobookTheme { ChapterOrderDialog(chapters, true, "Список розділів змінився", {}, {}) } }
        compose.onNodeWithTag("chapter_order_reverse").assertIsNotEnabled()
        compose.onNodeWithTag("chapter_order_down_one").assertIsNotEnabled()
        compose.onNodeWithTag("chapter_order_cancel").assertIsNotEnabled()
        compose.onNodeWithTag("chapter_order_error").assertTextContains("Список розділів змінився")
    }

    @Test fun `confirmed metadata remains visible on both split preview cards`() {
        val files = listOf("01.mp3", "02.mp3").map { name -> LocalAudioEntry(name, "Кобзар") { ByteArrayInputStream(byteArrayOf(1)) } }
        var plan = ImportPlanner.buildPlan(SourceRef.Folder("content://tree"), files)
        plan = ImportPlanner.editBook(plan, "folder:Кобзар", title = "Поезії", author = "Тарас Шевченко", narrator = "Диктор", seriesTitle = "Збірки", seriesIndex = 2)
        plan = ImportPlanner.splitBook(plan, "folder:Кобзар", 1)
        compose.setContent { AudiobookTheme {
            ImportPreviewDialog(MainViewModel.ImportPreviewState(plan, "content://tree"), {}, {}, { _, _ -> }, { _, _ -> }, {}, {})
        } }
        compose.onNodeWithTag("import_preview_rename_folder:Кобзар#2").assertExists()
        compose.onRoot().captureRoboImage("src/test/snapshots/import_corrections_preview_split.png")
    }


}
