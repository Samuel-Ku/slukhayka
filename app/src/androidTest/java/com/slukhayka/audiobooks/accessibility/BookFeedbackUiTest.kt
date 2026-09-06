package com.slukhayka.audiobooks.accessibility

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.data.db.BookRow
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.reviews.*
import com.slukhayka.audiobooks.ui.BookFeedbackController
import com.slukhayka.audiobooks.ui.screens.BookFeedbackHost
import com.slukhayka.audiobooks.ui.screens.ChapterBottomSheet
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Local fixture only: never publishes a test review or changes real playback/data. */
class BookFeedbackUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private class Memory : FeedbackPreferences {
        val values = mutableMapOf<String, String?>()
        var pendingIds = emptySet<String>()
        override fun read(key: String) = values[key]
        override fun write(values: Map<String, String?>) { this.values.putAll(values) }
        override fun pending() = pendingIds
        override fun pending(ids: Set<String>) { pendingIds = ids }
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(rule.activity.getExternalFilesDir(null), "feedback-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    @Test fun completionWaitsForForegroundAndFailedDraftCanBeReopenedAtLargeFont() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val local = BookFeedbackStore(Memory())
        val book = BookRow("fixture", "Трохи ненависті — тестова форма", "Автор", "Рік САНЧЕЗ", "", 0, genre = "", sourceUrl = "", workId = "work")
        val c = BookFeedbackController(scope, local, { book }, { "edition" }, { null }, null, null)
        var foreground by mutableStateOf(false)
        try {
            rule.runOnUiThread { rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    AudiobookTheme { Surface { BookFeedbackHost(c, local, foreground, true) } }
                }
            } }
            local.completed("fixture")
            rule.waitForIdle()
            rule.onNodeWithTag("book_feedback_form").assertDoesNotExist()
            rule.runOnUiThread { foreground = true }
            rule.waitUntil(10_000) { c.state.value?.loading == false }
            rule.onNodeWithTag("feedback_book_stars").performScrollTo()
            rule.onNode(hasTestTag("rating_star_3") and hasAnyAncestor(hasTestTag("feedback_book_stars"))).performTouchInput { click() }
            rule.onNodeWithTag("feedback_narration_stars").performScrollTo()
            rule.onNode(hasTestTag("rating_star_5") and hasAnyAncestor(hasTestTag("feedback_narration_stars"))).performTouchInput { click() }
            capture("ratings-200")
            rule.onNodeWithTag("feedback_body").performScrollTo().performTextInput("Збережена чернетка")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            rule.onNodeWithTag("feedback_save").performScrollTo().assertHeightIsAtLeast(48.dp).performTouchInput { click() }
            rule.waitUntil(10_000) { c.state.value?.failed == true }
            rule.onNodeWithTag("feedback_save").performScrollTo().assertIsDisplayed()
            capture("failed-200")
            rule.onNodeWithTag("feedback_dismiss").performScrollTo().performTouchInput { click() }
            rule.onNodeWithTag("book_feedback_form").assertDoesNotExist()
            local.completed("fixture")
            rule.waitForIdle()
            rule.onNodeWithTag("book_feedback_form").assertDoesNotExist()
            rule.runOnUiThread { c.open("fixture") }
            rule.waitUntil(10_000) { c.state.value?.loading == false }
            assertEquals(BookFeedbackDraft(3, 5, "Збережена чернетка"), c.state.value?.draft)
        } finally { scope.cancel() }
    }

    @Test fun chapterListEndsWithAReachableFeedbackAction() {
        var tapped = false
        val chapters = (0..5).map { ChapterEntity(id = "fixture-$it", bookId = "fixture", title = "Розділ ${it + 1}", chapterIndex = it, durationSeconds = 60) }
        rule.runOnUiThread { rule.activity.setContent {
            AudiobookTheme { ChapterBottomSheet(chapters, 0, {}, {}, onFeedback = { tapped = true }) }
        } }
        rule.onNodeWithTag("chapter_sheet_heading").performTouchInput { swipeUp() }
        rule.onNodeWithTag("chapter_sheet_list").performScrollToNode(hasTestTag("book_feedback_open"))
        rule.onNodeWithTag("book_feedback_open").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("playlist-footer")
        rule.onNodeWithTag("book_feedback_open").performTouchInput { click() }
        assertTrue(tapped)
    }
}
