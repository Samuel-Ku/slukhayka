package com.slukhayka.audiobooks.data.entries

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.LibraryEntryEntity
import com.slukhayka.audiobooks.data.db.ReadthroughMapping
import com.slukhayka.audiobooks.data.db.WorkEntity

/**
 * ADR-0046 §§3–4 / ADR-0047 §1 / spec-54 T15 (#870) — writes a manual add.
 *
 * The DECISION is [ManualBookAddPolicy]; this class only carries it to the
 * rows, and the order matters: the Work first (nothing hangs off a missing
 * Work), then the Library Entry WITH its origin in the same statement, then —
 * only when the listener asked — the Readthrough. A paper or e-book add never
 * touches the Edition or Source tables.
 */
class ManualBookAdder(private val dao: AudiobookDao) {

    sealed interface Result {
        data class Added(
            val libraryEntryId: String,
            val workId: String,
            val origin: LibraryEntryOrigin,
            val readthroughId: String?
        ) : Result

        data class Refused(val reason: String) : Result
    }

    suspend fun add(request: ManualBookAddRequest): Result {
        val plan = ManualBookAddPolicy.plan(request)
        if (plan is ManualBookAddPlan.Refused) return Result.Refused(plan.reason)
        plan as ManualBookAddPlan.Add

        val workKey = ManualBookAddPolicy.workKey(request)
        val entryId = ManualBookAddPolicy.libraryEntryId(request)

        // The Work is upserted, never duplicated: a manual add joins the Work
        // the app already merges on.
        dao.upsertWork(
            WorkEntity(
                id = workKey,
                mergeKey = workKey,
                title = request.title.trim(),
                author = request.author.trim()
            )
        )
        dao.insertLibraryEntryWithOrigin(
            id = entryId,
            workId = workKey,
            origin = plan.origin.name,
            createdAt = request.now
        )

        val pass = plan.readthrough
        if (pass != null) {
            with(ReadthroughMapping) { dao.upsertReadthrough(pass.copy(libraryEntryId = entryId).toEntity()) }
        }

        return Result.Added(
            libraryEntryId = entryId,
            workId = workKey,
            origin = plan.origin,
            readthroughId = pass?.id
        )
    }

    /** The stored origin of one link, or null when there is no such link. */
    suspend fun originOf(bookId: String): LibraryEntryOrigin? {
        val row: LibraryEntryEntity = dao.libraryEntryById(bookId) ?: return null
        return LibraryEntryOrigin.entries.firstOrNull { it.name == row.origin }
    }
}
