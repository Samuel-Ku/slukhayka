package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.testing.TestDataFactory
import com.slukhayka.audiobooks.ui.screens.ListenHeroCard
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F29 of the 2026-10-28 device audit — the «Продовжити слухати» hero is the
 * app's main card, and on the device it drew a paper letter tile while the SAME
 * Work showed real art on the shelves, the book page and the player.
 *
 * The card now takes an artwork slot from its caller and falls back to the
 * paper letter only when the Work really has no art. These two nodes are the
 * regression witness: before the fix neither testTag existed and the hero could
 * silently keep drawing the placeholder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA", sdk = [35])
class ListenHeroCoverTest {

    @get:Rule
    val compose = createComposeRule()

    private val book = TestDataFactory.dataBooks()[0]
    private val progress = TestDataFactory.seedPlaybackProgress(
        listOf(book),
        chapterIndex = 2,
        positionSeconds = 420L,
    )[0]
    private val cumulativePositionSeconds = 600L + 660L + 420L

    @Test
    fun `the hero shows the Work's real artwork when it has any`() {
        showHero(book.copy(coverDrawableRes = R.drawable.ic_launcher_foreground))

        compose.onNodeWithTag("hero_cover_art", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("hero_cover_placeholder", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the hero keeps the paper letter only for a Work without art`() {
        // The fixture Work has neither URL nor drawable: the fallback is honest.
        showHero(book)

        compose.onNodeWithTag("hero_cover_placeholder", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("hero_cover_art", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun showHero(work: com.slukhayka.audiobooks.data.db.AudiobookEntity) {
        compose.setContent {
            AudiobookTheme(darkTheme = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ListenHeroCard(
                        book = work,
                        progress = progress,
                        cumulativePositionSeconds = cumulativePositionSeconds,
                        onResumeClick = {},
                        onBookClick = {},
                    )
                }
            }
        }
    }
}
