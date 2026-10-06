package com.slukhayka.audiobooks.ui.screens.collections

import androidx.compose.runtime.Composable
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
 * @param openId the collection the listener opened, or null for no page at all.
 *   An id that matches nothing opens nothing, deliberately: a page for a
 *   collection that is gone would be a ghost, and the honest answer is no page.
 */
@Composable
fun ListenerCollectionPage(
    collections: List<ListenerCollection>,
    openId: String?,
    onClose: () -> Unit,
    onRemoveBook: (collectionId: String, bookId: String) -> Unit,
    onDelete: (collectionId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val collection = openId?.let { id -> collections.firstOrNull { it.id == id } } ?: return
    CollectionPage(
        title = collection.title,
        onClose = onClose,
        modifier = modifier,
        testTag = "collection_detail_page"
    ) {
        CollectionDetailContent(
            collection = collection,
            onRemoveBook = { bookId -> onRemoveBook(collection.id, bookId) },
            onDelete = { onDelete(collection.id) }
        )
    }
}
