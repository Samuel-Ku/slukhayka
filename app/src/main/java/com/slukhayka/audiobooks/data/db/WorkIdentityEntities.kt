package com.slukhayka.audiobooks.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class WorkMergeOperationState { APPLIED, UNDONE }

/** Durable local operation. Row payloads use an explicitly versioned recoverable format. */
@Entity(tableName = "work_merge_operations")
data class WorkMergeOperationEntity(
    @PrimaryKey val operationId: String,
    val state: String,
    val canonicalWorkId: String,
    val participantWorkIds: String,
    val approvedIdentity: String,
    val payloadVersion: Int,
    val beforeImages: ByteArray,
    val afterImages: ByteArray,
)

/** Namespace separation prevents a Work id from being mistaken for a merge key. */
@Entity(tableName = "work_identity_redirects", primaryKeys = ["namespace", "oldKey"],
    indices = [Index("operationId"), Index("canonicalWorkId")])
data class WorkIdentityRedirectRow(
    val namespace: String,
    val oldKey: String,
    val canonicalWorkId: String,
    val operationId: String,
)

/** Retained after Undo: undoing identity does not authorize erasing an independent Edition. */
@Entity(tableName = "work_merge_preserved_participants",
    primaryKeys = ["operationId", "bookId", "editionId"],
    indices = [Index("bookId"), Index("editionId")])
data class PreservedWorkMergeParticipant(
    val operationId: String,
    val bookId: String,
    val editionId: String,
    val autoMergeExcluded: Boolean,
)
