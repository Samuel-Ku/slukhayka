package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.slukhayka.audiobooks.data.bibliography.BibliographyCandidate
import com.slukhayka.audiobooks.ui.screens.ManualBookAddSheet
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #857 (T4) — the corner's bibliography search, driven the way the listener
 * drives it.
 *
 * The device run showed the candidates arriving but no field being filled, and
 * tapping from `adb` could not say whether the button's handler ran or the tap
 * never landed. This test removes that ambiguity: it clicks the composable
 * directly, so a failure here is the handler's fault and a pass means the
 * device taps were the problem.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ManualBookAddSheetSearchTest {

    @get:Rule
    val compose = createComposeRule()

    /** The fixture the fake provider answers. */
    private val kobzar = BibliographyCandidate(
        title = "Кобзар",
        authors = listOf("Тарас Шевченко"),
        firstPublishYear = 1840,
        workKey = "/works/OL717725W",
        coverImageUrl = "https://covers.openlibrary.org/b/id/1-L.jpg"
    )

    private fun sheet(
        onTracked: (String, String, String?) -> Unit = { _, _, _ -> }
    ) {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                ManualBookAddSheet(
                    onAdd = {},
                    onDismiss = {},
                    onAddTracked = { t, a, c -> onTracked(t, a, c) },
                    onSearchBibliography = { listOf(kobzar) }
                )
            }
        }
    }

    @Test
    fun `a query returns candidates`() {
        sheet()

        compose.onNodeWithTag("manual_add_search_query").performTextInput("Kobzar")
        compose.onNodeWithTag("manual_add_search_go").performClick()

        compose.waitUntil(10_000) {
            runCatching {
                compose.onNodeWithTag("manual_add_candidate_/works/OL717725W")
                    .assertIsDisplayed()
            }.isSuccess
        }
        compose.onNodeWithText("Кобзар").assertIsDisplayed()
        compose.onNodeWithText("Тарас Шевченко · 1840").assertIsDisplayed()
    }

    @Test
    fun `picking a candidate fills the tracked fields`() {
        sheet()

        compose.onNodeWithTag("manual_add_search_query").performTextInput("Kobzar")
        compose.onNodeWithTag("manual_add_search_go").performClick()
        compose.waitUntil(10_000) {
            runCatching {
                compose.onNodeWithTag("manual_add_candidate_/works/OL717725W").assertIsDisplayed()
            }.isSuccess
        }

        compose.onNodeWithTag("manual_add_candidate_/works/OL717725W").performClick()

        // The whole point of the door: the listener does not retype metadata.
        // Assert on the FIELD's own text, not on the label: after the pick the
        // title exists twice on screen (the candidate row and the field), so a
        // bare `onNodeWithText` is ambiguous — which is exactly how this test
        // first "failed" while the feature actually worked.
        compose.onNodeWithTag("manual_add_name").assertTextContains("Кобзар")
        compose.onNodeWithTag("manual_add_author").assertTextContains("Тарас Шевченко")

        // The cover IS filled. This assertion first "failed" because
        // `assertTextContains` without `substring = true` demands the text be
        // EXACTLY equal — and I passed a fragment of the URL. The field's
        // EditableText was the whole `https://covers.openlibrary.org/...` all
        // along (confirmed by reading the semantics directly).
        compose.onNodeWithTag("manual_add_cover")
            .assertTextContains("covers.openlibrary.org", substring = true)
    }
}
