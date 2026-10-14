package com.slukhayka.audiobooks.data.merge

import androidx.room.withTransaction
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.WorkEntity

/** Opaque Work IDs and bibliographic merge keys are separate namespaces. */
sealed interface WorkIdentityInput {
    val value: String
    data class WorkId(override val value: String) : WorkIdentityInput
    data class MergeKey(override val value: String) : WorkIdentityInput
}

sealed interface WorkResolution {
    data class Resolved(val work: WorkEntity) : WorkResolution
    data class Missing(val input: WorkIdentityInput) : WorkResolution
    data class Refused(val reason: IdentityRefusal) : WorkResolution
}

enum class IdentityRefusal { MalformedIdentity, Cycle, Dangling, ConflictingAlias, AliasLimit }

/** Resolves stored identity in the same Room instance used by manual commands. */
class WorkIdentityAccess(
    private val database: AudiobookDatabase,
    gate: ManualWorkMergeGate,
) {
    init { gate.requireDatabase(database) }

    suspend fun resolve(input: WorkIdentityInput): WorkResolution = database.withTransaction {
        resolveStored(input)
    }

    private suspend fun resolveStored(input: WorkIdentityInput): WorkResolution {
        val visited = mutableSetOf<WorkIdentityInput>()
        var current = input
        var followed = 0
        val dao = database.audiobookDao()
        val identity = database.workIdentityDao()
        while (true) {
            if (current.value.isBlank()) return WorkResolution.Refused(IdentityRefusal.MalformedIdentity)
            if (!visited.add(current)) return WorkResolution.Refused(IdentityRefusal.Cycle)
            val matches = when (val key = current) {
                is WorkIdentityInput.WorkId -> listOfNotNull(dao.getWorkById(key.value))
                is WorkIdentityInput.MergeKey -> identity.worksForMergeKey(key.value)
            }
            if (matches.size > 1) return WorkResolution.Refused(IdentityRefusal.ConflictingAlias)
            val namespace = when (current) {
                is WorkIdentityInput.WorkId -> "WORK_ID"
                is WorkIdentityInput.MergeKey -> "MERGE_KEY"
            }
            val redirect = identity.redirectByAlias(namespace, current.value)
            if (redirect == null) {
                return matches.singleOrNull()?.let { WorkResolution.Resolved(it) }
                    ?: if (followed == 0) WorkResolution.Missing(input)
                    else WorkResolution.Refused(IdentityRefusal.Dangling)
            }
            if (matches.any { it.id != redirect.canonicalWorkId }) {
                return WorkResolution.Refused(IdentityRefusal.ConflictingAlias)
            }
            if (followed == MAX_REDIRECTS) return WorkResolution.Refused(IdentityRefusal.AliasLimit)
            followed++
            // Redirect targets are Work IDs; never retry them as bibliographic merge keys.
            current = WorkIdentityInput.WorkId(redirect.canonicalWorkId)
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 64
    }
}
