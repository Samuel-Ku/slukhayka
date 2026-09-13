package com.slukhayka.audiobooks.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * Spec-51 (#689) — a listener's own collection. Local-first: the row exists
 * offline and never waits for a network round trip. Text is already hygienic
 * (see [com.slukhayka.audiobooks.data.collections.ListenerCollectionLimits]).
 */
@Entity(tableName = "listener_collections", primaryKeys = ["id"])
data class ListenerCollectionEntity(
    val id: String,
    val title: String,
    val description: String,
    val createdAt: Long,
    // Spec-51 (#695) — the FORK's frozen attribution. Nullable: an ordinary own
    // collection has none, and only a fork ever fills these in.
    val sourceDocumentId: String? = null,
    val sourceTitle: String? = null,
    val sourcePseudonym: String? = null,
    val snapshotAt: Long? = null
)

/**
 * One book's place in a collection. The composite key is what makes "already
 * there" a database fact rather than a UI check, and [addedAt] carries the
 * AC's insertion order.
 */
@Entity(
    tableName = "listener_collection_items",
    primaryKeys = ["collectionId", "bookId"],
    indices = [Index("collectionId"), Index("bookId")]
)
data class ListenerCollectionItemEntity(
    val collectionId: String,
    val bookId: String,
    val reason: String,
    val addedAt: Long
)
