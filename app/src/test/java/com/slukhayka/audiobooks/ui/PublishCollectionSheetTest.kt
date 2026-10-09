package com.slukhayka.audiobooks.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.collections.ListenerCollection
import com.slukhayka.audiobooks.data.collections.ListenerCollectionItem
import com.slukhayka.audiobooks.data.collections.PublishedCollectionCodec
import com.slukhayka.audiobooks.ui.screens.collections.PublishCollectionSheet
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-51 (#691) — nothing leaves the device without this screen's consent.
 *
 * #1154 — the pseudonym is part of that consent, and it is entered HERE. Until
 * now the sheet displayed a pseudonym that the caller was supposed to produce
 * from nowhere, and no caller existed; the preview could not even be built. So
 * these tests now walk the sheet from its opening state — nothing typed, nothing
 * publishable — through to the confirm.
 *
 * spec-46 T14 (колекційний зріз): its chrome lines are resources now, so the
 * locale is explicit instead of inherited from the host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "uk-rUA", sdk = [36])
class PublishCollectionSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun collection(
        title: String = "Магія",
        books: List<String> = listOf("a")
    ) = ListenerCollection(
        id = "c1",
        title = title,
        description = "про зорі",
        createdAt = 1L,
        items = books.map { ListenerCollectionItem(it, "", 1L) }
    )

    private fun setContent(
        collection: ListenerCollection = collection(),
        onConfirm: (String) -> Unit = {},
        onDismiss: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                PublishCollectionSheet(
                    collection = collection,
                    onConfirm = onConfirm,
                    onDismiss = onDismiss
                )
            }
        }
    }

    /** Types a pseudonym and returns what the sheet would confirm with. */
    private fun typePseudonym(value: String) {
        composeTestRule.onNodeWithTag("publish_collection_pseudonym").performTextInput(value)
    }

    @Test
    fun `nothing is publishable until a pseudonym is given`() {
        var confirmed: String? = null
        setContent(onConfirm = { confirmed = it })

        // The honest opening state: the sheet says what it is waiting for and
        // offers no way past it. An empty confirmation is not a confirmation.
        composeTestRule.onNodeWithTag("publish_collection_awaiting_pseudonym").assertIsDisplayed()
        composeTestRule.onNodeWithTag("publish_collection_confirm").assertIsNotEnabled()

        composeTestRule.onNodeWithTag("publish_collection_confirm").performClick()
        assertEquals(null, confirmed)
    }

    @Test
    fun `the sheet lists every line that will be published`() {
        setContent()
        typePseudonym("Слухач")

        composeTestRule.onNodeWithTag("publish_collection_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.publish_collection_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.publish_collection_preview_lead))
            .assertIsDisplayed()
        // #980: the labels live here now (the pure preview model only carries
        // the facts), so each rendered line is asserted from its resource — the
        // EN twin is covered by the collections EN walk.
        listOf(
            context.getString(R.string.publish_collection_preview_line_title, "Магія"),
            context.getString(R.string.publish_collection_preview_line_pseudonym, "Слухач"),
            context.getString(R.string.publish_collection_preview_line_book_count, 1),
            context.getString(R.string.publish_collection_preview_line_description_included)
        ).forEachIndexed { index, line ->
            composeTestRule.onNodeWithTag("publish_collection_line_$index").assertIsDisplayed()
            composeTestRule.onNodeWithText(line).assertExists()
        }
    }

    @Test
    fun `the preview follows the pseudonym as it is typed`() {
        setContent()
        typePseudonym("Слухач")

        composeTestRule
            .onNodeWithText(
                context.getString(R.string.publish_collection_preview_line_pseudonym, "Слухач")
            )
            .assertExists()
    }

    @Test
    fun `the pseudonym is bounded at the published limit while typing`() {
        var confirmed: String? = null
        setContent(onConfirm = { confirmed = it })

        typePseudonym("п".repeat(PublishedCollectionCodec.MAX_PSEUDONYM_LEN + 30))
        composeTestRule.onNodeWithTag("publish_collection_confirm").performClick()

        // The limit is met while typing, not applied silently afterwards.
        assertEquals("п".repeat(PublishedCollectionCodec.MAX_PSEUDONYM_LEN), confirmed)
    }

    @Test
    fun `a collection with no books is refused honestly and never publishable`() {
        var confirmed: String? = null
        setContent(collection = collection(books = emptyList()), onConfirm = { confirmed = it })

        composeTestRule.onNodeWithTag("publish_collection_impossible").assertIsDisplayed()
        // Not the "type a name" state: no pseudonym can fix this one, so the
        // field is closed rather than inviting a name that would go nowhere.
        composeTestRule.onNodeWithTag("publish_collection_awaiting_pseudonym").assertDoesNotExist()
        composeTestRule.onNodeWithTag("publish_collection_confirm").assertIsNotEnabled()

        composeTestRule.onNodeWithTag("publish_collection_confirm").performClick()
        assertEquals(null, confirmed)
    }

    @Test
    fun `cancelling publishes nothing`() {
        var confirmed: String? = null
        var dismissed = false
        setContent(onConfirm = { confirmed = it }, onDismiss = { dismissed = true })
        typePseudonym("Слухач")

        composeTestRule.onNodeWithTag("publish_collection_cancel").performClick()
        assertFalse("nothing leaves the device on cancel", confirmed != null)
        assertEquals(true, dismissed)
    }

    @Test
    fun `only the explicit confirm publishes, and it carries the pseudonym`() {
        var confirmed: String? = null
        setContent(onConfirm = { confirmed = it })
        typePseudonym("Слухач")

        composeTestRule.onNodeWithTag("publish_collection_confirm").assertIsEnabled()
        composeTestRule.onNodeWithTag("publish_collection_confirm").performClick()

        assertEquals("Слухач", confirmed)
        assertTrue("the confirm is the only path out", confirmed != null)
    }
}
