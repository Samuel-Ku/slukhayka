package com.slukhayka.audiobooks.accessibility

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.SelectedTab
import com.slukhayka.audiobooks.ui.screens.WorkFeedFilters
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LanguageFilterTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(rule.activity.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun liveLanguageChangePreservesOtherFilters() {
        val vm = ViewModelProvider(rule.activity)[MainViewModel::class.java]
        val languages = vm.contentLanguages.value
        val genres = vm.feedGenreFilters.value
        val durations = vm.feedDurationFilters.value
        val sort = vm.feedSortByTitle.value
        val query = vm.searchQuery.value
        val tab = vm.selectedTab.value
        try {
            rule.runOnUiThread {
                vm.selectTab(SelectedTab.EXPLORE)
                vm.updateSearchQuery("")
                vm.setContentLanguages(setOf("uk", "en"))
                vm.setFeedGenreFilters(setOf("fantasy"))
                vm.setFeedDurationFilters(setOf("under_5h"))
                vm.setFeedSortByTitle(true)
            }
            // #766 A — a raw fetchSemanticsNodes() THROWS while no hierarchy exists
            // yet; the tolerant wait retries instead.
            rule.waitUntil(20_000) {
                runCatching { rule.onAllNodesWithTag("home_screen").fetchSemanticsNodes().size == 1 }
                    .getOrDefault(false)
            }
            rule.onNodeWithTag("home_screen").performScrollToKey("work_feed_controls")
            // Spec-51 (#742): the «Мови контенту» screen is the one writer; the
            // chip opens it rather than cycling.
            listOf(setOf("uk"), setOf("en"), emptySet<String>()).forEachIndexed { index, expected ->
                rule.runOnUiThread { vm.setContentLanguages(expected) }
                rule.waitUntil(10_000) { vm.contentLanguages.value == expected }
                assertEquals(setOf("fantasy"), vm.feedGenreFilters.value)
                assertEquals(setOf("under_5h"), vm.feedDurationFilters.value)
                assertTrue(vm.feedSortByTitle.value)
                // Language changes replace the live sections above these
                // controls, so bring their stable key back into the viewport.
                rule.onNodeWithTag("home_screen").performScrollToKey("work_feed_controls")
                rule.onNodeWithTag("feed_language").assertIsDisplayed()
                screenshot("548-live-$index.png")
            }
        } finally {
            rule.runOnUiThread {
                vm.setContentLanguages(languages)
                vm.setFeedGenreFilters(genres)
                vm.setFeedDurationFilters(durations)
                vm.setFeedSortByTitle(sort)
                vm.updateSearchQuery(query)
                vm.selectTab(tab)
            }
        }
    }
}
