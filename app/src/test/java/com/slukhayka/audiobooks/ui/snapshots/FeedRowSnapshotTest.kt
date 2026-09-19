package com.slukhayka.audiobooks.ui.snapshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.ui.catalog.CatalogCardAction
import com.slukhayka.audiobooks.ui.catalog.CatalogCardActionState
import com.slukhayka.audiobooks.ui.catalog.CatalogCardFailure
import com.slukhayka.audiobooks.ui.catalog.CatalogCardTarget
import com.slukhayka.audiobooks.ui.screens.homeFeedContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Snapshot tests for the spec-42 T1 endless-feed row: Source provenance no
 * longer occupies the feed, language badges and known durations ride the
 * canonical `BookRow`, and the action status stays under the row.
 *
 * #567: the feed builds that row inline at the `homeFeedContent` call site —
 * the named `WorkFeedCard` wrapper is gone — so these snapshots drive the feed
 * seam itself from fixture rows, with no ViewModel. Every shelf above the feed
 * is empty, so the phone-height viewport composes the row and its footnote.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-" + RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class FeedRowSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun row(
        title: String,
        author: String,
        editionCount: Int,
        languages: String = "",
        durationSeconds: Long? = null,
        durationMaxSeconds: Long? = null
    ) = WorkFeedRow(
        workId = "w-$editionCount",
        mergeKey = title.lowercase(),
        title = title,
        author = author,
        seriesTitle = null,
        seriesIndex = null,
        coverImageUrl = null,
        addedAt = 0L,
        // ADR-0007: the badge counts the Work's SOURCE rows (work_sources).
        sourceCount = editionCount,
        genre = null,
        languages = languages,
        durationSeconds = durationSeconds,
        durationMaxSeconds = durationMaxSeconds
    )

    private fun setContent(
        row: WorkFeedRow,
        actionState: CatalogCardActionState = CatalogCardActionState.Idle,
        onCancelAction: () -> Unit = {},
        onOpenBrowser: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val feedFlow = remember { MutableStateFlow(PagingData.from(listOf(row))) }
                    val feedItems = feedFlow.collectAsLazyPagingItems()
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        homeFeedContent(
                            isCatalogLoading = false,
                            hasLibraryBooks = true,
                            sections = emptyList(),
                            genreFacetOptions = emptyList(),
                            collections = emptyList(),
                            newArrivals = emptyList(),
                            recommendedBooks = emptyList(),
                            personalCycles = emptyList(),
                            shortBooks = emptyList(),
                            longBooks = emptyList(),
                            workFeedItems = feedItems,
                            feedGenreFilters = emptySet(),
                            feedSortByTitle = false,
                            onRefreshCatalog = {},
                            onGoToLibrary = {},
                            onOpenTop100 = {},
                            onOpenPeople = {},
                            onOpenSeriesIndex = {},
                            onOpenCollectionsIndex = {},
                            onOpenSeries = { _, _ -> },
                            onOpenRecommendedBook = {},
                            onOpenWorkFeedRow = {},
                            onBookClick = {},
                            onSetFeedGenreFilters = {},
                            onSetFeedSortByTitle = {},
                            catalogCardActionState = actionState,
                            onCancelCatalogCardAction = onCancelAction,
                            onOpenCatalogBrowser = onOpenBrowser
                        )
                    }
                }
            }
        }
    }

    @Test
    fun feed_row_never_shows_source_count() {
        setContent(row("Пасажир", "Жан-Крістоф Гранже", editionCount = 2))

        composeTestRule.onNodeWithText("2 джерела").assertDoesNotExist()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/work_feed_no_source_badge.png"
        )
    }

    @Test
    fun feed_row_single_source_no_badge() {
        setContent(row("Пасажир", "Жан-Крістоф Гранже", editionCount = 1))

        // A single source renders no badge at all.
        composeTestRule.onNodeWithText("1 джерело").assertDoesNotExist()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/work_feed_single_source_no_badge.png"
        )
    }

    // Spec-45 (#405) T7 (#495): a known rendition language renders its EN/UA
    // badge; unknown renders NO badge — honest absence.
    @Test
    fun feed_row_known_english_renders_en_badge() {
        setContent(
            row("Pride and Prejudice", "Jane Austen", editionCount = 1, languages = "en")
        )

        composeTestRule.onNodeWithText("EN").assertExists()
        composeTestRule.onNodeWithContentDescription("English").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/work_feed_en_badge.png"
        )
    }

    @Test
    fun feed_row_known_ukrainian_renders_ua_badge() {
        setContent(
            row("Кобзар", "Тарас Шевченко", editionCount = 1, languages = "uk")
        )

        composeTestRule.onNodeWithText("UA").assertExists()
        composeTestRule.onNodeWithContentDescription("Українська").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/work_feed_ua_badge.png"
        )
    }

    @Test
    fun feed_row_unknown_language_renders_no_badge() {
        setContent(row("Пасажир", "Жан-Крістоф Гранже", editionCount = 1))

        composeTestRule.onNodeWithText("EN").assertDoesNotExist()
        composeTestRule.onNodeWithText("UA").assertDoesNotExist()
    }

    @Test
    fun feed_row_multi_language_work_renders_both_badges() {
        setContent(
            row("Pride and Prejudice", "Jane Austen", editionCount = 2, languages = "en,uk")
        )

        composeTestRule.onNodeWithText("EN").assertExists()
        composeTestRule.onNodeWithText("UA").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/work_feed_both_badges.png"
        )
    }

    // Spec-24 T1: the feed row shows the full book duration (Ч:ММ:СС) under
    // the author, and only when the duration is really known — never «0:00».
    @Test
    fun feed_row_known_duration_renders_time() {
        setContent(
            row("Пасажир", "Жан-Крістоф Гранже", editionCount = 1, durationSeconds = 60_061L)
        )

        // Self-verifying on top of the image: the «16:41:01» line renders.
        composeTestRule.onNodeWithText("16:41:01").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/work_feed_duration.png"
        )
    }

    @Test
    fun feed_row_materially_different_Editions_render_an_honest_range() {
        setContent(
            row(
                "Книга з начитками",
                "Автор",
                editionCount = 2,
                durationSeconds = 10_800L,
                durationMaxSeconds = 43_200L
            )
        )

        composeTestRule.onNodeWithText("3:00:00–12:00:00").assertExists()
        composeTestRule.onNodeWithText("2 джерела").assertDoesNotExist()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/work_feed_duration_range.png"
        )
    }

    @Test
    fun feed_row_unknown_duration_renders_nothing() {
        setContent(row("Пасажир", "Жан-Крістоф Гранже", editionCount = 1))

        // No duration known — no time line at all (never a fabricated «0:00»).
        composeTestRule.onNodeWithText("16:41:01").assertDoesNotExist()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/work_feed_no_duration.png"
        )
    }

    @Test
    fun feed_row_checking_state_is_visible_and_cancellable() {
        var cancelled = false
        val work = row("Пасажир", "Жан-Крістоф Гранже", editionCount = 1)
        setContent(
            row = work,
            actionState = CatalogCardActionState.Checking(
                CatalogCardTarget(work.workId, work.title, work.author),
                CatalogCardAction.PLAY
            ),
            onCancelAction = { cancelled = true }
        )

        composeTestRule.onNodeWithText("Перевіряємо…").assertExists()
        composeTestRule.onNodeWithContentDescription("Скасувати").performClick()
        assertTrue(cancelled)
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/work_feed_checking.png"
        )
    }

    @Test
    fun feed_row_terminal_error_is_visible() {
        val work = row("Пасажир", "Жан-Крістоф Гранже", editionCount = 1)
        setContent(
            row = work,
            actionState = CatalogCardActionState.Failed(
                CatalogCardTarget(work.workId, work.title, work.author),
                CatalogCardAction.OPEN,
                CatalogCardFailure.EMPTY_SOURCES
            )
        )

        composeTestRule.onNodeWithText("Не вдалося відкрити книгу. Спробуйте ще раз.").assertExists()
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/work_feed_error.png"
        )
    }
}
