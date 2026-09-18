package com.slukhayka.audiobooks.data.social

/**
 * #895 — removing a friend, per `docs/specs/2026-09-16-social-layer.md` §3.
 *
 * §3 is mostly about what does NOT happen: nothing is deleted, nothing is
 * rewritten, and nothing comes back by itself. The policy below makes those
 * "nots" explicit, so a later screen cannot quietly break them.
 */
object UnfriendPolicy {

    /**
     * §3 — either side can end the friendship alone: no consent, no notice. It
     * is neither a block nor a report (that is [BlockPolicy]).
     */
    fun friendsAfterUnfriend(friends: Set<String>, other: String): Set<String> = friends - other

    /**
     * §3 — the audience is a rule applied **at the moment of display**, not a
     * snapshot of "who was a friend back then". So a `FRIENDS` post stops being
     * visible to the former friend from now on, while the author keeps it, and
     * other friends still see the very same post.
     */
    fun visibleTo(
        audience: Audience,
        author: String,
        viewer: String,
        areFriends: Boolean
    ): Boolean = when {
        author == viewer -> true
        audience == Audience.PRIVATE -> false
        audience == Audience.PUBLIC -> true
        else -> areFriends
    }

    /**
     * §3 — renewed friendship is a NEW explicit request; nothing becomes visible
     * by itself. Accepting it therefore does not re-publish anything: the posts
     * written while the pair were not friends follow their own audience (and a
     * `FRIENDS` post written before is visible again only because the pair ARE
     * friends now — never because the old friendship was restored).
     */
    fun acceptsRenewedFriendship(requestedAgain: Boolean): Boolean = requestedAgain
}
