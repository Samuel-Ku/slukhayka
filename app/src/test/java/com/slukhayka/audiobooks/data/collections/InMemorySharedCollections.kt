package com.slukhayka.audiobooks.data.collections

/**
 * A fake shared store: online-only is modelled by [online], so the tests can
 * prove that a refusal leaves everything local and untouched.
 */
class InMemorySharedCollections(
    var online: Boolean = true,
    private val clock: () -> Long = { 1L }
) : ListenerCollectionsSharedStore {

    private val published = linkedMapOf<String, PublishedCollection>()

    override suspend fun publish(
        collection: ListenerCollection,
        authorId: String,
        pseudonym: String
    ): PublishResult {
        if (!online) return PublishResult.Refused("offline")
        if (!CuratorIdentity.isPublishable(authorId)) return PublishResult.Refused("no-identity")
        val cleanPseudonym = pseudonym.trim().take(PublishedCollectionCodec.MAX_PSEUDONYM_LEN)
        if (cleanPseudonym.isEmpty()) return PublishResult.Refused("no-pseudonym")
        val document = PublishedCollection(
            authorId = authorId,
            collectionId = collection.id,
            pseudonym = cleanPseudonym,
            title = collection.title,
            description = collection.description,
            bookIds = collection.items.map { it.bookId },
            publishedAt = clock()
        )
        published[document.documentId] = document
        return PublishResult.Published
    }

    override suspend fun renameAuthor(authorId: String, pseudonym: String): PublishResult {
        if (!online) return PublishResult.Refused("offline")
        val clean = pseudonym.trim().take(PublishedCollectionCodec.MAX_PSEUDONYM_LEN)
        if (clean.isEmpty()) return PublishResult.Refused("no-pseudonym")
        val mine = published.filterValues { it.authorId == authorId }
        if (mine.isEmpty()) return PublishResult.Refused("unknown-author")
        // ALL of the author's collections carry the same public name.
        mine.forEach { (id, doc) -> published[id] = doc.copy(pseudonym = clean) }
        return PublishResult.Published
    }

    override suspend fun deleteAuthorProfile(authorId: String): PublishResult {
        if (!online) return PublishResult.Refused("offline")
        val removed = published.entries.removeAll { it.value.authorId == authorId }
        return if (removed) PublishResult.Published else PublishResult.Refused("unknown-author")
    }

    override suspend fun publishedBy(authorId: String): List<PublishedCollection> =
        published.values.filter { it.authorId == authorId }
}
