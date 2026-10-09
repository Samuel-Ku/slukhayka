package com.slukhayka.audiobooks.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.slukhayka.audiobooks.data.collections.ListenerCollection
import com.slukhayka.audiobooks.data.collections.ListenerCollectionItem
import com.slukhayka.audiobooks.ui.screens.collections.ListenerCollectionPage
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1154 — the page that opens a listener's OWN collection.
 *
 * This class exists because of HOW the defect hid. [CollectionDetailContent] was
 * written, correct and tested; the library reported a tapped row into a state
 * variable; and nothing read that variable. Every piece had a test and the
 * feature still did not work, because what was missing was the CALL.
 *
 * So the page is a piece with the open id as an INPUT, and these tests render it
 * with one. A future regression that stops passing the id — or stops rendering
 * the page — fails here rather than silently in the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA", sdk = [36])
class ListenerCollectionPageTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun collection(id: String) = ListenerCollection(
        id = id,
        title = "Магія",
        description = "про зорі",
        createdAt = 1L,
        items = listOf(
            ListenerCollectionItem("book-a", "бо атмосферно", 1L),
            ListenerCollectionItem("book-b", "", 2L)
        )
    )

    private fun setPage(
        collections: List<ListenerCollection>,
        openId: String?,
        onClose: () -> Unit = {},
        onRemoveBook: (String, String) -> Unit = { _, _ -> },
        onDelete: (String) -> Unit = {},
        onPublish: (String, String) -> Unit = { _, _ -> }
    ) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                ListenerCollectionPage(
                    collections = collections,
                    openId = openId,
                    onClose = onClose,
                    onRemoveBook = onRemoveBook,
                    onDelete = onDelete,
                    onPublish = onPublish
                )
            }
        }
    }

    @Test
    fun `the open collection is shown as its own page`() {
        setPage(collections = listOf(collection("c1")), openId = "c1")

        composeTestRule.onNodeWithTag("collection_detail_page").assertIsDisplayed()
        composeTestRule.onNodeWithTag("collection_detail").assertIsDisplayed()
        composeTestRule.onNodeWithText("Магія").assertIsDisplayed()
        composeTestRule.onNodeWithTag("collection_item_book-a").assertIsDisplayed()
    }

    @Test
    fun `nothing open means no page at all`() {
        setPage(collections = listOf(collection("c1")), openId = null)

        composeTestRule.onNodeWithTag("collection_detail_page").assertDoesNotExist()
        composeTestRule.onNodeWithTag("collection_detail").assertDoesNotExist()
    }

    @Test
    fun `an id that matches no collection opens nothing, not a ghost`() {
        // The collection can disappear under an open page — deleted, or a
        // refresh landed. A page for it would be a page for something that is
        // not there.
        setPage(collections = listOf(collection("c1")), openId = "c2")

        composeTestRule.onNodeWithTag("collection_detail_page").assertDoesNotExist()
    }

    @Test
    fun `closing reports back so the caller can drop the open id`() {
        var closed = false
        setPage(collections = listOf(collection("c1")), openId = "c1", onClose = { closed = true })

        composeTestRule.onNodeWithTag("collection_detail_page_back").performClick()

        assertEquals(true, closed)
    }

    @Test
    fun `removing a book reports the collection AND the book`() {
        var removed: Pair<String, String>? = null
        setPage(
            collections = listOf(collection("c1")),
            openId = "c1",
            onRemoveBook = { collectionId, bookId -> removed = collectionId to bookId }
        )

        composeTestRule.onNodeWithTag("collection_item_remove_book-b").performClick()

        // The page knows which collection it is showing, so the caller is never
        // asked to guess it from the screen state.
        assertEquals("c1" to "book-b", removed)
    }

    @Test
    fun `deleting reports the collection only after the explicit confirm`() {
        var deleted: String? = null
        setPage(
            collections = listOf(collection("c1")),
            openId = "c1",
            onDelete = { deleted = it }
        )

        composeTestRule.onNodeWithTag("collection_delete").performClick()
        assertNull("opening the confirmation must destroy nothing", deleted)

        composeTestRule.onNodeWithTag("collection_delete_cancel").performClick()
        assertNull("cancelling must destroy nothing", deleted)

        composeTestRule.onNodeWithTag("collection_delete").performClick()
        composeTestRule.onNodeWithTag("collection_delete_confirm").performClick()
        assertEquals("c1", deleted)
    }

    /**
     * The chain, end to end: a tap on the page's publish action reaches the
     * sheet, and only the sheet's explicit confirm reaches the caller — with the
     * pseudonym the listener typed and the id of the collection they were
     * looking at.
     */
    @Test
    fun `publishing from the page needs the sheet's explicit confirm`() {
        var published: Pair<String, String>? = null
        setPage(
            collections = listOf(collection("c1")),
            openId = "c1",
            onPublish = { collectionId, pseudonym -> published = collectionId to pseudonym }
        )

        composeTestRule.onNodeWithTag("collection_publish").performClick()
        composeTestRule.onNodeWithTag("publish_collection_sheet").assertIsDisplayed()
        assertNull("opening the sheet must publish nothing", published)

        composeTestRule.onNodeWithTag("publish_collection_cancel").performClick()
        assertNull("cancelling must publish nothing", published)

        composeTestRule.onNodeWithTag("collection_publish").performClick()
        composeTestRule
            .onNodeWithTag("publish_collection_pseudonym")
            .performTextInput("Слухач")
        composeTestRule.onNodeWithTag("publish_collection_confirm").performClick()

        assertEquals("c1" to "Слухач", published)
    }

    @Test
    fun `a collection with no books offers no publishable path`() {
        var published: Pair<String, String>? = null
        setPage(
            collections = listOf(collection("c1").copy(items = emptyList())),
            openId = "c1",
            onPublish = { collectionId, pseudonym -> published = collectionId to pseudonym }
        )

        composeTestRule.onNodeWithTag("collection_publish").performClick()
        composeTestRule.onNodeWithTag("publish_collection_impossible").assertIsDisplayed()
        composeTestRule.onNodeWithTag("publish_collection_confirm").performClick()

        assertNull(published)
    }

    /**
     * The pin that actually covers #1154.
     *
     * Every other test here renders the page DIRECTLY, so all of them would stay
     * green if `LibraryScreen` stopped calling it — which is precisely the shape
     * of the original defect: a correct composable that nobody rendered. No
     * rendered fixture reaches the whole library screen (it needs the app's
     * view model), so the call is pinned at the source, the same way this
     * repository already pins locale literals.
     */
    @Test
    fun `the library screen passes the open id into the page`() {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "src/main/java/com/slukhayka/audiobooks"),
            File(System.getProperty("user.dir"), "app/src/main/java/com/slukhayka/audiobooks")
        )
        val sourceRoot = candidates.firstOrNull { it.isDirectory }
            ?: error("source root not found from ${System.getProperty("user.dir")}")
        val library = File(sourceRoot, "ui/screens/LibraryScreen.kt").readText()

        assertTrue(
            "LibraryScreen must render ListenerCollectionPage — without the call a " +
                "collection can be created and listed but never opened (#1154)",
            library.contains("ListenerCollectionPage(")
        )
        assertTrue(
            "LibraryScreen must pass the state it already collects — `openId = openCollectionId`",
            library.contains("openId = openCollectionId")
        )
        assertTrue(
            "LibraryScreen must wire the confirmed publish — without it the sheet's " +
                "confirm leads nowhere (#1154)",
            library.contains("onPublish = viewModel::confirmPublish")
        )
    }
}
