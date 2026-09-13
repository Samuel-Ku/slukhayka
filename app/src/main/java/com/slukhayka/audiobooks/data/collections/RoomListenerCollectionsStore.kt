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
                }
            )
        }
    }
}
