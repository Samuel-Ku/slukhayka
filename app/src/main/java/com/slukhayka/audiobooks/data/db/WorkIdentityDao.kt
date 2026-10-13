package com.slukhayka.audiobooks.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
abstract class WorkIdentityDao {
    @Query("SELECT state FROM work_merge_operations WHERE operationId = :operationId")
    protected abstract suspend fun operationStateValue(operationId: String): String?

    suspend fun operationStateById(operationId: String): WorkMergeOperationState? =
        operationStateValue(operationId)?.let { WorkMergeOperationState.valueOf(it) }

    @Query("SELECT COUNT(*) FROM work_merge_operations")
    abstract suspend fun operationCount(): Int

    @Query("SELECT COUNT(*) FROM work_identity_redirects")
    abstract suspend fun redirectCount(): Int

    @Query("SELECT * FROM work_merge_preserved_participants WHERE operationId = :operationId ORDER BY bookId, editionId")
    abstract suspend fun preservedParticipants(operationId: String): List<PreservedWorkMergeParticipant>

    @Query("SELECT * FROM work_identity_redirects WHERE namespace = :namespace AND oldKey = :alias")
    abstract suspend fun redirectByAlias(namespace: String, alias: String): WorkIdentityRedirectRow?

    @Query("SELECT EXISTS(SELECT 1 FROM work_merge_preserved_participants WHERE autoMergeExcluded = 1 AND (bookId = :bookId OR editionId = :editionId))")
    abstract suspend fun autoMergeExcluded(bookId: String, editionId: String): Boolean

    @Query("SELECT * FROM work_merge_operations WHERE operationId = :operationId")
    internal abstract suspend fun operationById(operationId: String): WorkMergeOperationEntity?

    @Insert
    internal abstract suspend fun insertOperation(operation: WorkMergeOperationEntity)

    @Insert
    internal abstract suspend fun insertRedirects(redirects: List<WorkIdentityRedirectRow>)

    @Insert
    internal abstract suspend fun insertPreservedParticipants(participants: List<PreservedWorkMergeParticipant>)

    @Query("DELETE FROM work_identity_redirects WHERE operationId = :operationId")
    internal abstract suspend fun deleteOperationRedirects(operationId: String)

    @Query("UPDATE work_merge_operations SET state = 'UNDONE' WHERE operationId = :operationId AND state = 'APPLIED'")
    internal abstract suspend fun markUndone(operationId: String): Int
}
