package com.slukhayka.audiobooks.ui.screens.collections

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.slukhayka.audiobooks.data.collections.ListenerCollection

/**
 * Spec-51 (#690) + #1154 — the listener's OWN collection, as a full-screen page.
 *
 * It is a piece of its own so the WIRING can be tested. The defect in #1154 was
 * not a missing composable: [CollectionDetailContent] existed and was covered by
 * tests. What was missing was the CALL — the library reported the tapped id into
 * a state variable that nothing ever read, so the collection could be created
 * and listed but never opened. A test of the pieces cannot see a missing call;
 * a page whose open id is an explicit input can be rendered with one.
 *
 * The page also owns the publish FLOW — the confirmation sheet and its
 * pseudonym — while [CollectionDetailContent] only reports the intent. That
 * keeps the presenter unable to publish anything merely by being rendered, and
 * keeps the single place that can reach [onPublish] behind the sheet's explicit
 * confirm.
 *
 * @param openId the collection the listener opened, or null for no page at all.
 *   An id that matches nothing opens nothing, deliberately: a page for a
 *   collection that is gone would be a ghost, and the honest answer is no page.
 * @param onPublish called only after the listener confirms, with the pseudonym
 *   they typed. Never called by rendering this page.
 */
@Composable
fun ListenerCollectionPage(
    collections: List<ListenerCollection>,
    openId: String?,
    onClose: () -> Unit,
    onRemoveBook: (collectionId: String, bookId: String) -> Unit,
    onDelete: (collectionId: String) -> Unit,
    modifier: Modifier = Modifier,
    onPublish: (collectionId: String, pseudonym: String) -> Unit = { _, _ -> }
) {
    val collection = openId?.let { id -> collections.firstOrNull { it.id == id } } ?: return
    // Keyed on the collection: opening another one starts a fresh sheet rather
    // than inheriting a half-typed pseudonym from the previous collection.
    var publishing by rememberSaveable(collection.id) { mutableStateOf(false) }

    CollectionPage(
        title = collection.title,
        onClose = onClose,
        modifier = modifier,
        testTag = "collection_detail_page"
    ) {
        CollectionDetailContent(
            collection = collection,
            onRemoveBook = { bookId -> onRemoveBook(collection.id, bookId) },
            onDelete = { onDelete(collection.id) },
            onPublish = { publishing = true }
        )
    }

    if (publishing) {
        PublishCollectionSheet(
            collection = collection,
            onConfirm = { pseudonym ->
                publishing = false
                onPublish(collection.id, pseudonym)
            },
            onDismiss = { publishing = false }
        )
    }
}
