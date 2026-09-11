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
            rule.waitUntil(20_000) { rule.onAllNodesWithTag("home_screen").fetchSemanticsNodes().size == 1 }
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

    @Test fun flagsFitInBothLocalesAndLargeText() {
        var language by mutableStateOf(setOf("uk", "en"))
        var locale by mutableStateOf("uk")
        var scale by mutableStateOf(1f)
        var width by mutableStateOf(320)
        rule.runOnUiThread {
            rule.activity.setContent {
                val base = LocalContext.current
                val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
                val localized = base.createConfigurationContext(configuration)
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalContext provides localized, LocalConfiguration provides configuration,
                    LocalDensity provides Density(density.density, scale)
                ) {
                    AudiobookTheme(darkTheme = true) {
                        Box(Modifier.statusBarsPadding().width(width.dp)) {
                            WorkFeedFilters(
                                selectedGenreIds = setOf("fantasy"), selectedDurationBucketIds = setOf("under_5h"),
                                sortByTitle = false, genres = emptyList(), onGenresChange = {}, onSortChange = {},
                                contentLanguages = language
                            )
                        }
                    }
                }
            }
        }
        for (uiLanguage in listOf("uk", "en")) {
            for ((testWidth, fontScale) in listOf(320 to 1f, 400 to 1.3f, 320 to 2f)) {
                // Spec-51 (#742): «Усі» is the empty selection; {uk} and {en}
                // are the two narrowed states the chip must announce.
                for ((index, selected) in listOf(emptySet<String>(), setOf("uk"), setOf("en")).withIndex()) {
                    rule.runOnUiThread { locale = uiLanguage; width = testWidth; scale = fontScale; language = selected }
                    rule.waitForIdle()
                    val bounds = listOf("feed_sort", "feed_filters", "feed_language").map { tag ->
                        rule.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsEqualTo(48.dp)
                            .assertWidthIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
                    }
                    assertTrue(bounds.zipWithNext().all { (left, right) -> left.right <= right.left && left.top == right.top })
                    if (selected.size == 1) rule.onNodeWithTag("feed_language").assertIsSelected()
                    else rule.onNodeWithTag("feed_language").assertIsNotSelected()
                    val context = rule.activity.createConfigurationContext(Configuration(rule.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(uiLanguage)) })
                    val name = when (index) { 1 -> R.string.content_language_uk; 2 -> R.string.content_language_en; else -> R.string.content_language_all }
                    rule.onNodeWithTag("feed_language").assertContentDescriptionEquals(context.getString(R.string.content_language_chip_label, context.getString(name)))
                    screenshot("548-$uiLanguage-$testWidth-$fontScale-$index.png")
                }
            }
        }
    }
}
