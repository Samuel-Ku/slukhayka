package com.slukhayka.audiobooks.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.authors.AuthorSummary
import com.slukhayka.audiobooks.ui.screens.AuthorsIndexContent
import com.slukhayka.audiobooks.ui.screens.BookListScreen
import com.slukhayka.audiobooks.ui.screens.CanonicalAuthorContent
import com.slukhayka.audiobooks.ui.screens.CollectionsIndexContent
import com.slukhayka.audiobooks.ui.screens.PeopleContent
import com.slukhayka.audiobooks.ui.screens.SeriesIndexContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * spec-46 T13 (#574) — the screen-state facade contract.
 *
 * ADR-0033 left three named wrappers over the canonical states in
 * `IndexScreenScaffold.kt` (`IndexEmptyState`, `SecondaryLoadingState`,
 * `SecondaryMessageState`) as delegating facades. They gave the caller nothing
 * the canonical `EmptyState` does not already provide, so T13 deletes them and
 * routes every call site through the canonical component. Two things must hold
 * afterwards:
 *
 * 1. «жодного старого імені в коді» — the three names stay deleted. A facade
 *    that is merely "unused" still documents a state the app does not ship and
 *    the next screen would revive it instead of reaching for `EmptyState`; the
 *    scan is the same source-guard style as [PersonBooksCanonicalRowGuardTest].
 * 2. The behaviour the facades owned survives the migration: every state is a
 *    polite live-region, the loading state is a real progress announcement and
 *    the error state announces «Помилка» on the same node. The behaviour class
 *    is green before and after the deletion — it is the proof the migration
 *    changed names, not conduct.
 */
class CanonicalStateFacadesGuardTest {

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
    fun `the screen-state facades stay deleted`() {
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val hits = OLD_STATE_FACADES.filter { file.readText().contains(it) }
                if (hits.isEmpty()) null else file.relativeTo(sourceRoot).invariantSeparatorsPath
            }
            .toList()

        assertTrue(
            "the canonical-state facades must stay deleted — found in: $offenders",
            offenders.isEmpty()
        )
    }

    /**
     * The canonical error flavour («Помилка» via `stateDescription`) belongs to
     * genuinely failed states only. After T13 every consumer renders
     * `EmptyState` inline, so the flavour is one line each — and exactly the
     * five failure branches may carry it: the author page, the shared book
     * list, the library rating, the people index and the series page. A
     * successful empty (no collections, no series, no authors, no rated books)
     * must stay silent about an error that did not happen.
     */
    @Test
    fun `the error flavour rides only the genuinely failed states`() {
        val carriers = CONSUMER_SURFACES.filter { read(it).contains(ERROR_FLAVOUR) }.toSet()

        assertEquals(EXPECTED_ERROR_SURFACES, carriers)
    }

    private fun read(path: String): String {
        val file = File(sourceRoot, path)
        check(file.isFile) { "source file not found: $file" }
        return file.readText()
    }

    private companion object {
        val OLD_STATE_FACADES = listOf(
            "IndexEmptyState",
            "SecondaryLoadingState",
            "SecondaryMessageState"
        )

        /** The exact canonical error announcement the failure branches pass. */
        const val ERROR_FLAVOUR =
            "stateDescription = stringResource(R.string.secondary_state_error)"

        val CONSUMER_SURFACES = listOf(
            "ui/screens/AuthorDiscovery.kt",
            "ui/screens/BookListScreen.kt",
            "ui/screens/CollectionsIndexScreen.kt",
            "ui/screens/LibraryRatingScreen.kt",
            "ui/screens/PeopleScreen.kt",
            "ui/screens/SeriesIndexScreen.kt",
            "ui/screens/SeriesScreen.kt"
        )

        val EXPECTED_ERROR_SURFACES = setOf(
            "ui/screens/AuthorDiscovery.kt",
            "ui/screens/BookListScreen.kt",
            "ui/screens/LibraryRatingScreen.kt",
            "ui/screens/PeopleScreen.kt",
            "ui/screens/SeriesScreen.kt"
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "uk-rUA-w411dp-h891dp")
class CanonicalStateFacadesBehaviorTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun string(id: Int): String = context.getString(id)

    /** The canonical empty/loading/error state is a polite live-region; a bare Text is not. */
    private fun announcesItselfPolitely() = SemanticsMatcher.expectValue(
        SemanticsProperties.LiveRegion,
        LiveRegionMode.Polite
    )

    @Test
    fun `the author index empty message is one polite canonical state`() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                AuthorsIndexContent(
                    authors = emptyList(),
                    onAuthorClick = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        compose.onNodeWithText(string(R.string.author_empty_catalog))
            .assertIsDisplayed()
            .assert(announcesItselfPolitely())
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun `the collections index empty message is one polite canonical state`() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                CollectionsIndexContent(
                    collections = emptyList(),
                    onBookClick = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        compose.onNodeWithText(string(R.string.collections_index_empty))
            .assertIsDisplayed()
            .assert(announcesItselfPolitely())
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun `the series index empty message is one polite canonical state`() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                SeriesIndexContent(
                    series = emptyList(),
                    onSeriesClick = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        compose.onNodeWithText(string(R.string.series_index_empty))
            .assertIsDisplayed()
            .assert(announcesItselfPolitely())
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun `the shared book list loading state keeps its progress announcement`() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                BookListScreen(
                    title = "Фентезі",
                    countLabel = null,
                    emptyMessage = "Немає книг.",
                    isLoading = true,
                    books = emptyList(),
                    onBackClick = {},
                    onBookClick = {},
                    onPlayClick = {},
                    testTag = "canonical_facades_loading"
                )
            }
        }

        compose.onNodeWithContentDescription(string(R.string.secondary_loading))
            .assert(
                SemanticsMatcher.keyIsDefined(
                    SemanticsProperties.ProgressBarRangeInfo
                )
            )
        compose.onNodeWithText(string(R.string.secondary_loading))
            .assert(announcesItselfPolitely())
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun `the shared book list error state is one polite state that says Помилка`() {
        val error = "Не вдалося завантажити книги."
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                BookListScreen(
                    title = "Фентезі",
                    countLabel = null,
                    emptyMessage = "Книг немає.",
                    isLoading = false,
                    books = emptyList(),
                    onBackClick = {},
                    onBookClick = {},
                    onPlayClick = {},
                    testTag = "canonical_facades_error",
                    errorMessage = error
                )
            }
        }

        compose.onNodeWithText(error)
            .assertIsDisplayed()
            .assert(announcesItselfPolitely())
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    string(R.string.secondary_state_error)
                )
            )
        compose.onNodeWithText("Книг немає.").assertDoesNotExist()
    }

    @Test
    fun `the shared book list successful empty carries no error flavour`() {
        val empty = "Для цієї людини поки немає книг."
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                BookListScreen(
                    title = "Книги авторки",
                    countLabel = null,
                    emptyMessage = empty,
                    isLoading = false,
                    books = emptyList(),
                    onBackClick = {},
                    onBookClick = {},
                    onPlayClick = {},
                    testTag = "canonical_facades_empty",
                    errorMessage = null
                )
            }
        }

        compose.onNodeWithText(empty)
            .assertIsDisplayed()
            .assert(announcesItselfPolitely())
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun `the people index failed state announces the canonical error flavour`() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                PeopleContent(
                    people = emptyList(),
                    isLoading = false,
                    loadFailed = true,
                    onPersonClick = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        compose.onNodeWithText(string(R.string.secondary_people_error))
            .assertIsDisplayed()
            .assert(announcesItselfPolitely())
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    string(R.string.secondary_state_error)
                )
            )
    }

    @Test
    fun `an empty people list carries no error flavour`() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                PeopleContent(
                    people = emptyList(),
                    isLoading = false,
                    loadFailed = false,
                    onPersonClick = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        compose.onNodeWithText(string(R.string.secondary_people_empty))
            .assertIsDisplayed()
            .assert(announcesItselfPolitely())
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun `the author page failure announces the canonical error flavour`() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                CanonicalAuthorContent(
                    author = AuthorSummary(
                        id = "lesia-ukrainka",
                        displayName = "Леся Українка",
                        normalizedName = "леся українка",
                        workCount = 12
                    ),
                    works = emptyList(),
                    onWorkClick = {},
                    loadFailed = true,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        compose.onNodeWithText(string(R.string.author_load_failed))
            .assertIsDisplayed()
            .assert(announcesItselfPolitely())
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    string(R.string.secondary_state_error)
                )
            )
    }

    @Test
    fun `a successful author empty carries no error flavour`() {
        compose.setContent {
            AudiobookTheme(darkTheme = true) {
                CanonicalAuthorContent(
                    author = AuthorSummary(
                        id = "lesia-ukrainka",
                        displayName = "Леся Українка",
                        normalizedName = "леся українка",
                        workCount = 0
                    ),
                    works = emptyList(),
                    onWorkClick = {},
                    loadFailed = false,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        compose.onNodeWithText(string(R.string.author_empty_works))
            .assertIsDisplayed()
            .assert(announcesItselfPolitely())
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }
}
