package com.slukhayka.audiobooks.data.collections

import com.slukhayka.audiobooks.data.db.ListenerCollectionEntity
import com.slukhayka.audiobooks.data.db.ListenerCollectionItemEntity
import com.slukhayka.audiobooks.data.db.ListenerCollectionsDao
import java.util.UUID

/**
 * Spec-51 (#689) — the on-disk twin of the contract already pinned by
 * [ListenerCollectionsStoreTest] on fakes: hygiene on write, duplicates as an
 * honest false, and insertion order preserved.
 */
class RoomListenerCollectionsStore(
    private val dao: ListenerCollectionsDao,
    private val clock: () -> Long = System::currentTimeMillis
) : ListenerCollectionsStore {

    override suspend fun create(title: String?, description: String?): String? {
        val cleanTitle = ListenerCollectionLimits.cleanTitle(title)
        if (!ListenerCollectionLimits.isWritableTitle(cleanTitle)) return null
        val id = UUID.randomUUID().toString()
        dao.insertCollection(
            ListenerCollectionEntity(
                id = id,
                title = cleanTitle,
                description = ListenerCollectionLimits.cleanDescription(description),
                createdAt = clock()
            )
        )
        return id
    }

    override suspend fun add(collectionId: String, bookId: String, reason: String?): Boolean {
        if (dao.collections().none { it.id == collectionId }) return false
        val rowId = dao.insertItem(
            ListenerCollectionItemEntity(
                collectionId = collectionId,
                bookId = bookId,
                reason = ListenerCollectionLimits.cleanReason(reason),
                addedAt = clock()
            )
        )
        return rowId != -1L
    }

    override suspend fun remove(collectionId: String, bookId: String): Boolean =
        dao.deleteItem(collectionId, bookId) > 0

    override suspend fun rename(collectionId: String, title: String?): Boolean {
        val cleanTitle = ListenerCollectionLimits.cleanTitle(title)
        if (!ListenerCollectionLimits.isWritableTitle(cleanTitle)) return false
        return dao.updateTitle(collectionId, cleanTitle) > 0
    }

    override suspend fun updateDescription(collectionId: String, description: String?): Boolean =
        dao.updateDescription(
            collectionId,
            ListenerCollectionLimits.cleanDescription(description)
        ) > 0

    override suspend fun delete(collectionId: String): Boolean {
        // Items first: a collection must never leave orphans behind.
        dao.deleteItemsOf(collectionId)
        return dao.deleteCollection(collectionId) > 0
    }

    override suspend fun saveFork(
        forked: ListenerCollection,
        attribution: ForkAttribution
    ): Boolean {
        if (dao.collections().any { it.id == forked.id }) return false
        dao.insertCollection(
            ListenerCollectionEntity(
                id = forked.id,
                title = ListenerCollectionLimits.cleanTitle(forked.title),
                description = ListenerCollectionLimits.cleanDescription(forked.description),
                createdAt = forked.createdAt,
                sourceDocumentId = attribution.sourceDocumentId,
                sourceTitle = attribution.sourceTitle,
                sourcePseudonym = attribution.sourcePseudonym,
                snapshotAt = attribution.snapshotAt
            )
        )
        forked.items.forEach { item ->
            dao.insertItem(
                ListenerCollectionItemEntity(
                    collectionId = forked.id,
                    bookId = item.bookId,
                    reason = ListenerCollectionLimits.cleanReason(item.reason),
                    addedAt = item.addedAt
                )
            )
        }
        return true
    }

    override suspend fun all(): List<ListenerCollection> {
        val items = dao.items().groupBy { it.collectionId }
        return dao.collections().map { collection ->
            ListenerCollection(
                id = collection.id,
                title = collection.title,
                description = collection.description,
                createdAt = collection.createdAt,
                items = items[collection.id].orEmpty().map {
                    ListenerCollectionItem(it.bookId, it.reason, it.addedAt)
                },
                // All four or none: a half-filled snapshot is not an attribution.
                attribution = if (
                    collection.sourceDocumentId != null &&
                    collection.sourceTitle != null &&
                    collection.sourcePseudonym != null &&
                    collection.snapshotAt != null
                ) {
                    ForkAttribution(
                        sourceTitle = collection.sourceTitle,
                        sourcePseudonym = collection.sourcePseudonym,
                        sourceDocumentId = collection.sourceDocumentId,
                        snapshotAt = collection.snapshotAt
                    )
                } else {
                    null
                }
            )
        }
    }
}
