package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.ui.screens.WebSourceBrowserScreen
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1774 — «Довгі рядки не обрізаються після перекладу»: the Polish import
 * action is the longest of the three locales and shares its row with five
 * 48 dp toolbar buttons, so it is the one label a translation could push into
 * an ellipsis. Every other new string is a wrapping paragraph in a full-width
 * column.
 *
 * The measure is Compose's own `GetTextLayoutResult` — the same visual fact
 * `LibraryTabsFitTest` pins — not a screenshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "pl-rPL", sdk = [36])
class WebSourceBrowserLongCopyFitTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<App>()

    private fun setScreen(width: Dp) {
        val viewModel = MainViewModel(app)
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Box(Modifier.width(width).height(900.dp)) {
                    WebSourceBrowserScreen(
                        viewModel = viewModel,
                        sourceId = "soundbooks",
                        homeUrl = "https://sound-books.net/",
                        displayName = "Sound-Books",
                        onClose = {}
                    )
                }
            }
        }
    }

    private fun assertNoEllipsis(label: String, width: Dp) {
        setScreen(width)
        val layouts = mutableListOf<TextLayoutResult>()
        composeTestRule
            .onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val ellipsizedLine = (0 until layout.lineCount).firstOrNull { layout.isLineEllipsized(it) }
        assertFalse(
            "«$label» еліпситься на ${width.value} dp " +
                "(line=$ellipsizedLine of ${layout.lineCount}, size=${layout.size})",
            ellipsizedLine != null
        )
    }

    @Test
    fun polishImportActionFitsOnAPhoneWidth() {
        assertNoEllipsis("Dodaj do biblioteki", 411.dp)
    }

    @Test
    fun polishImportActionFitsOnANarrowPhoneWidth() {
        assertNoEllipsis("Dodaj do biblioteki", 360.dp)
    }
}
