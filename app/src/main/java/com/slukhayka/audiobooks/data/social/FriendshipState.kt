package com.slukhayka.audiobooks.data.social

/**
 * #916 — the locally stored state of a friendship fact, per
 * `docs/specs/2026-09-16-social-layer.md` §3 and §5.
 *
 * The two REQUEST states carry a direction because a pending request is
 * one-sided; the two SETTLED states do not, because a friendship is a pair fact
 * where order does not matter ([Friendship.sameAs]) and a refusal is merely the
 * absence of one.
 */
enum class FriendshipState {
    /** The other pseudonym asked; this listener has not answered yet. */
    INCOMING,

    /** This listener asked; the other side has not answered yet. */
    OUTGOING,

    /** The pair are friends now. Only a state the listener can actually show. */
    ACCEPTED,

    /** The request was declined. Kept so the ask does not silently reappear. */
    DECLINED
}

/**
 * §2 — which side performed a stored block. The action is one-sided by design;
 * the consequence is two-sided once the two directions are read together.
 */
enum class BlockDirection {
    /** This listener blocked the pseudonym. */
    BY_ME,

    /** The pseudonym blocked this listener — learned from the shared base. */
    BY_OTHER
}
