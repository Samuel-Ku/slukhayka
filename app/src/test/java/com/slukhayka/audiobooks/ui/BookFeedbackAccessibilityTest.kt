package com.slukhayka.audiobooks.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.screens.BookFeedbackSheet
import com.slukhayka.audiobooks.data.reviews.BookFeedbackDraft
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w320dp-h640dp")
class BookFeedbackAccessibilityTest {
    @get:Rule val compose = createComposeRule()
    @Test fun distinctRatingsAndRetryStayReachableAtTwoHundredPercent() {
        var state by mutableStateOf(BookFeedbackState("book", "work", "edition", "Довга назва прослуханої книги",
            "Озвучувач із довгим прізвищем", BookFeedbackDraft(), true, loading = false))
        var saves = 0
        var dismissals = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AudiobookTheme { BookFeedbackSheet(state, { state = state.copy(draft = it) },
                    { saves++; state = state.copy(failed = true) }, { dismissals++ }) }
            }
        }
        compose.onNodeWithTag("feedback_book_stars").performScrollTo()
        compose.onNode(hasTestTag("rating_star_2") and hasAnyAncestor(hasTestTag("feedback_book_stars"))).performClick()
        compose.onNodeWithTag("feedback_narration_stars").performScrollTo()
        compose.onNode(hasTestTag("rating_star_5") and hasAnyAncestor(hasTestTag("feedback_narration_stars"))).performClick()
        assertEquals(2, state.draft.bookRating)
        assertEquals(5, state.draft.narrationRating)
        compose.onNodeWithTag("feedback_save").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, saves)
        compose.onNodeWithTag("feedback_save").performScrollTo().assertIsEnabled().performClick()
        assertEquals(2, saves)
        compose.onNodeWithTag("feedback_dismiss").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, dismissals)
    }
}
