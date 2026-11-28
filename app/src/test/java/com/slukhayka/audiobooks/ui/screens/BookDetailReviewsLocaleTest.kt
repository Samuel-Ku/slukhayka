package com.slukhayka.audiobooks.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.slukhayka.audiobooks.data.reviews.ListenerReview
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1824 — a review's date is written in the interface locale.
 *
 * `reviewDateLabel` used to take `Locale("uk")` unconditionally, so EN and PL
 * turned «14 лист. 2023 р.» in the middle of an otherwise translated card. The
 * timestamp is 2023-11-14T12:00:00Z — noon, so a test machine's time zone
 * cannot move the day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BookDetailReviewsLocaleTest {

    @get:Rule
    val rule = createComposeRule()

    private val review = ListenerReview(
        workId = "work-1",
        uid = "uid-1",
        authorName = "Listener",
        rating = 4,
        body = null,
        editionTag = null,
        createdAt = NOON_14_NOV_2023
    )

    private fun renderCard() {
        rule.setContent {
            AudiobookTheme(darkTheme = true) {
                ReviewCard(
                    review = review,
                    workTitle = "Dune",
                    isOwn = false,
                    isPending = false
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "en-rUS", sdk = [36])
    fun `an english interface writes an english review date`() {
        renderCard()

        rule.onNodeWithText("Nov 14, 2023").assertExists()
        rule.onNodeWithText("лист", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "pl", sdk = [36])
    fun `a polish interface writes a polish review date`() {
        renderCard()

        rule.onNodeWithText("14 lis 2023").assertExists()
        rule.onNodeWithText("лист", substring = true).assertDoesNotExist()
    }

    /**
     * The seam itself, without Compose: the label takes the locale it is given
     * instead of reaching for a hardcoded one (web's `reviewDateLabel` keeps
     * the same contract — ADR-0034).
     */
    @Test
    fun `the label formats with the locale it is given`() {
        assertEquals(
            "Nov 14, 2023",
            reviewDateLabel(NOON_14_NOV_2023, Locale.forLanguageTag("en"))
        )
        assertEquals(
            "14 lis 2023",
            reviewDateLabel(NOON_14_NOV_2023, Locale.forLanguageTag("pl"))
        )
    }

    private companion object {
        /** 2023-11-14T12:00:00Z, far enough from midnight to ignore time zones. */
        const val NOON_14_NOV_2023 = 1_699_963_200_000L
    }
}
