package com.slukhayka.audiobooks.ui.components.paperink

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.slukhayka.audiobooks.ui.icons.PaperInkIcons
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * F13 of the 2026-10-28 device audit — the bar wrapped its labels in a
 * `LocalDensity` with `fontScale.coerceAtMost(1.3f)`, so a reader who asked
 * for 200 % got 130 % labels (and §7 of the direction forbids clipping the
 * scale). The bar's two-row layout is the accessible answer; the labels now
 * follow the reader.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "uk-rUA-w411dp-h891dp", sdk = [35])
class PaperInkNavigationLargeTextTest {

    @get:Rule
    val compose = createComposeRule()

    private val items = listOf(
        PaperInkNavItem("Слухати", PaperInkIcons.Headphones, "tab_listen"),
        PaperInkNavItem("Огляд", PaperInkIcons.Compass, "tab_explore"),
        PaperInkNavItem("Бібліотека", PaperInkIcons.Library, "tab_library"),
        PaperInkNavItem("Друзі", PaperInkIcons.Friends, "tab_friends"),
    )

    @Test
    fun `labels follow two hundred percent text without ellipsis`() {
        assertLabelsNotEllipsized(fontScale = 2f)
    }

    @Test
    fun `labels follow one hundred fifty percent text without ellipsis`() {
        assertLabelsNotEllipsized(fontScale = 1.5f)
    }

    @Test
    fun `labels at normal text stay one line`() {
        showBar(fontScale = 1f)
        items.forEach { item ->
            val layout = labelLayout(item.testTag!!)
            assertEquals(1, layout.lineCount)
            assertEquals(false, layout.isLineEllipsized(0))
        }
    }

    private fun assertLabelsNotEllipsized(fontScale: Float) {
        showBar(fontScale)
        items.forEach { item ->
            val layout = labelLayout(item.testTag!!)
            assertEquals(
                "${item.label} at ${fontScale}x must allow two lines",
                2,
                layout.layoutInput.maxLines,
            )
            assertEquals(
                "${item.label} is ellipsized at ${fontScale}x: lines=${layout.lineCount} " +
                    "width=${layout.size.width}",
                false,
                layout.isLineEllipsized(layout.lineCount - 1),
            )
        }
    }

    private fun showBar(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = fontScale)
            ) {
                AudiobookTheme(darkTheme = false) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize()) {
                            PaperInkNavigationBar(items = items, selectedIndex = 0, onSelect = {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun labelLayout(navTag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("${navTag}_label", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(results)
        return requireNotNull(results.firstOrNull()) { "the label never reported its layout" }
    }
}
