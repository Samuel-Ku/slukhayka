package com.slukhayka.audiobooks.accessibility

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.slukhayka.audiobooks.AppLocale
import com.slukhayka.audiobooks.AppLocaleApplier
import com.slukhayka.audiobooks.AppLocalePrefs
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.MainViewModel
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Real routes and localized windows; saved books/bookmarks/downloads are not edited. */
class LiveBookDetailNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun localizedBookMenusAndPersonSeriesRoutesReturnToTheirOrigin() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveBookLayout") == "true")
        val originalLocale = AppLocalePrefs(rule.activity).locale
        val requestedLocale = if (args.getString("auditLocale") == "en") AppLocale.ENGLISH else AppLocale.UKRAINIAN
        val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        val originalSelected = vm.selectedBookId.value
        rule.waitUntil(20_000) { vm.libraryBooks.value.any { it.book.title == "Трохи ненависті" } }
        val book = vm.libraryBooks.value.first { it.book.title == "Трохи ненависті" }.book
        val playback = vm.playerState.value
        fun back() {
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            rule.waitForIdle()
        }
        fun capture(surface: String) {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val phase = args.getString("auditPhase") ?: "normal"
            File(rule.activity.getExternalFilesDir(null), "555-$phase-${requestedLocale.tag}-$surface.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        try {
            rule.runOnUiThread { AppLocaleApplier.apply(rule.activity, requestedLocale) }
            rule.waitUntil(10_000) { rule.activity.resources.configuration.locales[0].language == requestedLocale.tag }
            args.getString("expectedFontScale")?.toFloat()?.let { expected ->
                assertEquals("actual Activity font scale", expected, rule.activity.resources.configuration.fontScale, 0.01f)
            }
            rule.runOnUiThread { vm.selectBook(book.id) }
            rule.onNodeWithTag("book_detail_title").assertExists()
            capture("book")
            rule.onNodeWithTag("book_detail_delete_trigger").performTouchInput { click() }
            val openSite = rule.activity.getString(R.string.a11y_book_detail_open_site, book.title)
            rule.onNodeWithText(openSite).assertIsDisplayed()
            rule.onNodeWithText(rule.activity.getString(R.string.a11y_book_detail_delete_work, book.title)).assertIsDisplayed()
            capture("menu")
            rule.onNodeWithText(openSite).performTouchInput { click() }
            rule.waitUntil(10_000) { vm.selectedWebSource.value?.homeUrl == book.sourceUrl }
            rule.runOnUiThread { vm.closeWebSource() }
            rule.onNodeWithTag("book_detail_title").assertExists()
            for (tag in listOf("book_detail_author_link", "book_detail_narrator_link", "book_detail_series_pill")) {
                rule.onNodeWithTag(tag).performScrollTo().performTouchInput { click() }
                rule.waitUntil(10_000) {
                    if (tag == "book_detail_series_pill") vm.selectedSeries.value != null else vm.selectedPerson.value != null
                }
                rule.waitForIdle()
                rule.onNodeWithTag("book_detail_title").assertDoesNotExist()
                capture("child-$tag")
                back()
                rule.waitUntil(10_000) { vm.selectedSeries.value == null && vm.selectedPerson.value == null }
                rule.onNodeWithTag(tag).assertIsFocused()
            }
            rule.onNodeWithTag("play_book_button").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            capture("actions")
            rule.onNodeWithTag("book_detail_description").performScrollTo().assertIsDisplayed()
            val scroll = rule.onNodeWithTag("book_detail_screen")
            repeat(12) {
                val descriptionBottom = rule.onNodeWithTag("book_detail_description").getUnclippedBoundsInRoot().bottom
                val viewportBottom = scroll.getUnclippedBoundsInRoot().bottom
                if (descriptionBottom > viewportBottom) {
                    scroll.performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.3f) }
                }
            }
            assertTrue("end of description must scroll above the navigation", rule.onNodeWithTag("book_detail_description").getUnclippedBoundsInRoot().bottom <= scroll.getUnclippedBoundsInRoot().bottom)
            capture("description")
            assertEquals(playback.currentBook?.id, vm.playerState.value.currentBook?.id)
            assertEquals(playback.isPlaying, vm.playerState.value.isPlaying)
        } finally {
            try {
                rule.runOnUiThread {
                    vm.closeWebSource()
                    vm.closePersonBooks()
                    vm.closeSeries()
                    vm.selectBook(originalSelected)
                }
            } finally {
                rule.runOnUiThread { AppLocaleApplier.apply(rule.activity, originalLocale) }
            }
        }
    }
}
