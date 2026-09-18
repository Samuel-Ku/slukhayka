package com.slukhayka.audiobooks.data.social

/**
 * #898 — what the «Друзі» feed shows, per
 * `docs/specs/2026-09-16-social-layer.md` §1 (audience), §2 (blocking),
 * §3 (unfriending) and §6 (invariants).
 *
 * The rule is deliberately **composed** from the two policies that already own
 * half of it instead of re-deriving either:
 *
 * - [BlockPolicy.visibleTo] — a block governs the feed, not the public contract
 *   (§2: a public review stays public, even for a blocked person);
 * - [UnfriendPolicy.visibleTo] — the audience is a rule applied **at the moment
 *   of display**, not a snapshot of "who was a friend back then" (§3).
 *
 * A `FRIENDS` post therefore needs BOTH facts to hold right now: the pair is not
 * blocked in either direction **and** the friendship is live. Either policy on
 * its own is not enough — [BlockPolicy] does not know about friendship, and
 * [UnfriendPolicy] does not know about blocks.
 */
object FriendsFeedPolicy {

    /**
     * §1/§2/§3 — may [viewer] see one post by [author]?
     *
     * - own post — always, whatever the audience is;
     * - [Audience.PRIVATE] — never for anyone else, at any friendship or block
     *   state;
     * - [Audience.PUBLIC] — to anyone, including a blocked or former friend;
     * - [Audience.FRIENDS] — only while [areFriendsNow] and the pair is unblocked.
     *
     * [areFriendsNow] is the viewer's accepted-friends set at this moment, so a
     * former friend simply passing `false` is the whole of §3's feed rule.
     *
     * Ownership is decided HERE rather than delegated, because both underlying
     * policies answer "own post" with a plain `author == viewer` and the viewer
     * is not always known: the listener identity resolves asynchronously, so a
     * cold start briefly passes `""`. Without this guard two blank pseudonyms
     * would "own" each other and a `PRIVATE` post would leak. A post whose
     * author is blank is unattributable — §5 makes the author a pseudonym — so
     * it is shown to nobody rather than to whoever also happens to be unknown.
     */
    fun visibleTo(
        audience: Audience,
        author: String,
        viewer: String,
        areFriendsNow: Boolean,
        blocks: BlockState = BlockState()
    ): Boolean {
        if (author.isBlank()) return false
        if (viewer.isNotBlank() && author == viewer) return true
        return BlockPolicy.visibleTo(audience, author, viewer, blocks) &&
            UnfriendPolicy.visibleTo(audience, author, viewer, areFriendsNow)
    }

    /**
     * The same rule over a whole feed. [authorOf] / [audienceOf] read the two
     * facts the rule needs off each post, so the feed does not have to agree on
     * one model type to be filtered (the shared `Post` shape is #897's).
     *
     * Order is preserved and nothing is added: a feed that loses every post
     * under this filter stays visibly empty, it is never padded (§6.4).
     */
    fun <T> visibleFeed(
        posts: List<T>,
        viewer: String,
        friendsNow: Set<String>,
        blocks: BlockState = BlockState(),
        authorOf: (T) -> String,
        audienceOf: (T) -> Audience
    ): List<T> = posts.filter { post ->
        val author = authorOf(post)
        visibleTo(
            audience = audienceOf(post),
            author = author,
            viewer = viewer,
            areFriendsNow = author in friendsNow,
            blocks = blocks
        )
    }
}
