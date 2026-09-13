package com.slukhayka.audiobooks.data.collections

/** A fake store: the contract's shape, no Room, no clock of its own. */
class InMemoryListenerCollections(private val clock: () -> Long) : ListenerCollectionsStore {

    private val collections = linkedMapOf<String, ListenerCollection>()
    private var nextId = 1

    override suspend fun create(title: String?, description: String?): String? {
        val cleanTitle = ListenerCollectionLimits.cleanTitle(title)
        if (!ListenerCollectionLimits.isWritableTitle(cleanTitle)) return null
        val id = "c${nextId++}"
        collections[id] = ListenerCollection(
            id = id,
            title = cleanTitle,
            description = ListenerCollectionLimits.cleanDescription(description),
            createdAt = clock()
        )
        return id
    }

    override suspend fun add(collectionId: String, bookId: String, reason: String?): Boolean {
        val existing = collections[collectionId] ?: return false
        if (existing.contains(bookId)) return false
        collections[collectionId] = existing.copy(
            items = existing.items + ListenerCollectionItem(
                bookId = bookId,
                reason = ListenerCollectionLimits.cleanReason(reason),
                addedAt = clock()
            )
        )
        return true
    }

    override suspend fun remove(collectionId: String, bookId: String): Boolean {
        val existing = collections[collectionId] ?: return false
        if (!existing.contains(bookId)) return false
        collections[collectionId] = existing.copy(items = existing.items.filterNot { it.bookId == bookId })
        return true
    }

    override suspend fun rename(collectionId: String, title: String?): Boolean {
        val existing = collections[collectionId] ?: return false
        val cleanTitle = ListenerCollectionLimits.cleanTitle(title)
        if (!ListenerCollectionLimits.isWritableTitle(cleanTitle)) return false
        collections[collectionId] = existing.copy(title = cleanTitle)
        return true
    }

    override suspend fun updateDescription(collectionId: String, description: String?): Boolean {
        val existing = collections[collectionId] ?: return false
        collections[collectionId] =
            existing.copy(description = ListenerCollectionLimits.cleanDescription(description))
        return true
    }

    override suspend fun delete(collectionId: String): Boolean =
        collections.remove(collectionId) != null

    override suspend fun all(): List<ListenerCollection> = collections.values.toList()
}
