package com.slukhayka.audiobooks.data.collections

/**
 * Spec-51 (#695) — the attribution a fork carries FOREVER.
 *
 * The snapshot is frozen at fork time: the text names the collection and the
 * pseudonym as they were then, so a later rename or deletion of the original
 * cannot rewrite history. Only the LINK is conditional — it points at the
 * original while that is still visible, and silently stops being a link
 * afterwards (the text stays).
 */
data class ForkAttribution(
    val sourceTitle: String,
    val sourcePseudonym: String,
    val sourceDocumentId: String,
    val snapshotAt: Long
) {
    /** Always shown, never rewritten by changes to the original. */
    val text: String get() = "на основі «$sourceTitle» від $sourcePseudonym"

    /**
     * The link is a door to the original, so it exists only while the original
     * is visible. [originalVisible] is the caller's fresh answer — the snapshot
     * itself never assumes.
     */
    fun linkAvailable(originalVisible: Boolean): Boolean = originalVisible
}

/** Spec-51 (#695) — the honest outcome of «Зберегти собі». */
sealed interface ForkOutcome {
    data class Forked(
        val collection: ListenerCollection,
        val attribution: ForkAttribution
    ) : ForkOutcome

    /**
     * Nothing was saved. A fork is a LOCAL copy, so when the original is not on
     * this device there is nothing to copy — an honest refusal, never a silent
     * fetch that might complete later.
     */
    data class Refused(val reason: String) : ForkOutcome
}

object ForkPolicy {

    /**
     * Forks an original that is ALREADY available locally.
     *
     * @param original the visible collection as this device knows it, or null
     * when it is not here (offline, never opened) — which refuses honestly.
     */
    fun fork(
        original: ListenerCollection?,
        pseudonym: String,
        documentId: String,
        now: Long
    ): ForkOutcome {
        if (original == null) return ForkOutcome.Refused(ORIGINAL_NOT_LOCAL)
        val (collection, attribution) = forkOf(original, pseudonym, documentId, now)
        return ForkOutcome.Forked(collection, attribution)
    }

    const val ORIGINAL_NOT_LOCAL = "original-not-local"

    /**
     * Spec-51 (#695) — the PRECISE gate of «Зберегти собі»: a fork is an
     * OFFLINE copy into the reader's own collections, so it is offered only
     * when the reader already has at least one of the original's books locally
     * (the copy then has something to render offline). An empty composition or
     * a collection of books the reader does not own refuses honestly — the
     * action is never a silent fetch that might complete later.
     */
    fun canFork(originalBookIds: Collection<String>, localBookIds: Set<String>): Boolean =
        originalBookIds.isNotEmpty() && originalBookIds.any { it in localBookIds }


    /**
     * A fork is a LOCAL COPY of the composition (books and their reasons), not
     * a live reference: editing the original afterwards changes nothing here.
     */
    fun forkOf(
        original: ListenerCollection,
        pseudonym: String,
        documentId: String,
        now: Long
    ): Pair<ListenerCollection, ForkAttribution> {
        val copy = original.copy(
            id = "fork-${original.id}-$now",
            createdAt = now,
            items = original.items.map { it.copy() }
        )
        val attribution = ForkAttribution(
            sourceTitle = ListenerCollectionLimits.cleanTitle(original.title),
            sourcePseudonym = pseudonym.trim().take(PublishedCollectionCodec.MAX_PSEUDONYM_LEN),
            sourceDocumentId = documentId,
            snapshotAt = now
        )
        return copy to attribution
    }
}
