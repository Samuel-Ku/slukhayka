package com.slukhayka.audiobooks.ui.components.paperink

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.theme.PaperInkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1703 — «Не цікаво» on a Listen shelf kept a 32 dp circle as its WHOLE
 * target: the quiet visual had become the interactive zone, below the 48 dp
 * floor the legacy `PosterCard` honoured (#372).
 *
 * The fix is the legacy shape: a 32 dp visible disc centred inside a 48 dp
 * target. Both halves are pinned here, because shrinking either one back is
 * the regression — the visual must stay quiet, and the target must stay
 * reachable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA-w411dp-h891dp", sdk = [36])
class PaperInkDismissTargetTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the dismiss action keeps a 48 dp target around the 32 dp disc`() {
        var clicks = 0
        compose.setContent {
            PaperInkTheme(darkTheme = true) {
                PaperInkPosterCard(
                    title = "Нейромант",
                    author = "Вільям Гібсон",
                    meta = "17 год 59 хв",
                    onNotInterested = { clicks++ },
                    dismissContentDescription = "Не цікаво: Нейромант",
                    dismissTestTag = "not_interested_book-1",
                )
            }
        }

        val target = compose.onNodeWithTag("not_interested_book-1")
        target
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        // The visual stays the 32 dp disc: the extra area is quiet.
        compose.onNodeWithTag("not_interested_visual_book-1", useUnmergedTree = true)
            .assertWidthIsEqualTo(32.dp)

        target.performClick()
        assertEquals("the dismiss callback still fires from the target", 1, clicks)
    }
}
