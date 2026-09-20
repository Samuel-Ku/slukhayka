package com.slukhayka.audiobooks.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.universe.SeriesRef
import com.slukhayka.audiobooks.data.universe.SeriesUniverseContext
import com.slukhayka.audiobooks.ui.screens.SeriesUniverseHeader
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * spec-46 (#975 follow-up) — the series screen's universe header chrome must
 * survive the English run.
 *
 * The default locale is Ukrainian, so the two lines the header built inline —
 * «Всесвіт: «…»» and «Цикл N з M» — compiled and rendered correctly under the
 * UK run and only leaked once the app spoke English. They are chrome: the
 * universe line reuses the existing `book_detail_universe_line`, and the
 * position line got a dedicated `series_cycle_position` with an English twin.
 *
 * The proof walks the WHOLE semantics tree in `en-rUS` and fails on any
 * Cyrillic (the [IndexScreensEnglishChromeTest] pattern); the fixture uses
 * Latin-only data, so a failure can only come from chrome the app itself
 * produced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS", sdk = [36])
class SeriesScreenEnglishChromeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val cyrillic = Regex("[А-Яа-яІіЇїЄєҐґ]")

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val sourceRoot: File by lazy {
        // Gradle runs unit tests with cwd = app/.
        val candidates = listOf(
            File(System.getProperty("user.dir"), "src/main/java/com/slukhayka/audiobooks"),
            File(System.getProperty("user.dir"), "app/src/main/java/com/slukhayka/audiobooks")
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("source root not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `the universe header chrome speaks English`() {
        composeTestRule.setContent {
            chrome {
                SeriesUniverseHeader(
                    context = SeriesUniverseContext(
                        universeName = "Foundation",
                        seriesTitle = "Foundation",
                        position = 2,
                        totalInUniverse = 3,
                        precedes = SeriesRef("Prelude", "https://4read.org/xfsearch/cikl/prelude/"),
                        follows = SeriesRef("Forward", "https://4read.org/xfsearch/cikl/forward/")
                    ),
                    onOpenSeries = {}
                )
            }
        }

        assertChromeHasNoCyrillic()
        composeTestRule
            .onNodeWithText(context.getString(R.string.book_detail_universe_line, "Foundation"))
            .assertExists()
        composeTestRule
            .onNodeWithText(context.getString(R.string.series_cycle_position, 2, 3))
            .assertExists()
    }

    @Test
    fun `SeriesScreen builds both header lines from resources`() {
        val source = read("ui/screens/SeriesScreen.kt")

        assertTrue(
            "SeriesScreen must build the universe line from R.string.book_detail_universe_line: $source",
            source.contains("stringResource(R.string.book_detail_universe_line")
        )
        assertTrue(
            "SeriesScreen must build the position line from R.string.series_cycle_position",
            source.contains("R.string.series_cycle_position")
        )
        assertTrue(
            "SeriesScreen must not keep the Ukrainian universe literal",
            !source.contains("\"Всесвіт:")
        )
        assertTrue(
            "SeriesScreen must not keep the Ukrainian position literal",
            !source.contains("\"Цикл ")
        )
        assertTrue(
            "SeriesScreen must not import the dead ukPlural helper",
            !source.contains("ukPlural")
        )
    }

    @Test
    fun `the cycle position resource has an English twin`() {
        val label = context.getString(R.string.series_cycle_position, 2, 3)
        assertTrue("the cycle position leaked Ukrainian into EN: $label", !cyrillic.containsMatchIn(label))
    }

    private fun read(path: String): String {
        val file = File(sourceRoot, path)
        check(file.isFile) { "source file not found: $file" }
        return file.readText()
    }

    @Composable
    private fun chrome(content: @Composable () -> Unit) {
        AudiobookTheme(darkTheme = true) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                content()
            }
        }
    }

    private fun assertChromeHasNoCyrillic() {
        val texts = collectTexts(composeTestRule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        val leaked = texts.filter { cyrillic.containsMatchIn(it) }
        assertTrue("Ukrainian chrome leaked into the EN series run: $leaked", leaked.isEmpty())
    }

    private fun collectTexts(node: SemanticsNode): List<String> {
        val out = mutableListOf<String>()
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
        node.config.getOrNull(SemanticsProperties.StateDescription)?.let { out += it }
        node.children.forEach { out += collectTexts(it) }
        return out
    }
}
