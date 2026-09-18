package com.slukhayka.audiobooks.data.social

/**
 * #894 — blocking, per `docs/specs/2026-09-16-social-layer.md` §2:
 * **one-sided as an action, two-sided as a consequence**.
 *
 * Deliberately pure: no storage, no UI. The rules below are the ones the spec
 * says must not be broken by code.
 */
data class BlockState(
    /** Pseudonyms this listener blocked. */
    val blocked: Set<String> = emptySet(),
    /** Pseudonyms that blocked this listener. */
    val blockedBy: Set<String> = emptySet()
) {
    /** §2 — both sides stop seeing each other, whichever side acted. */
    fun hides(other: String): Boolean = other in blocked || other in blockedBy

    fun block(other: String): BlockState = copy(blocked = blocked + other)

    /**
     * §2 — unblocking does NOT restore the friendship: what was cancelled stays
     * cancelled until a NEW explicit request is accepted.
     */
    fun unblock(other: String): BlockState = copy(blocked = blocked - other)
}

object BlockPolicy {

    /** §2 — a block cancels the friendship in both directions. */
    fun friendsAfterBlock(friends: Set<String>, other: String): Set<String> = friends - other

    /** §2 — after unblocking, the friendship is still gone. */
    fun friendsAfterUnblock(friends: Set<String>, other: String): Set<String> = friends - other

    /**
     * §2 — a friend request across a block is rejected **silently**: the caller
     * gets a plain `false`, never a reason the other side could learn from.
     */
    fun acceptsFriendRequest(state: BlockState, requester: String): Boolean =
        !state.hides(requester)

    /**
     * §2/§6 — a block governs the **feed and the profile**, not someone's right
     * to read what the person made public themselves. So:
     * `PRIVATE` is the author's alone, `FRIENDS` needs an unblocked pair, and
     * `PUBLIC` stays readable even for a blocked person.
     */
    fun visibleTo(
        audience: Audience,
        author: String,
        viewer: String,
        state: BlockState
    ): Boolean = when {
        author == viewer -> true
        audience == Audience.PRIVATE -> false
        audience == Audience.PUBLIC -> true
        else -> !state.hides(author)
    }
}
