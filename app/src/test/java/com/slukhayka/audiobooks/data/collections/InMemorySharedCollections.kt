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
        val document = PublishedCollectionFactory.of(collection, authorId, pseudonym, clock())
            ?: return PublishResult.Refused("no-pseudonym")
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

    override suspend fun containing(bookId: String): List<PublishedCollection> =
        published.values.filter { bookId.isNotBlank() && bookId in it.bookIds && !it.hidden }

    private val votes = linkedMapOf<String, Int>()

    override suspend fun vote(documentId: String, voterKey: String, stars: Int): PublishResult {
        if (!online) return PublishResult.Refused("offline")
        if (voterKey.isBlank() || !CollectionRating.isValidStars(stars)) {
            return PublishResult.Refused("bad-vote")
        }
        val document = published[documentId] ?: return PublishResult.Refused("unknown-collection")
        val (sum, count) = CollectionRating.applyVote(
            document.ratingSum,
            document.ratingCount,
            votes[voterKey],
            stars
        )
        votes[voterKey] = stars
        published[documentId] = document.copy(ratingSum = sum, ratingCount = count)
        return PublishResult.Published
    }

    override suspend fun myVote(voterKey: String): Int? = votes[voterKey]

    override suspend fun topPublic(limit: Int): List<PublishedCollection> =
        CollectionRanking.top(published.values.filterNot { it.hidden }, limit)

    override suspend fun visibleBy(authorId: String): List<PublishedCollection> =
        published.values.filter { it.authorId == authorId && !it.hidden }

    override suspend fun deleteOwnCollection(documentId: String): PublishResult {
        if (!online) return PublishResult.Refused("offline")
        return if (published.remove(documentId) != null) {
            PublishResult.Published
        } else {
            PublishResult.Refused("unknown-collection")
        }
    }

    private val reports = linkedSetOf<String>()

    override suspend fun report(documentId: String, reporterKey: String): PublishResult {
        if (!online) return PublishResult.Refused("offline")
        if (reporterKey.isBlank()) return PublishResult.Refused("bad-report")
        val document = published[documentId] ?: return PublishResult.Refused("unknown-collection")
        // One complaint per person: a duplicate is accepted but never counts.
        if (!reports.add(reporterKey)) return PublishResult.Published
        published[documentId] = document.copy(
            reportCount = document.reportCount + 1,
            hidden = CollectionModeration.nextHidden(document.hidden, document.reportCount)
        )
        return PublishResult.Published
    }
}
