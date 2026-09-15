package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slukhayka.audiobooks.ui.screens.collections.MyCollectionRow
import com.slukhayka.audiobooks.ui.screens.collections.MyCollectionsBlock
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec-51 (#690) — the Library block's seam: honest empty state, real rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MyCollectionsBlockTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setBlock(rows: List<MyCollectionRow>, onOpen: (String) -> Unit = {}) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                MyCollectionsBlock(rows = rows, onOpen = onOpen)
            }
        }
    }

    @Test
    fun `with no collections the block renders nothing and invents nothing`() {
        setBlock(emptyList())

        // UI: порожній блок більше не показується взагалі — «Мої добірки» +
        // «Ще немає добірок» займали два рядки й нічого не пропонували.
        composeTestRule.onNodeWithTag("my_collections_block").assertDoesNotExist()
        composeTestRule.onNodeWithTag("my_collections_empty").assertDoesNotExist()
        composeTestRule.onNodeWithText("Ще немає добірок").assertDoesNotExist()
    }

    @Test
    fun `rows carry the real title and count and open the collection`() {
        var openedId: String? = null
        setBlock(
            rows = listOf(
                MyCollectionRow(id = "c1", title = "Магія", bookCount = 3),
                MyCollectionRow(id = "c2", title = "Космос", bookCount = 1)
            ),
            onOpen = { openedId = it }
        )

        composeTestRule.onNodeWithTag("my_collection_row_c1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Магія").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 книг").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ще немає добірок").assertDoesNotExist()

        composeTestRule.onNodeWithTag("my_collection_row_c2").performClick()
        assertEquals("c2", openedId)
    }
}
