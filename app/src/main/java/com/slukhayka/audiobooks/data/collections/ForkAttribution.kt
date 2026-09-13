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

object ForkPolicy {

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
