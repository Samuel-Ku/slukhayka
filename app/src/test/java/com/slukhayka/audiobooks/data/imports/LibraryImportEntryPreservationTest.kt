package com.slukhayka.audiobooks.data.imports

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.entries.LibraryEntryOrigin
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1397 (review of #1395) — a repeat import of the SAME book id must not wipe
 * the personal row.
 *
 * The reachable door: the Edition lookup is the rendition identity
 * (narrator|language), so a page whose narrator claim changed resolves to
 * `existing == null` while `adapterBookId(url)` yields the same bookId. The
 * new-book branch then writes the Library Entry over an EXISTING personal row.
 * With `INSERT OR REPLACE` that reset the heart, the createdAt ordering and the
 * entry's download state; the upsert's conflict arm keeps them (ADR-0009) and
 * never downgrades a personal origin (ADR-0063).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LibraryImportEntryPreservationTest {

    private lateinit var database: AudiobookDatabase
    private lateinit var imports: LibraryImport

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        imports = LibraryImport(database.audiobookDao(), context, emptyList())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun page(narrator: String) = SourceBookDetail(
        title = "Кобзар",
        author = "Тарас Шевченко",
        narrator = narrator,
        url = "https://sluhay.com/kobzar",
        chapters = listOf(SourceChapter("Розділ", "https://cdn.example.org/1.mp3"))
    )

    @Test
    fun `a repeat import of the same id keeps the personal heart and createdAt`() = runBlocking {
        val dao = database.audiobookDao()
        val first = imports.importBookFromSource(
            sourceId = "sluhay",
            detail = page(narrator = "Іван"),
            origin = LibraryEntryOrigin.EXPLICIT_SAVE
        )
        dao.setFavorite(first.id, true)
        val before = dao.libraryEntryById(first.id)!!
        assertTrue("fixture: the heart is set", before.isFavorite)

        // Same URL → same bookId; a changed narrator claim → the Edition lookup
        // misses, so the import takes the new-book branch over the live entry.
        val second = imports.importBookFromSource(
            sourceId = "sluhay",
            detail = page(narrator = "Петро"),
            origin = LibraryEntryOrigin.EXPLICIT_SAVE
        )

        assertEquals("the same card is reused, not duplicated", first.id, second.id)
        val after = dao.libraryEntryById(first.id)!!
        assertTrue("the listener's heart survives the re-import", after.isFavorite)
        assertEquals("createdAt is the library ordering, not the import time", before.createdAt, after.createdAt)
        assertEquals("a personal link is never downgraded", "EXPLICIT_SAVE", after.origin)
    }
}
