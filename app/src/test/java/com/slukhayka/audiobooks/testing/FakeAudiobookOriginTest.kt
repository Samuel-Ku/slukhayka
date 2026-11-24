package com.slukhayka.audiobooks.testing

import com.slukhayka.audiobooks.data.entries.LibraryEntryOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1399 (review of #1398) — the fake DAO's conflict arm mirrors the production
 * SQL: an AUTOMATIC incoming origin never rewrites an existing row's origin,
 * only an incoming PERSONAL one does. Previously the fake was correct by
 * inspection only — no test touched this branch, so drift stayed green.
 */
class FakeAudiobookOriginTest {

    private suspend fun seed(dao: FakeAudiobookDao, origin: LibraryEntryOrigin) {
        dao.upsertLibraryEntry(
            id = "book",
            workId = "book",
            isFavorite = false,
            createdAt = 1L,
            downloadProgress = 0f,
            origin = origin.name
        )
    }

    @Test
    fun `an automatic upsert never rewrites a legacy origin`() = runBlocking {
        val dao = FakeAudiobookDao()
        seed(dao, LibraryEntryOrigin.UNKNOWN)

        dao.upsertLibraryEntry("book", "book", false, 2L, 0f, origin = LibraryEntryOrigin.CATALOG_SYNC.name)

        assertEquals("UNKNOWN", dao.libraryEntryById("book")!!.origin)
    }

    @Test
    fun `an automatic upsert never downgrades a technical anchor either`() = runBlocking {
        val dao = FakeAudiobookDao()
        seed(dao, LibraryEntryOrigin.TECHNICAL_OPEN)

        dao.upsertLibraryEntry("book", "book", false, 2L, 0f, origin = LibraryEntryOrigin.CATALOG_SYNC.name)

        assertEquals("TECHNICAL_OPEN", dao.libraryEntryById("book")!!.origin)
    }

    @Test
    fun `an incoming personal origin upgrades the row`() = runBlocking {
        val dao = FakeAudiobookDao()
        seed(dao, LibraryEntryOrigin.TECHNICAL_OPEN)

        dao.upsertLibraryEntry("book", "book", false, 2L, 0f, origin = LibraryEntryOrigin.EXPLICIT_SAVE.name)

        assertEquals("EXPLICIT_SAVE", dao.libraryEntryById("book")!!.origin)
    }

    @Test
    fun `a personal row is never downgraded by an automatic pass`() = runBlocking {
        val dao = FakeAudiobookDao()
        seed(dao, LibraryEntryOrigin.EXPLICIT_IMPORT)

        dao.upsertLibraryEntry("book", "book", false, 2L, 0f, origin = LibraryEntryOrigin.CATALOG_SYNC.name)

        assertEquals("EXPLICIT_IMPORT", dao.libraryEntryById("book")!!.origin)
    }
}
