package com.slukhayka.audiobooks.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stable String kinds of [PersonBookmarkEntity] (#399).
 *
 * `AUTHOR` — the canonical author of a Work; identity is the same
 * `AuthorIdentity.fromWorkName` canonical id the author facet uses.
 * `NARRATOR` — the edition-level narrator (narratorId derived from the
 * same normalisation as AuthorIdentity, but prefixed with "narrator" so
 * the two never collide).
 */
enum class PersonRole(val storageValue: String, val idPrefix: String) {
    AUTHOR("AUTHOR", "author"),
    NARRATOR("NARRATOR", "narrator");

    companion object {
        fun fromStorage(value: String): PersonRole? = entries.firstOrNull { it.storageValue == value }
    }
}

data class PersonBookmarkKey(val role: PersonRole, val id: String)

/** SQL-facing values for DAO and migration tests. Domain callers use [PersonRole]. */
object PersonBookmarkKind {
    const val AUTHOR = "AUTHOR"
    const val NARRATOR = "NARRATOR"
}

/**
 * A listener's bookmarked person (#399 — parent #398).
 *
 * One row per (kind, id) — the listener may bookmark both the author
 * AND the narrator of the same book as two independent bookmarks.
 *
 * [normalizedName] is stored for prefix-search in the "People" section
 * (spec-28 #401) and is derived deterministically from [displayName]
 * using the same normaliser as [com.slukhayka.audiobooks.data.authors.AuthorIdentity].
 *
 * [createdAt] is set once on first toggle; [updatedAt] is refreshed on
 * every toggle or notifyEnabled change so LWW-merge is trivially
 * possible when #404 (Firestore sync) arrives.
 *
 * [lastSeenAt] — the last time the listener visited this person's page
 * or tapped "Mark seen"; drives the new-count badge.
 * [lastNotifiedAt] — the last time a notification was posted for this
 * person; prevents duplicate notifications.
 * [notifyEnabled] — the per-person notification toggle (default true).
 */
@Entity(
    tableName = "person_bookmarks",
    primaryKeys = ["kind", "id"],
    indices = [Index("normalizedName")]
)
data class PersonBookmarkEntity(
    /** [PersonRole.AUTHOR] or [PersonRole.NARRATOR], persisted through `storageValue`. */
    val kind: String,
    /** Deterministic person id (`boundedId("author", normalizedName)` or `boundedId("narrator", normalizedName)`). */
    val id: String,
    /** The human-readable name the listener sees. */
    val displayName: String,
    /** Lowercased, NFKC-normalised name for prefix search. */
    val normalizedName: String,
    /** Epoch millis when this bookmark was first created. */
    val createdAt: Long = System.currentTimeMillis(),
    /** Epoch millis when the listener last viewed this person's page. */
    val lastSeenAt: Long = 0L,
    /** Epoch millis when the last notification was posted for this person. */
    val lastNotifiedAt: Long = 0L,
    /** Whether new-book notifications are enabled for this person (default true). */
    val notifyEnabled: Boolean = true,
    /** Epoch millis of the last write — LWW merge key for #404. */
    val updatedAt: Long = System.currentTimeMillis()
)
