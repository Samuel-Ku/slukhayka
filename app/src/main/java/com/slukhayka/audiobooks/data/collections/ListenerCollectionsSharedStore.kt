package com.slukhayka.audiobooks.data.collections

import com.slukhayka.audiobooks.data.achievements.ShowcaseAwardSnapshot

/**
 * Spec-51 (#692) — the honest outcome of a public READ: real data, an honest
 * empty, or an unreachable shared layer. The reader keeps its last good list on
 * [Failure] instead of blanking a surface on a transient error.
 */
sealed interface CollectionReadResult {
    data class Data(val collections: List<PublishedCollection>) : CollectionReadResult
    data object Empty : CollectionReadResult
    data object Failure : CollectionReadResult
}

/** Spec-51 (#691) — the honest outcome of an online-only action. */
sealed interface PublishResult {
    data object Published : PublishResult

    /**
     * Nothing left the device. There is deliberately NO queue: a refusal is
     * final and visible, never a silent retry that publishes later.
     */
    data class Refused(val reason: String) : PublishResult
}

/**
 * Spec-51 (#691) — publishing is ONLINE-ONLY. The shared store is the only
 * door out of the device, and every method can honestly refuse.
 *
 * A missing implementation (no Firestore configured) must leave the local
 * collections fully usable and expose NO public surface — see the callers.
 */
interface ListenerCollectionsSharedStore {

    /** @return [PublishResult.Published], or a [PublishResult.Refused] reason. */
    suspend fun publish(
        collection: ListenerCollection,
        authorId: String,
        pseudonym: String,
        /** #692 — the display snapshots known at publish time (may be empty). */
        itemSnapshots: Map<String, PublishedCollectionFactory.ItemSnapshot> = emptyMap()
    ): PublishResult

    /** Replaces the pseudonym on the author document AND all their collections. */
    suspend fun renameAuthor(authorId: String, pseudonym: String): PublishResult

    /**
     * #705 (T7) — replaces the showcase on the author's public profile.
     *
     * There is no author document, so the showcase is written onto every
     * collection the author has published, exactly like [renameAuthor]. An
     * empty [awards] is a real instruction — it CLEARS the showcase, which is
     * how unpinning the last award removes it from the profile.
     *
     * An author with no published collections has no profile to write to and is
     * refused with `no-public-profile`: the showcase can never be shown, and
     * accepting it would tell the listener they had published something they
     * had not.
     */
    suspend fun publishShowcase(
        authorId: String,
        awards: List<ShowcaseAwardSnapshot>
    ): PublishResult

    /** Removes the author document and every collection it owns. */
    suspend fun deleteAuthorProfile(authorId: String): PublishResult

    /** What is publicly visible for this author. */
    suspend fun publishedBy(authorId: String): List<PublishedCollection>

    /**
     * Spec-51 (#692) — every VISIBLE published collection that carries this
     * book, for the book page's «Добірки з цією книгою» block. Ordering is the
     * caller's ([CollectionRanking]); this is a plain read.
     */
    suspend fun containing(bookId: String): List<PublishedCollection>

    /**
     * #692 — the same read with the three outcomes kept apart, so a surface
     * can hold its last good list when the shared layer is unreachable.
     */
    suspend fun readContaining(bookId: String): CollectionReadResult =
        CollectionReadResult.Data(containing(bookId))

    /**
     * Spec-51 (#694) — one vote per person, applied TRANSACTIONALLY with the
     * collection's `ratingSum`/`ratingCount`. [voterKey] is
     * [CollectionIdentity.voterKey]; a re-vote REPLACES the previous stars.
     * Online-only with an honest refusal, like publishing.
     */
    suspend fun vote(documentId: String, voterKey: String, stars: Int): PublishResult

    /** The listener's own stars for one collection, or null when not voted. */
    suspend fun myVote(voterKey: String): Int?

    /**
     * Spec-51 (#696) — one complaint per person. Three UNIQUE complaints hide
     * the collection forever, transactionally with `reportCount`/`hidden`.
     * [reporterKey] is [CollectionIdentity.voterKey]; a duplicate is an
     * idempotent acceptance that never counts again.
     */
    suspend fun report(documentId: String, reporterKey: String): PublishResult

    /**
     * Spec-51 (#696) — the author's ONLY remaining action on a hidden
     * collection: delete it. Online-only with an honest refusal.
     */
    suspend fun deleteOwnCollection(documentId: String): PublishResult

    /**
     * Spec-51 (#693) — the public «Добірки слухачів» rail. The implementation
     * takes a BOUNDED candidate page (most-voted first) and applies the shared
     * [CollectionRanking], so the rail orders exactly like the book block.
     * Hidden collections never appear.
     */
    suspend fun topPublic(limit: Int): List<PublishedCollection>

    /** Spec-51 (#693) — a curator's VISIBLE collections (hidden excluded). */
    suspend fun visibleBy(authorId: String): List<PublishedCollection>
}
