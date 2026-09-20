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
import com.slukhayka.audiobooks.data.authors.AuthorSummary
import com.slukhayka.audiobooks.data.catalog.CatalogPerson
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.ui.screens.AuthorsIndexContent
import com.slukhayka.audiobooks.ui.screens.PeopleContent
import com.slukhayka.audiobooks.ui.screens.SeriesIndexContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * spec-46 T14 (#575) — the E6 index screens' chrome must survive the English
 * run.
 *
 * #575's acceptance list names «Серії, Колекції, ТОП 100, Виконавці» plus the
 * author and genre indices and requires the UK/EN walk to be clean. The
 * counters moved into the canonical subtitle and the scaffolds collapsed, but
 * three chrome strings were left as hardcoded Ukrainian literals — the series
 * placeholder, the people-index backfill notice and the genre counter — and
 * the author/people row counts hand-rolled the Ukrainian plural helper. The
 * default locale is Ukrainian, so every one of them compiled and rendered
 * correctly under the UK run and only leaked once the app spoke English.
 *
 * The proof walks the WHOLE semantics tree in `en-rUS` and fails on any
 * Cyrillic (the [OverviewEnglishChromeTest] pattern); fixtures use Latin-only
 * data, so a failure can only come from chrome the app itself produced. The
 * genre counter is asserted at the source seam because its screen needs a
 * `MainViewModel` that a unit test cannot cheaply build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS", sdk = [36])
class IndexScreensEnglishChromeTest {

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
    fun `the series index placeholder speaks English`() {
        composeTestRule.setContent {
            chrome { SeriesIndexContent(series = emptyList(), onSeriesClick = {}) }
        }

        assertChromeHasNoCyrillic()
        composeTestRule.onNodeWithText(context.getString(R.string.series_index_empty)).assertExists()
    }

    @Test
    fun `the people index backfill notice and rows speak English`() {
        composeTestRule.setContent {
            chrome {
                PeopleContent(
                    people = listOf(
                        CatalogPerson(
                            name = "Jane Doe",
                            path = "/xfsearch/chitaet/Jane/",
                            bookCount = 5,
                            role = PersonRole.NARRATOR
                        )
                    ),
                    isLoading = false,
                    loadFailed = false,
                    onPersonClick = {},
                    indexBackfillPending = true,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        assertChromeHasNoCyrillic()
        composeTestRule.onNodeWithText(context.getString(R.string.people_index_backfill)).assertExists()
        composeTestRule.onNodeWithText("5 books").assertExists()
    }

    @Test
    fun `the author index row count speaks English`() {
        composeTestRule.setContent {
            chrome {
                AuthorsIndexContent(
                    authors = listOf(
                        AuthorSummary(
                            id = "jane-doe",
                            displayName = "Jane Doe",
                            normalizedName = "jane doe",
                            workCount = 5
                        )
                    ),
                    onAuthorClick = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        assertChromeHasNoCyrillic()
        composeTestRule.onNodeWithText("5 books").assertExists()
    }

    @Test
    fun `the genre index counter is a wired resource with an English twin`() {
        val source = read("ui/screens/GenreScreen.kt")

        assertTrue(
            "GenreScreen must build its count from R.plurals.genre_book_count: $source",
            source.contains("pluralStringResource(R.plurals.genre_book_count")
        )
        assertTrue(
            "GenreScreen must not hand-roll the Ukrainian plural helper",
            !source.contains("ukPlural(")
        )
        listOf(1, 2, 5).forEach { count ->
            val label = context.resources.getQuantityString(R.plurals.genre_book_count, count, count)
            assertTrue("the genre counter leaked Ukrainian into EN: $label", !cyrillic.containsMatchIn(label))
        }
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
        assertTrue("Ukrainian chrome leaked into the EN index run: $leaked", leaked.isEmpty())
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
