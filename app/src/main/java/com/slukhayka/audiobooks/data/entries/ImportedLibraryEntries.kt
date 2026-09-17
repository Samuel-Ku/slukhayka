package com.slukhayka.audiobooks.data.entries

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.LibraryEntryEntity

/**
 * ADR-0047 / spec-54 T09 (#867) — the «Імпортоване» queue as ONE seam.
 *
 * The subsection is a TEMPORARY home, not a second library: it lists exactly the
 * links whose origin the data does not recover ([LibraryEntryOrigin.UNKNOWN])
 * and offers the two explicit actions. All the decisions live in the pure
 * [LibraryEntryOriginPolicy], so this class only carries them to the rows.
 */
class ImportedLibraryEntries(private val dao: AudiobookDao) {

    /** The rows waiting for a decision, newest first. */
    suspend fun pending(): List<LibraryEntryEntity> =
        dao.libraryEntriesWithOrigin(LibraryEntryOrigin.UNKNOWN.name)

    /**
     * Applies one explicit action to one row.
     *
     * @return false when the row does not exist or is not in the queue — the
     *   caller never gets a silent success for something it did not decide.
     */
    suspend fun triage(bookId: String, action: LibraryEntryOriginPolicy.TriageAction): Boolean {
        val row = dao.libraryEntryById(bookId) ?: return false
        val origin = LibraryEntryOrigin.entries.firstOrNull { it.name == row.origin } ?: return false
        if (!LibraryEntryOriginPolicy.needsTriage(origin)) return false
        return when (val next = LibraryEntryOriginPolicy.afterTriage(origin, action)) {
            // Removing the LINK: the Work, the Edition and the Source stay.
            null -> {
                dao.deleteLibraryEntry(bookId)
                true
            }
            else -> {
                dao.updateLibraryEntryOrigin(bookId, next.name)
                true
            }
        }
    }
}
