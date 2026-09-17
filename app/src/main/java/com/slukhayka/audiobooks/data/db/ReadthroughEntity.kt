package com.slukhayka.audiobooks.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ADR-0046 / spec-54 T13 (#863) — one pass through one Work in one format.
 *
 * The row is deliberately flat: [journalJson] carries the progress journal the
 * pure `ReadthroughPolicy` already governs, and the UNITS stay in the unit they
 * were observed in (`unit` + `unitValue`) — a shared percentage across formats
 * would be fiction (ADR-0014).
 *
 * AUDIO rows point at the existing Edition ([editionId]); the live player
 * position stays in Listening State and is never duplicated here (ADR-0046 §3).
 * PAPER and EBOOK rows carry no Edition at all (§4).
 */
@Entity(
    tableName = "readthroughs",
    indices = [
        Index(value = ["libraryEntryId"]),
        Index(value = ["workId"])
    ]
)
data class ReadthroughEntity(
    @PrimaryKey val id: String,
    val libraryEntryId: String,
    val workId: String,
    val format: String,
    val state: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val editionId: String?,
    val unit: String,
    val unitValue: Int,
    /** The progress journal, newest last; `[]` for a fresh pass. */
    val journalJson: String = "[]"
)
