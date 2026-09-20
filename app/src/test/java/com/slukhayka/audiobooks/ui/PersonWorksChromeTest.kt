package com.slukhayka.audiobooks.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.testing.EnglishChromeWalk
import com.slukhayka.audiobooks.ui.library.PersonWorkRow
import com.slukhayka.audiobooks.ui.screens.PersonWorksContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec-46 T05 (#566) — the person page's Work rows are the canonical [com.slukhayka.audiobooks.ui.components.BookRow]
 * and every count is chrome from the resources.
 *
 * The two locale methods render the SAME Latin-only fixtures, so:
 * - the EN run proves no Ukrainian leaked into an English interface (the row
 *   used to hardcode «начиток»), and
 * - the UK run proves the count still reads naturally («2 начитки»), i.e. the
 *   migration moved the string rather than deleting it.
 *
 * Asserting a couple of labels would still pass with one hardcoded corner left,
 * so the EN method also walks the WHOLE semantics tree and fails on any
 * Cyrillic, exactly like [LibraryEnglishChromeTest].
 *
 * #986 — the walk itself is the shared [EnglishChromeWalk], so it reads every
 * root and PaneTitle too; this slice used to keep a narrower private copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersonWorksChromeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @Config(qualifiers = "en-rUS", sdk = [36])
    fun englishRunRendersCanonicalRowsAndNoUkrainianChrome() {
        var opened: String? = null
        composeTestRule.setContent {
            PersonWorksSurface {
                PersonWorksContent(
                    works = fixtureWorks,
                    isLoading = false,
                    loadFailed = false,
                    onBookClick = { opened = it }
                )
            }
        }

        assertNoCyrillic()

        val multi = composeTestRule.onNodeWithTag("person_work_work-a")
        // BookRow merges its descendants: one node carries the title, the
        // people line, the narration count and the ownership badge.
        multi.assertTextContains("The Kobzar")
        multi.assertTextContains("Taras Shevchenko", substring = true)
        multi.assertTextContains("2 narrations")
        multi.assertTextContains("In the library")
        multi.performClick()
        assertEquals("card-1", opened)

        // One merged tap target for the Work, not one per child; a Work with
        // no narration of ours is not tappable at all.
        composeTestRule.onAllNodes(hasClickAction(), useUnmergedTree = true)
            .assertCountEquals(1)
        composeTestRule.onNodeWithTag("person_work_work-b").assertHasNoClickAction()

        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        assertEquals("1 work", resources.getQuantityString(R.plurals.person_works_count, 1, 1))
        assertEquals("2 works", resources.getQuantityString(R.plurals.person_works_count, 2, 2))
    }

    @Test
    @Config(qualifiers = "uk-rUA", sdk = [36])
    fun ukrainianRunRendersTheSameRowsFromResources() {
        composeTestRule.setContent {
            PersonWorksSurface {
                PersonWorksContent(
                    works = fixtureWorks,
                    isLoading = false,
                    loadFailed = false,
                    onBookClick = {}
                )
            }
        }

        val multi = composeTestRule.onNodeWithTag("person_work_work-a")
        multi.assertTextContains("2 начитки")
        multi.assertTextContains("У Медіатеці")

        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        assertEquals("1 твір", resources.getQuantityString(R.plurals.person_works_count, 1, 1))
        assertEquals("2 твори", resources.getQuantityString(R.plurals.person_works_count, 2, 2))
        assertEquals("5 творів", resources.getQuantityString(R.plurals.person_works_count, 5, 5))
    }

    private fun assertNoCyrillic() {
        // #986 — one shared walk, every root, PaneTitle included.
        EnglishChromeWalk.assertNoCyrillic(composeTestRule, "person works")
    }

    private fun card(id: String, narrator: String) = AudiobookEntity(
        id = id,
        title = "The Kobzar",
        author = "Taras Shevchenko",
        narrator = narrator,
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = "https://example.invalid/$id"
    )

    /**
     * One Work with two narrations (the «several narrations» count) that the
     * listener owns, and one Work with no narration of ours at all. Latin-only
     * data: any Cyrillic left in the tree is chrome, not content.
     */
    private val fixtureWorks = listOf(
        PersonWorkRow(
            workId = "work-a",
            title = "The Kobzar",
            author = "Taras Shevchenko",
            narrator = "Ivan Franko",
            ownedInLibrary = true,
            narrations = listOf(card("card-1", "Ivan Franko"), card("card-2", "Lesya Ukrainka")),
            coverUrl = "https://example.invalid/kobzar.jpg"
        ),
        PersonWorkRow(
            workId = "work-b",
            title = "Haydamaky",
            author = "Taras Shevchenko",
            narrator = null,
            ownedInLibrary = false,
            narrations = emptyList(),
            coverUrl = null
        )
    )
}

@androidx.compose.runtime.Composable
private fun PersonWorksSurface(content: @androidx.compose.runtime.Composable () -> Unit) {
    AudiobookTheme(darkTheme = true) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}
