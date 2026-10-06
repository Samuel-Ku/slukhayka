package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.data.achievements.ShowcaseAwardSnapshot
import com.slukhayka.audiobooks.ui.screens.collections.CollectionWithBookRow
import com.slukhayka.audiobooks.ui.screens.collections.CuratorProfileContent
import com.slukhayka.audiobooks.ui.screens.collections.PublicCollectionsRail
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec-51 (#693) — the rail and the curator profile are honest shelves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PublicCollectionsRailTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val rated = CollectionWithBookRow(
        documentId = "doc-1",
        authorId = "author-1",
        title = "Магія",
        pseudonym = "Слухач",
        average = 4.5,
        ratingCount = 2
    )

    private fun setRail(
        rows: List<CollectionWithBookRow>,
        onOpen: (String) -> Unit = {},
        onOpenCurator: (String) -> Unit = {}
    ) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                PublicCollectionsRail(rows = rows, onOpen = onOpen, onOpenCurator = onOpenCurator)
            }
        }
    }

    @Test
    fun `no rows means no rail at all`() {
        setRail(emptyList())
        composeTestRule.onNodeWithTag("public_collections_rail").assertDoesNotExist()
    }

    @Test
    fun `the rail names the shelf and shows the shared ranking data`() {
        setRail(listOf(rated))

        composeTestRule.onNodeWithText("Добірки слухачів").assertIsDisplayed()
        composeTestRule.onNodeWithText("Магія").assertIsDisplayed()
        composeTestRule.onNodeWithText("★ 4.5 · 2 оцінки").assertIsDisplayed()
    }

    @Test
    fun `the rail opens a collection and its curator`() {
        var opened: String? = null
        var curator: String? = null
        setRail(listOf(rated), onOpen = { opened = it }, onOpenCurator = { curator = it })

        composeTestRule.onNodeWithTag("rail_collection_doc-1").performClick()
        assertEquals("doc-1", opened)

        composeTestRule.onNodeWithTag("rail_curator_doc-1").performClick()
        assertEquals("author-1", curator)
    }

    @Test
    fun `the curator profile lists visible collections and opens them`() {
        var opened: String? = null
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CuratorProfileContent(
                    pseudonym = "Слухач",
                    rows = listOf(rated),
                    onOpen = { opened = it }
                )
            }
        }

        composeTestRule.onNodeWithTag("curator_profile_pseudonym").assertIsDisplayed()
        composeTestRule.onNodeWithText("Слухач").assertIsDisplayed()
        composeTestRule.onNodeWithTag("curator_collection_doc-1").performClick()
        assertEquals("doc-1", opened)
    }

    @Test
    fun `a curator with nothing visible says so honestly`() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CuratorProfileContent(pseudonym = "Слухач", rows = emptyList(), onOpen = {})
            }
        }

        composeTestRule.onNodeWithTag("curator_profile_empty").assertIsDisplayed()
    }

    private fun setProfile(
        rows: List<CollectionWithBookRow>,
        showcase: List<ShowcaseAwardSnapshot>
    ) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CuratorProfileContent(
                    pseudonym = "Слухач",
                    rows = rows,
                    showcase = showcase,
                    onOpen = {}
                )
            }
        }
    }

    @Test
    fun `the profile shows the awards the curator published`() {
        setProfile(
            rows = listOf(rated),
            showcase = listOf(
                ShowcaseAwardSnapshot("first_book", "Перша книга"),
                ShowcaseAwardSnapshot("night_watch", "Нічний вартовий")
            )
        )

        composeTestRule.onNodeWithTag("curator_profile_showcase").assertIsDisplayed()
        composeTestRule.onNodeWithTag("curator_profile_showcase_heading").assertIsDisplayed()
        composeTestRule.onNodeWithTag("curator_profile_award_first_book").assertIsDisplayed()
        composeTestRule.onNodeWithTag("curator_profile_award_night_watch").assertIsDisplayed()
        composeTestRule.onNodeWithText("Нічний вартовий").assertIsDisplayed()
    }

    @Test
    fun `a curator who published no showcase gets no showcase section`() {
        setProfile(rows = listOf(rated), showcase = emptyList())

        // Not merely empty: absent. An empty heading would be a shelf.
        composeTestRule.onNodeWithTag("curator_profile_showcase").assertDoesNotExist()
        composeTestRule.onNodeWithTag("curator_collection_doc-1").assertIsDisplayed()
    }

    @Test
    fun `a profile with no publications shows no showcase even if one is handed in`() {
        // #705 criterion 4, as a structural rule rather than an implication:
        // the showcase rides on the collections, so a profile with none cannot
        // show awards however the caller calls it.
        setProfile(
            rows = emptyList(),
            showcase = listOf(ShowcaseAwardSnapshot("first_book", "Перша книга"))
        )

        composeTestRule.onNodeWithTag("curator_profile_empty").assertIsDisplayed()
        composeTestRule.onNodeWithTag("curator_profile_showcase").assertDoesNotExist()
        composeTestRule.onNodeWithTag("curator_profile_award_first_book").assertDoesNotExist()
    }
}
