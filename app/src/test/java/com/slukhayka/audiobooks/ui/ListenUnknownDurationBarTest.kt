package com.slukhayka.audiobooks.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.screens.ListenHeroCard
import com.slukhayka.audiobooks.ui.screens.RecentlyListenedRow
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1353 — an unknown duration is not "0 %".
 *
 * `ListenHeroCard` and `RecentlyListenedRow` used to draw a
 * `LinearProgressIndicator` unconditionally, with `progressFraction = 0f` when
 * `totalDurationSeconds == 0`, and without `clearAndSetSemantics`. A screen
 * reader therefore announced `ProgressBarRangeInfo` = 0 % for a book whose
 * duration is simply not known — a fact stated where there is no fact.
 *
 * Both sides are pinned: the bar is absent when the total is unknown, and it is
 * present when the total is known. Without the second assertion a test that
 * only checks "no bar" would pass even if the bars had been deleted outright.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ListenUnknownDurationBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val book = TestDataFactory.dataBooks().first()
    private val progress = TestDataFactory.seedPlaybackProgress(
        audiobooks = listOf(book),
        chapterIndex = 1,
        positionSeconds = 300L
    ).first()

    /**
     * The semantics node a screen reader turns into "0 %". Counting it counts
     * what is announced, not what is drawn — an empty track would still announce
     * zero, and a bar removed from the tree announces nothing.
     */
    private fun progressBars() = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
    )

    @Composable
    private fun theme(content: @Composable () -> Unit) {
        AudiobookTheme(darkTheme = true) { content() }
    }

    @Composable
    private fun hero(book: AudiobookEntity) {
        ListenHeroCard(
            book = book,
            progress = progress,
            cumulativePositionSeconds = 900L,
            onResumeClick = {},
            onBookClick = {}
        )
    }

    @Composable
    private fun recentRow(book: AudiobookEntity) {
        RecentlyListenedRow(
            book = book,
            progress = progress,
            cumulativePositionSeconds = 900L,
            onClick = {},
            onPlayClick = {}
        )
    }

    @Test
    fun aHeroWithoutDurationDrawsNoBar() {
        compose.setContent { theme { hero(book.copy(totalDurationSeconds = 0L)) } }
        progressBars().assertCountEquals(0)
    }

    @Test
    fun aHeroWithDurationDrawsItsBar() {
        compose.setContent { theme { hero(book) } }
        progressBars().assertCountEquals(1)
    }

    @Test
    fun aRecentRowWithoutDurationDrawsNoBar() {
        compose.setContent { theme { recentRow(book.copy(totalDurationSeconds = 0L)) } }
        progressBars().assertCountEquals(0)
    }

    @Test
    fun aRecentRowWithDurationDrawsItsBar() {
        compose.setContent { theme { recentRow(book) } }
        progressBars().assertCountEquals(1)
    }
}
