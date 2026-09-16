package com.slukhayka.audiobooks.data.collections

/**
 * Spec-51 (#696) — the moderation threshold, pure and testable.
 *
 * Three UNIQUE complaints hide a collection FOREVER. "Forever" is a one-way
 * rule, not a toggle: [nextHidden] only ever turns the flag on, and the
 * Firestore rules enforce the same `false → true` transition, so a client can
 * never un-hide a collection the community removed.
 */
object CollectionModeration {

    /** The number of unique complaints that hides a collection for good. */
    const val HIDE_THRESHOLD: Int = 3

    /**
     * The moderation state after one NEW unique complaint.
     *
     * @param currentHidden the flag as stored; true stays true.
     * @param currentCount the unique complaints already counted.
     * @return whether the collection is hidden after this complaint.
     */
    fun nextHidden(currentHidden: Boolean, currentCount: Int): Boolean =
        currentHidden || (currentCount + 1) >= HIDE_THRESHOLD

    /** A public collection is renderable only while it is not hidden. */
    fun isRenderable(collection: PublishedCollection): Boolean = !collection.hidden
}
