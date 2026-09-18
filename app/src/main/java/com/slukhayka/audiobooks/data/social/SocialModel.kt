package com.slukhayka.audiobooks.data.social

/**
 * #897 — where the social layer's data lives, per
 * `docs/specs/2026-09-16-social-layer.md` §5.
 *
 * Deliberately the *shape* only: the spec is explicit that the social layer adds
 * no new entities where one already exists, so these types do not re-declare a
 * review, a diary entry or a user id.
 */

/**
 * §5 — a post is its **own** object with its own audience. It is not a review
 * and not a diary record, and it does not duplicate their fields; when it shares
 * something that already exists, it points at it by id.
 */
data class Post(
    val id: String,
    val authorPseudonym: String,
    val audience: Audience,
    val text: String,
    /** The record this post shares (a review, a published collection), if any. */
    val sourceId: String? = null
)

/**
 * §5 — a friendship is a **pair fact between two pseudonyms**, never a uid: the
 * shared base stores the accepted link under the same hashed identifier the
 * curatorial collections already use.
 */
data class Friendship(val first: String, val second: String) {
    init {
        require(first != second) { "a friendship is between two different pseudonyms" }
    }

    /** Order must not matter: the pair is one fact, stored once. */
    fun sameAs(other: Friendship): Boolean =
        (first == other.first && second == other.second) ||
            (first == other.second && second == other.first)

    fun involves(pseudonym: String): Boolean = pseudonym == first || pseudonym == second

    /** §3/§2 — the other side, whichever side acted. */
    fun other(pseudonym: String): String = if (pseudonym == first) second else first
}

/**
 * §6.3 — a **bibliographic** person (an author, a narrator) never becomes a
 * friend. «Люди» and a listener's profile are different entities, and no screen
 * may mix them; this type makes the distinction unrepresentable-by-accident.
 */
sealed interface SocialActor {
    val id: String

    /** A listener: the only kind of actor that can be a friend. */
    data class Listener(override val id: String, val pseudonym: String) : SocialActor

    /** An author/narrator from the catalogue. */
    data class Bibliographic(override val id: String) : SocialActor
}

object SocialModelRules {

    /** §6.3 — only listeners can be friends. */
    fun canBeFriend(actor: SocialActor): Boolean = actor is SocialActor.Listener

    /**
     * §5 — a private record (progress, goal, a finish) stays where it is and
     * gains NO social fields; this is the list the social layer must not touch.
     */
    fun privateRecordFields(): Set<String> = setOf("progress", "goal", "finishedAt")
}
