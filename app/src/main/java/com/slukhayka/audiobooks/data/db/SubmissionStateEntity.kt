package com.slukhayka.audiobooks.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Spec-53 T3 — one persistent listener-submission state. The pasted link's
 * local copy is imported first; the row keeps `AWAITING_PLAY` until the
 * player's REAL playing event settles it, so a restart never loses the
 * promise. Multiple rows coexist (several links past one after another).
 */
@Entity(tableName = "submission_states")
data class SubmissionStateEntity(
    /** The door's Source id — the key the playback verdict seam matches. */
    @PrimaryKey val sourceId: String,
    val url: String,
    val bookId: String,
    val metadataJson: String,
    val channelId: String,
    val state: String,
    val reason: String?,
    val createdAt: Long,
    val updatedAt: Long
)
