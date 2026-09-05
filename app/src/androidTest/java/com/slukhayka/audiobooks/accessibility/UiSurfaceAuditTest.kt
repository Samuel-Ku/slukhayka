package com.slukhayka.audiobooks.accessibility

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.AppBottomBar
import com.slukhayka.audiobooks.ui.SelectedTab
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.ui.components.SpeedSheet
import com.slukhayka.audiobooks.ui.components.SleepTimerSheet
import com.slukhayka.audiobooks.ui.library.*
import com.slukhayka.audiobooks.ui.screens.*
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Controlled UI states on the phone. No network, playback or library writes. */
class UiSurfaceAuditTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val book = AudiobookEntity(
        id = "ui-audit", title = "Надзвичайно довга назва книги про подорож між світами та повернення додому",
        author = "Автор із довгим подвійним прізвищем", narrator = "Озвучувач із довгим подвійним прізвищем",
        description = "", genre = "Фантастика", sourceUrl = "https://example.invalid/audit",
        coverDrawableRes = R.drawable.img_neuromancer_cover_1785247475170,
        totalDurationSeconds = 1800, totalChapters = 3
    )
    private val chapters = (0..2).map { index -> ChapterEntity(
        id = "audit-$index", bookId = book.id, chapterIndex = index,
        title = "Розділ ${index + 1}: довга назва розділу з поясненням подій", durationSeconds = 600
    ) }

    @Test fun captureStatesAndCheckPlayerCoverTransition() {
        var scene by mutableStateOf("player_normal")
        var scale by mutableStateOf(1f)
        var gridSelected = false
        var retries = 0
        var alternatives = 0
        val openedSettings = mutableListOf<SettingsDestination>()
        val arguments = InstrumentationRegistry.getArguments()
        val locale = arguments.getString("auditLocale") ?: "uk"
        rule.runOnUiThread {
            rule.activity.setContent {
                val density = LocalDensity.current
                val base = LocalContext.current
                val configuration = Configuration(base.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(locale))
                    fontScale = scale
                }
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, scale),
                    LocalContext provides base.createConfigurationContext(configuration),
                    LocalConfiguration provides configuration
                ) {
                    AudiobookTheme(darkTheme = true) {
                        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
                            if (scene.startsWith("player_")) {
                                Player(scene, { retries++ }, { alternatives++ })
                            } else key(scene) {
                                when (scene) {
                                    "search_loading" -> Column { GlobalSearchStatus(true, false, true) }
                                    "search_empty" -> Column { GlobalSearchStatus(false, false, true) }
                                    "search_error" -> Column { GlobalSearchStatus(false, true, true) }
                                    "search_result" -> Column {
                                        GlobalSearchResultCard(GlobalSearchResult(
                                            title = book.title, author = book.author, narrator = book.narrator,
                                            mergeKey = "audit", sources = listOf(
                                                GlobalSearchSource("4read", "4read", "https://example.invalid/a"),
                                                GlobalSearchSource("soundbooks", "Sound-Books", "https://example.invalid/b")
                                            )
                                        ), onClick = {})
                                    }
                                    "catalog_empty" -> Column { EmptyCatalogState({}, {}) }
                                    "cycle" -> Column { PersonalCycleCard(PersonalCycle("Епоха божевілля", "audit", null, 0, 12, false), {}) }
                                    "library_empty" -> LibraryEmptyState({}, {})
                                    "library_row" -> Column { LibraryBookCard(buildLibraryBooks(listOf(book), emptyList(), mapOf(book.id to chapters)).single(), grid = false, onClick = {}) }
                                    "library_grid" -> LazyVerticalGrid(
                                        columns = GridCells.Fixed(2), contentPadding = PaddingValues(16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) { items(2) { LibraryBookCard(buildLibraryBooks(listOf(book), emptyList(), mapOf(book.id to chapters)).single(), grid = true, onClick = {}) } }
                                    "library_filters" -> LibraryFilterSheet(LibraryFilter.ALL, LibrarySort.RECENTLY_LISTENED, false, {}, {}, { gridSelected = it }, {})
                                    "delete_dialog" -> ClearCacheConfirmDialog(3, 1024L * 1024 * 120, {}, {})
                                    "speed" -> SpeedSheet(1.25f, {}, {}, {}, {})
                                    "timer" -> SleepTimerSheet(currentTimerMinutes = 15, onSelectTimer = {}, onDismiss = {})
                                    "chapters" -> ChapterBottomSheet(chapters, 1, {}, {})
                                    "bookmark" -> BookmarkBottomSheet(120, chapters[1].title, {}, {})
                                    "settings" -> Box(Modifier.fillMaxSize()) { Box(Modifier.width(320.dp)) { SettingsScreen { openedSettings += it } } }
                                    "navigation" -> Box(Modifier.fillMaxSize()) { Box(Modifier.width(320.dp).testTag("navigation_fixture")) { AppBottomBar(SelectedTab.SETTINGS) {} } }
                                    "book" -> Box(Modifier.fillMaxSize()) { Column(
                                        Modifier.width(320.dp).testTag("book_fixture").verticalScroll(rememberScrollState()).padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        val presentation = bookDetailPresentation(book, emptyList(), listOf(
                                            SourceCatalog.WorkSourceRow("4read", "4read", "https://4read.org/audit", false)
                                        ))
                                        BookDetailIdentityHeader(book, presentation, universeName = "Перший закон")
                                        BookDetailPrimaryActions(
                                            workTitle = book.title,
                                            playLabel = stringResource(R.string.book_detail_continue_short) + " 01:23:45",
                                            streamOnly = false, downloadAction = BookDetailDownloadAction.Continue,
                                            downloadProgress = 0.5f, onPlay = {}, onDownload = {}, onAddBookmark = {}
                                        )
                                        BookDetailSourceSection(presentation)
                                    } }
                                }
                            }
                        }
                    }
                }
            }
        }
        val phase = arguments.getString("auditPhase") ?: "verified"
        val scenes = listOf("player_normal", "player_loading", "player_error", "search_loading", "search_empty", "search_error",
            "search_result", "catalog_empty", "cycle", "library_empty", "library_row", "library_grid", "library_filters",
            "delete_dialog", "speed", "timer", "chapters", "bookmark", "book", "settings", "navigation")
        for (fontScale in listOf(1f, 2f)) {
            for (name in arguments.getString("auditScenes")?.split(",") ?: scenes) {
                rule.runOnUiThread { scene = name; scale = fontScale }
                rule.waitForIdle()
                rule.mainClock.advanceTimeBy(600)
                rule.waitForIdle()
                // Android dialog windows also have platform enter/exit animations.
                // Compose idleness alone can capture the previous window fading out.
                android.os.SystemClock.sleep(500)
                screenshot("549-$phase-$locale-$name-$fontScale.png")
                if (name.startsWith("player_")) {
                    val cover = rule.onAllNodesWithTag("player_cover").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
                    val title = rule.onNodeWithTag("player_context").fetchSemanticsNode().boundsInRoot
                    assertTrue("Cover overlaps context in $name / $fontScale", cover == null || cover.bottom <= title.top)
                    if (name == "player_error") rule.onAllNodesWithText("HTTP 403 raw technical error").assertCountEquals(0)
                    if (fontScale == 2f) rule.onNodeWithTag("player_play_pause_button").performScrollTo().assertIsDisplayed()
                }
                if (phase != "before") {
                    if (name == "settings") {
                        rule.onNodeWithTag("settings_screen").assertWidthIsEqualTo(320.dp)
                        openedSettings.clear()
                        val routes = listOf(SettingsDestination.Profile, SettingsDestination.Storage,
                            SettingsDestination.NetworkPrivacy, SettingsDestination.Recommendations,
                            SettingsDestination.ContentLanguages, SettingsDestination.AppLocale)
                        for (route in routes) {
                            rule.onNodeWithTag("settings_${route.name}").performScrollTo()
                                .assertIsDisplayed().assertHeightIsAtLeast(48.dp).performTouchInput { click() }
                        }
                        rule.waitForIdle()
                        assertEquals(routes, openedSettings)
                    }
                    if (name == "navigation") {
                        rule.onNodeWithTag("navigation_fixture").assertWidthIsEqualTo(320.dp)
                        val context = rule.activity.createConfigurationContext(Configuration(rule.activity.resources.configuration).apply {
                            setLocale(Locale.forLanguageTag(locale))
                        })
                        for (res in listOf(R.string.nav_listen, R.string.nav_explore, R.string.nav_library, R.string.nav_settings)) {
                            val layouts = mutableListOf<TextLayoutResult>()
                            rule.onNodeWithText(context.getString(res), useUnmergedTree = true)
                                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                            assertTrue("Clipped navigation label", !layouts.single().hasVisualOverflow)
                        }
                    }
                    if (name == "book") {
                        rule.onNodeWithTag("book_fixture").assertWidthIsEqualTo(320.dp)
                        for (role in listOf("author", "narrator")) {
                            rule.onNodeWithTag("book_detail_${role}_link").performScrollTo().assertIsDisplayed()
                            val person = rule.onNodeWithTag("book_detail_${role}_link").fetchSemanticsNode().boundsInRoot
                            val star = rule.onNodeWithTag("book_detail_${role}_bookmark").fetchSemanticsNode().boundsInRoot
                            assertEquals("Detached bookmark", person.right, star.left, 1f)
                            val layouts = mutableListOf<TextLayoutResult>()
                            rule.onNodeWithTag("book_detail_${role}_link")
                                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                            assertTrue("Clipped person name", !layouts.single().hasVisualOverflow)
                        }
                        for (tag in listOf("play_book_button", "download_offline_button", "bookmark_button")) {
                            rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                        }
                        rule.onAllNodesWithText("4read").assertCountEquals(1)
                        screenshot("550-$locale-book-actions-$fontScale.png")
                    }
                    if (name == "catalog_empty") {
                        for (tag in listOf("catalog_empty_refresh", "catalog_empty_import")) {
                            val node = rule.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                            val height = node.fetchSemanticsNode().boundsInRoot.height / rule.activity.resources.displayMetrics.density
                            assertTrue("Stretched $tag: $height dp", height <= 120)
                        }
                    }
                    if (name == "library_filters") {
                        gridSelected = false
                        rule.onNodeWithTag("library_view_grid").performScrollTo().assertIsDisplayed().performTouchInput { click() }
                        rule.waitForIdle()
                        assertTrue("Grid callback did not run", gridSelected)
                    }
                    if (name == "player_error") {
                        val beforeRetries = retries
                        val beforeAlternatives = alternatives
                        for (tag in listOf("player_retry", "player_find_another_source")) {
                            val node = rule.onNodeWithTag(tag)
                            if (fontScale == 2f) node.performScrollTo()
                            node.assertIsDisplayed().assertHeightIsAtLeast(48.dp).performTouchInput { click() }
                        }
                        rule.waitForIdle()
                        assertEquals(beforeRetries + 1, retries)
                        assertEquals(beforeAlternatives + 1, alternatives)
                    }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(rule.activity.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Composable private fun Player(scene: String, onRetry: () -> Unit, onAlternative: () -> Unit) {
        val state = PlayerState(currentBook = book, chapters = chapters, currentChapterIndex = 1,
            currentPositionMs = 120_000, durationMs = 600_000, isPlaying = scene == "player_normal",
            isBuffering = scene == "player_loading", lastErrorMsg = if (scene == "player_error") "HTTP 403 raw technical error" else "")
        PlayerScreenContent(
            playerState = state, book = book, currentChapterTitle = chapters[1].title,
            progress = calculatePlayerProgress(chapters, 1, 120_000, 600_000, emptyList()), artworkAccent = null,
            onArtworkLoaded = {}, onDismiss = {}, onToggleFavorite = {}, onToggleDebug = {}, onSeek = {}, onBookSeek = {},
            onPreviousChapter = {}, onBack = {}, onPlayPause = {}, onForward = {}, onNextChapter = {}, onUndoSeek = {},
            onSpeed = {}, onTimer = {}, onBookmark = {}, onChapters = {},
            onRetryPlayback = onRetry, onFindAnotherSource = onAlternative
        )
    }
}
