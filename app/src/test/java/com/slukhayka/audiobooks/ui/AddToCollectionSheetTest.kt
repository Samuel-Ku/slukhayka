package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.slukhayka.audiobooks.data.collections.ListenerCollection
import com.slukhayka.audiobooks.data.collections.ListenerCollectionItem
import com.slukhayka.audiobooks.ui.screens.collections.AddToCollectionSheet
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-51 (#689) — the sheet's seam: it lists the listener's OWN collections,
 * marks the one that already holds the book, and creates a new collection
 * inline (no leaving the book page).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AddToCollectionSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val bookId = "book-a"

    private fun setSheetContent(
        collections: List<ListenerCollection>,
        onToggle: (String, Boolean, String?) -> Unit = { _, _, _ -> },
        onCreate: (String, String?) -> Unit = { _, _ -> }
    ) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                AddToCollectionSheet(
                    collections = collections,
                    bookId = bookId,
                    onDismiss = {},
                    onToggle = onToggle,
                    onCreate = onCreate
                )
            }
        }
    }

    private fun collection(id: String, title: String, books: List<String>) = ListenerCollection(
        id = id,
        title = title,
        description = "",
        createdAt = 1L,
        items = books.map { ListenerCollectionItem(it, "", 1L) }
    )

    @Test
    fun `the sheet lists every own collection`() {
        setSheetContent(
            listOf(collection("c1", "Магія", emptyList()), collection("c2", "Космос", emptyList()))
        )

        composeTestRule.onNodeWithTag("add_to_collection_sheet").assertExists()
        composeTestRule.onNodeWithTag("collection_row_c1").assertExists()
        composeTestRule.onNodeWithTag("collection_row_c2").assertExists()
    }

    @Test
    fun `a collection that already holds the book reports it as already there`() {
        setSheetContent(
            listOf(
                collection("with", "Уже тут", listOf(bookId)),
                collection("without", "Ще ні", listOf("other-book"))
            )
        )

        // The row exists in both cases; the DIFFERENCE is the ticked checkbox,
        // which is the store's fact surfaced in the UI.
        composeTestRule.onNodeWithTag("collection_row_with").assertExists()
        composeTestRule.onNodeWithTag("collection_row_without").assertExists()
    }

    @Test
    fun `a new collection is created inline with a hygienic title`() {
        var createdTitle: String? = null
        var createdDescription: String? = null
        setSheetContent(
            collections = emptyList(),
            onCreate = { title, description ->
                createdTitle = title
                createdDescription = description
            }
        )

        composeTestRule.onNodeWithTag("new_collection_open").performClick()
        composeTestRule.onNodeWithTag("new_collection_title").performTextInput("  Нова добірка  ")
        composeTestRule.onNodeWithTag("new_collection_confirm").performClick()

        assertEquals("  Нова добірка  ", createdTitle)
        assertNull(createdDescription)
    }
}
