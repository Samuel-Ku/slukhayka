package com.slukhayka.audiobooks.data.entries

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.achievements.AchievementProgress
import com.slukhayka.audiobooks.data.achievements.RoomAchievementProgressSource
import com.slukhayka.audiobooks.data.achievements.RoomAchievementStore
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.testing.TestDataFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-0063 — the library is the listener's personal collection.
 *
 * Real Room rows, because the rule under test lives in SQL: a technical
 * materialization, a catalogue-sync anchor or a legacy `UNKNOWN` row can never
 * render as "my book", enter «Імпортоване» without being unknown, or feed an
 * achievement metric. A fake snapshot would prove none of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PersonalLibraryRoomTest {

    private fun <T> withDatabase(block: suspend (AudiobookDatabase) -> T): T = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    /**
     * One materialized book with its anchor row at [origin] — the exact shape
     * an import or a catalogue pass writes. `isDownloaded` and the language
     * are parameters because the personal/detail projections and the award
     * metrics read them.
     */
    private suspend fun seedBook(
        database: AudiobookDatabase,
        bookId: String,
        origin: LibraryEntryOrigin,
        isDownloaded: Boolean = false,
        language: String = ""
    ) {
        val dao = database.audiobookDao()
        val row = TestDataFactory.dataBooks().first().copy(
            id = bookId,
            title = "Книга $bookId",
            isDownloaded = isDownloaded
        )
        dao.insertAudiobooks(listOf(row))
        dao.upsertLibraryEntry(bookId, bookId, false, 1L, if (isDownloaded) 1f else 0f)
        dao.updateLibraryEntryOrigin(bookId, origin.name)
        dao.upsertWork(WorkEntity(id = bookId, mergeKey = "key-$bookId", title = row.title, author = "Автор"))
        dao.insertEdition(
            EditionEntity(
                id = "edition-$bookId",
                workId = bookId,
                narrator = row.narrator,
                language = language,
                totalChapters = 0,
                totalDurationSeconds = 0L
            )
        )
    }

    @Test
    fun `personal reads skip every automatic state`() = withDatabase { database ->
        seedBook(database, "saved", LibraryEntryOrigin.EXPLICIT_SAVE, isDownloaded = true)
        seedBook(database, "imported", LibraryEntryOrigin.EXPLICIT_IMPORT)
        seedBook(database, "technical", LibraryEntryOrigin.TECHNICAL_OPEN)
        seedBook(database, "auto", LibraryEntryOrigin.AUTO_SEED)
        seedBook(database, "sync", LibraryEntryOrigin.CATALOG_SYNC)
        seedBook(database, "legacy", LibraryEntryOrigin.UNKNOWN)

        val dao = database.audiobookDao()
        assertEquals(
            "only explicit links are «Мої книги»",
            setOf("saved", "imported"),
            dao.getPersonalAudiobooks().first().map { it.id }.toSet()
        )
        assertEquals(
            "the detail screen still sees every materialized rendition",
            6,
            dao.getAllAudiobooks().first().size
        )
        assertEquals(
            "a download is a personal-library fact",
            listOf("saved"),
            dao.getDownloadedAudiobooks().first().map { it.id }
        )
    }

    @Test
    fun `the triage queue holds unknown rows with their titles`() = withDatabase { database ->
        seedBook(database, "legacy", LibraryEntryOrigin.UNKNOWN)
        seedBook(database, "technical", LibraryEntryOrigin.TECHNICAL_OPEN)

        val rows = database.audiobookDao()
            .triageRowsWithOrigin(LibraryEntryOrigin.UNKNOWN.name)

        assertEquals(listOf("legacy"), rows.map { it.bookId })
        assertEquals("Книга legacy", rows.single().title)
    }

    @Test
    fun `achievement metrics read personal links only`() = withDatabase { database ->
        seedBook(database, "saved", LibraryEntryOrigin.EXPLICIT_SAVE, isDownloaded = true, language = "uk")
        seedBook(database, "technical", LibraryEntryOrigin.TECHNICAL_OPEN, language = "en")
        seedBook(database, "auto", LibraryEntryOrigin.AUTO_SEED, language = "de")
        seedBook(database, "sync", LibraryEntryOrigin.CATALOG_SYNC, language = "fr")

        val dao = database.audiobookDao()
        dao.insertSources(
            listOf(
                SourceEntity(
                    id = "saved-source",
                    bookId = "saved",
                    editionId = "edition-saved",
                    type = "4read",
                    url = "https://example.test/saved"
                ),
                SourceEntity(
                    id = "tech-source",
                    bookId = "technical",
                    editionId = "edition-technical",
                    type = "youtube",
                    url = "https://example.test/technical"
                )
            )
        )

        val snapshot: AchievementProgress = RoomAchievementProgressSource(
            database.achievementDao(),
            RoomAchievementStore(database.achievementDao()),
            emptySet(),
            abandonedBookIds = flowOf(emptySet())
        ).observe().first()

        assertEquals("one own book", 1L, snapshot.explicitBooks)
        assertEquals("one own language, never the mirror's", 1L, snapshot.knownLanguages)
        assertEquals("one door the listener actually used", 1L, snapshot.usedSourceDoors)
        assertEquals("no browser-guest award for a technical card", 0L, snapshot.browserBooks)
    }

    @Test
    fun `save creates the personal link and playback upgrades a technical one`() = withDatabase { database ->
        val entries = LibraryEntries(database.audiobookDao(), emptyList())
        seedBook(database, "technical", LibraryEntryOrigin.TECHNICAL_OPEN)

        assertTrue(entries.saveToLibrary("technical"))
        assertEquals(
            LibraryEntryOrigin.EXPLICIT_SAVE,
            entries.observeOrigin("technical").first()
        )
        assertEquals(
            listOf("technical"),
            database.audiobookDao().getPersonalAudiobooks().first().map { it.id }
        )

        seedBook(database, "played", LibraryEntryOrigin.TECHNICAL_OPEN)
        assertTrue(entries.markPlaybackStarted("played"))
        assertEquals(
            LibraryEntryOrigin.EXPLICIT_SAVE,
            entries.observeOrigin("played").first()
        )
        assertFalse(
            "a catalogue anchor is never personal",
            LibraryEntryOriginPolicy.isPersonal(LibraryEntryOrigin.CATALOG_SYNC)
        )
    }

    /**
     * ADR-0063 §9 — an automatic catalogue write must never rewrite a legacy
     * row's origin. Before the fix, `upsertLibraryEntry`'s conflict arm reset
     * any non-personal origin to the incoming one, so the next Огляд refresh
     * flipped `UNKNOWN` to `CATALOG_SYNC` and dropped the row out of
     * «Імпортоване» without the listener deciding.
     */
    @Test
    fun `a catalogue upsert never rewrites a legacy UNKNOWN row`() = withDatabase { database ->
        seedBook(database, "legacy", LibraryEntryOrigin.UNKNOWN)
        val dao = database.audiobookDao()

        dao.upsertLibraryEntry(
            id = "legacy",
            workId = "legacy",
            isFavorite = false,
            createdAt = 9L,
            downloadProgress = 0f,
            origin = LibraryEntryOrigin.CATALOG_SYNC.name
        )

        assertEquals("UNKNOWN", dao.libraryEntryById("legacy")!!.origin)
        assertEquals(
            "the row still waits for the listener in «Імпортоване»",
            listOf("legacy"),
            dao.triageRowsWithOrigin("UNKNOWN").map { it.bookId }
        )
        assertTrue("and it is still not personal", dao.getPersonalAudiobooksOnce().isEmpty())
    }
}
