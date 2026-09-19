package com.slukhayka.audiobooks.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * #916 — the local half of the social layer's storage, per
 * `docs/specs/2026-09-16-social-layer.md` §5.
 *
 * The listener's own side of a friendship is kept here as the **state of the
 * request** between them and one other **pseudonym** — never a uid (§5). The
 * accepted link itself is the shared base's fact; until that transport slice
 * lands this row is the local mirror the feed reads, which is why ACCEPTED is
 * one of the states rather than a separate table (a friendship is one fact, not
 * one request plus one relationship).
 */
@Entity(tableName = "friendship_states")
data class FriendshipStateEntity(
    /** The other side's pseudonym — the whole identity of the fact (§5). */
    @PrimaryKey val pseudonym: String,
    /** A [com.slukhayka.audiobooks.data.social.FriendshipState] name. */
    val state: String,
    val updatedAt: Long
)

/**
 * §2 — a block, stored **per direction**: [BlockDirection.BY_ME] is whom this
 * listener blocked, [BlockDirection.BY_OTHER] is who blocked this listener.
 *
 * The two directions are deliberately not merged into one row: the action is
 * one-sided (only one side performed it), while only the PAIR of facts makes
 * the consequence two-sided — [com.slukhayka.audiobooks.data.social.BlockState]
 * composes them.
 */
@Entity(tableName = "social_blocks", primaryKeys = ["pseudonym", "direction"])
data class BlockEntity(
    val pseudonym: String,
    /** A [com.slukhayka.audiobooks.data.social.BlockDirection] name. */
    val direction: String,
    val blockedAt: Long
)
