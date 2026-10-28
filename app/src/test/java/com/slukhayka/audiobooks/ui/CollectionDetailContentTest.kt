package com.slukhayka.audiobooks.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.collections.ListenerCollection
import com.slukhayka.audiobooks.data.collections.ListenerCollectionItem
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.screens.collections.CollectionDetailContent
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-51 (#690) — the collection screen's seam, including the delete gate.
 *
 * spec-46 T14 (колекційний зріз): the empty state is a resource now, so the
 * locale is explicit instead of inherited from the host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA", sdk = [36])
class CollectionDetailContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun collection(vararg books: Pair<String, String>) = ListenerCollection(
        id = "c1",
        title = "Магія",
        description = "про зорі",
        createdAt = 1L,
        items = books.map { (bookId, reason) -> ListenerCollectionItem(bookId, reason, 1L) }
    )

    private fun localBook(id: String, title: String, author: String = "Автор") = AudiobookEntity(
        id = id,
        title = title,
        author = author,
        narrator = "",
        description = "",
        coverDrawableRes = 0,
        genre = "",
        sourceUrl = "https://example.invalid/$id"
    )

    private fun setContent(
        collection: ListenerCollection,
        onRemoveBook: (String) -> Unit = {},
        onDelete: () -> Unit = {},
        booksById: Map<String, AudiobookEntity> = emptyMap(),
        onOpenBook: (String) -> Unit = {}
    ) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                CollectionDetailContent(
                    collection = collection,
                    onRemoveBook = onRemoveBook,
                    onDelete = onDelete,
                    booksById = booksById,
                    onOpenBook = onOpenBook
                )
            }
        }
    }

    @Test
    fun `the composition is listed in insertion order with its reasons`() {
        setContent(collection("book-a" to "бо атмосферно", "book-b" to ""))

        composeTestRule.onNodeWithText("Магія").assertIsDisplayed()
        composeTestRule.onNodeWithText("про зорі").assertIsDisplayed()
        composeTestRule.onNodeWithTag("collection_item_book-a").assertIsDisplayed()
        composeTestRule.onNodeWithTag("collection_item_book-b").assertIsDisplayed()
        composeTestRule.onNodeWithText("бо атмосферно").assertIsDisplayed()
    }

    @Test
    fun `items show resolved names, never the raw book id`() {
        setContent(
            collection("book-a" to "бо атмосферно", "book-b" to ""),
            booksById = mapOf(
                "book-a" to localBook("book-a", "Місто"),
                "book-b" to localBook("book-b", "Тигролови", author = "Іван Багряний")
            )
        )

        composeTestRule.onNodeWithText("Місто").assertIsDisplayed()
        composeTestRule.onNodeWithText("Тигролови").assertIsDisplayed()
        composeTestRule.onNodeWithText("Іван Багряний").assertIsDisplayed()
        composeTestRule.onNodeWithText("book-a").assertDoesNotExist()
        composeTestRule.onNodeWithText("book-b").assertDoesNotExist()
    }

    @Test
    fun `a book missing from the local library falls back to a human line`() {
        setContent(collection("book-a" to ""), booksById = emptyMap())

        composeTestRule
            .onNodeWithText(context.getString(R.string.collection_item_unknown))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("book-a").assertDoesNotExist()
    }

    @Test
    fun `tapping a resolved item opens the book`() {
        var opened: String? = null
        setContent(
            collection("book-a" to ""),
            booksById = mapOf("book-a" to localBook("book-a", "Місто")),
            onOpenBook = { opened = it }
        )

        composeTestRule.onNodeWithTag("collection_item_book-a").performClick()

        assertEquals("book-a", opened)
    }

    @Test
    fun `an empty composition says so instead of inventing books`() {
        setContent(collection())

        composeTestRule.onNodeWithTag("collection_empty").assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.collection_detail_empty))
            .assertIsDisplayed()
    }

    @Test
    fun `remove asks the caller once with the right book`() {
        var removed: String? = null
        setContent(collection("book-a" to ""), onRemoveBook = { removed = it })

        composeTestRule.onNodeWithTag("collection_item_remove_book-a").performClick()
        assertEquals("book-a", removed)
    }

    @Test
    fun `deleting asks first and cancelling destroys nothing`() {
        var deleted = false
        setContent(collection("book-a" to ""), onDelete = { deleted = true })

        composeTestRule.onNodeWithTag("collection_delete").performClick()
        composeTestRule.onNodeWithText("Видалити добірку?").assertIsDisplayed()

        composeTestRule.onNodeWithTag("collection_delete_cancel").performClick()
        assertEquals(false, deleted)
        // Cancelling must CLOSE the dialog without destroying anything.
        composeTestRule.onNodeWithText("Видалити добірку?").assertDoesNotExist()
        composeTestRule.onNodeWithTag("collection_detail").assertIsDisplayed()
    }

    @Test
    fun `confirming the deletion reaches the caller`() {
        var deleted = false
        setContent(collection("book-a" to ""), onDelete = { deleted = true })

        composeTestRule.onNodeWithTag("collection_delete").performClick()
        composeTestRule.onNodeWithTag("collection_delete_confirm").performClick()
        assertEquals(true, deleted)
    }
}
