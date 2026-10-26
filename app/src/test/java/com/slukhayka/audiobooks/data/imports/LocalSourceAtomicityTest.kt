package com.slukhayka.audiobooks.data.imports

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * #618 Local Source T3 — every related Room write of one local Edition runs
 * in ONE transaction: an injected failure rolls the Edition back whole (no
 * partial card, no Listening State drift, committed siblings untouched);
 * staged leftovers from a dead process are still swept; one failed Edition
 * never cancels an already-committed sibling; and concurrent imports of the
 * same bytes never fork a duplicate.
 *
 * ADR-0061 — the folder is the storage: every import here must leave NO
 * private copy behind (the old copy-promotion assertions are gone with the
 * copies themselves). A track's only address is its SAF document URI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalSourceAtomicityTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
        libraryDir().deleteRecursively()
        stagingDir().deleteRecursively()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun libraryDir() = File(context.filesDir, LibraryImport.LOCAL_AUDIO_DIR)
    private fun stagingDir() = File(context.filesDir, LibraryImport.LOCAL_STAGING_DIR)

    /** ADR-0061: the private-copy dir must stay empty on every path. */
    private fun privateCopies(): List<File> = libraryDir().listFiles()?.toList().orEmpty()

    private fun entry(name: String, byte: Int, folder: String? = null) = LocalAudioEntry(
        name,
        folder,
        uri = "content://test/${folder.orEmpty()}/$name"
    ) { ByteArrayInputStream(ByteArray(16) { byte.toByte() }) }

    /**
     * The real Room transaction seam, with an optional injected failure on the
     * Nth block: the block's writes complete and then the transaction throws,
     * so Room rolls the whole Edition back (a true atomicity probe, not a
     * pre-write abort).
     */
    private fun imports(failOnCall: Int? = null): LibraryImport {
        var calls = 0
        return LibraryImport(
            dao,
            context,
            emptyList(),
            writeBatchRunner = { block ->
                calls++
                db.withTransaction {
                    block()
                    if (calls == failOnCall) throw IllegalStateException("injected failure")
                }
            }
        )
    }

    @Test
    fun `importing a folder addresses tracks by document uri and copies nothing`() = runBlocking {
        val entries = listOf(
            LocalAudioEntry("01.mp3", "Кобзар", uri = "content://tree/books/01.mp3") {
                ByteArrayInputStream(ByteArray(16) { 1 })
            },
            LocalAudioEntry("02.mp3", "Кобзар", uri = "content://tree/books/02.mp3") {
                ByteArrayInputStream(ByteArray(16) { 2 })
            }
        )

        val result = imports().importAudioEntries(entries, sourceTreeUri = "content://tree/books")

        assertEquals(1, result.booksImported)
        assertEquals(2, result.filesImported)
        val book = dao.getAllAudiobooksOnce().single()
        val source = dao.getSourcesForBookSync(book.id).single { it.type == "local" }
        val tracks = dao.getTracksForSourceSync(source.id).sortedBy { it.trackIndex }
        assertEquals(
            listOf("content://tree/books/01.mp3", "content://tree/books/02.mp3"),
            tracks.map { it.url }
        )
        assertTrue("ADR-0061: жодної приватної копії", tracks.all { it.localFilePath == null })
        assertTrue("тека на пристрої не виросла", privateCopies().isEmpty())
    }

    @Test
    fun `the single-file door writes the document uri and copies nothing`() = runBlocking {
        val book = imports().importLocalAudioStream(
            "Книга.mp3",
            ByteArrayInputStream(ByteArray(32)),
            "content://tree/file/Книга.mp3"
        )

        val source = dao.getSourcesForBookSync(book.id).single { it.type == "local" }
        val track = dao.getTracksForSourceSync(source.id).single()
        assertEquals("content://tree/file/Книга.mp3", track.url)
        assertNull(track.localFilePath)
        assertTrue(privateCopies().isEmpty())
    }

    @Test
    fun `a failed preview book reports every unadded file without losing committed siblings`() = runBlocking {
        assertEquals(1, imports().importAudioEntries(listOf(entry("committed.mp3", 9, "Кобзар"))).booksImported)
        val prior = dao.getAllAudiobooksOnce().single()
        val priorChapters = dao.getChaptersListForBook(prior.id)
        com.slukhayka.audiobooks.data.listening.ListeningStateStore(dao).updateProgress(prior.id, 0, 17L)
        val listening = dao.getPlaybackProgressSync(prior.id)
        val unreadable = LocalAudioEntry("03.mp3", "A", uri = "content://test/A/03.mp3") {
            throw java.io.IOException("unreadable fixture")
        }
        val entries = listOf(
            entry("01.mp3", 1, "A"), entry("02.mp3", 2, "A"), unreadable,
            entry("04.mp3", 9, "A"), entry("01.mp3", 5, "B")
        )
        val plan = ImportPlanner.buildPlan(SourceRef.Folder("content://tree/partial-write"), entries)
        val result = imports(failOnCall = 1).applyImportPlan(plan)

        assertEquals(1, result.booksImported)
        assertEquals(1, result.filesImported)
        assertEquals("two rolled-back files and one unreadable file", 3, result.skippedFiles)
        assertEquals("the committed library duplicate is counted separately", 1, result.duplicateFiles)
        assertEquals(
            "Імпортовано 1 книг (1 файлів) · 1 дублікатів пропущено · 3 файлів не вдалося додати",
            com.slukhayka.audiobooks.ui.library.OutcomeMessages.importOutcome(result)
        )
        assertEquals(
            setOf(prior.id, dao.getAllAudiobooksOnce().single { it.title == "B" }.id),
            dao.getAllAudiobooksOnce().map { it.id }.toSet()
        )
        assertEquals(priorChapters, dao.getChaptersListForBook(prior.id))
        assertEquals(listening, dao.getPlaybackProgressSync(prior.id))
        assertTrue(privateCopies().isEmpty())
        assertTrue(stagingDir().listFiles().isNullOrEmpty())
    }

    @Test
    fun `cancelling a confirmed plan never commits partial chapters`() = runBlocking {
        val importer = imports()
        assertEquals(1, importer.importAudioEntries(listOf(entry("01.mp3", 9, "Кобзар"))).booksImported)
        val existing = dao.getAllAudiobooksOnce().single()
        val chapters = dao.getChaptersListForBook(existing.id)
        com.slukhayka.audiobooks.data.listening.ListeningStateStore(dao).updateProgress(existing.id, 0, 12L)
        val progress = dao.getPlaybackProgressSync(existing.id)

        for (merge in listOf(false, true)) {
            val cancelled = LocalAudioEntry("03.mp3", "Кобзар", uri = "content://test/Кобзар/03.mp3") {
                throw kotlinx.coroutines.CancellationException("cancel plan read")
            }
            var plan = ImportPlanner.buildPlan(
                SourceRef.Folder("content://tree/cancel-plan"),
                listOf(entry("02.mp3", 2, "Кобзар"), cancelled),
                listOf(ImportPlanner.ExistingWork(existing.id, existing.title, existing.mergeKey.orEmpty()))
            )
            if (merge) plan = ImportPlanner.acceptMerge(plan, plan.books.single().id)

            val result = runCatching { importer.applyImportPlan(plan) }

            assertTrue(
                "cancellation must reach the caller (merge=$merge)",
                result.exceptionOrNull() is kotlinx.coroutines.CancellationException
            )
            assertEquals(listOf(existing), dao.getAllAudiobooksOnce())
            assertEquals(chapters, dao.getChaptersListForBook(existing.id))
            assertEquals(progress, dao.getPlaybackProgressSync(existing.id))
            assertTrue("no chapter and no file of the cancelled plan", privateCopies().isEmpty())
            assertTrue(stagingDir().listFiles().isNullOrEmpty())
        }
    }

    @Test
    fun `cancelling a rescan append commits nothing`() = runBlocking {
        val importer = imports()
        assertEquals(1, importer.importAudioEntries(listOf(entry("committed.mp3", 9))).booksImported)

        fun cancelling(name: String): LocalAudioEntry {
            var opened = false
            return LocalAudioEntry(name, "Кобзар", uri = "content://test/Кобзар/$name") {
                assertFalse("stream must be opened exactly once", opened)
                opened = true
                throw kotlinx.coroutines.CancellationException("cancel scan read")
            }
        }

        val tree = "content://tree/cancelled-append"
        assertEquals(
            1,
            importer.applyImportPlan(
                ImportPlanner.buildPlan(SourceRef.Folder(tree), listOf(entry("01.mp3", 1, "Кобзар")))
            ).booksImported
        )
        val book = dao.getAudiobooksBySourceTree(tree).single()
        val chapters = dao.getChaptersListForBook(book.id)
        com.slukhayka.audiobooks.data.listening.ListeningStateStore(dao).updateProgress(book.id, 0, 12L)
        val progress = dao.getPlaybackProgressSync(book.id)

        val cancelledAppend = runCatching {
            importer.rescanAudioEntries(
                listOf(entry("01.mp3", 1, "Кобзар"), entry("03.mp3", 3, "Кобзар"), cancelling("04.mp3")),
                tree
            )
        }

        assertTrue(
            "cancellation must reach the caller",
            cancelledAppend.exceptionOrNull() is kotlinx.coroutines.CancellationException
        )
        assertEquals(chapters, dao.getChaptersListForBook(book.id))
        assertEquals(progress, dao.getPlaybackProgressSync(book.id))
        assertEquals(book, dao.getAudiobookById(book.id))
        assertTrue(privateCopies().isEmpty())
        assertTrue(stagingDir().listFiles().isNullOrEmpty())
    }

    @Test
    fun `an unreadable file is skipped without a book`() = runBlocking {
        var reads = 0
        val unreadable = LocalAudioEntry("01.mp3", "Кобзар", uri = "content://test/Кобзар/01.mp3") {
            reads++
            throw java.io.IOException("unreadable fixture")
        }

        val report = imports().rescanAudioEntries(listOf(unreadable), "content://tree/unreadable")

        assertEquals("the stream is opened once, not re-read", 1, reads)
        assertEquals(1, report.skippedFiles)
        assertEquals(0, report.newBooks)
        assertEquals(0, report.newChapters)
        assertTrue(dao.getAllAudiobooksOnce().isEmpty())
        assertTrue(privateCopies().isEmpty())
    }

    @Test
    fun `a rolled back rename observation does not abort another books append`() = runBlocking {
        val tree = "content://tree/provenance-rollback"
        val initial = listOf(entry("01.mp3", 1, "А"), entry("01.mp3", 2, "Б"))
        assertEquals(2, imports().applyImportPlan(ImportPlanner.buildPlan(SourceRef.Folder(tree), initial)).booksImported)
        val books = dao.getAudiobooksBySourceTree(tree)
        val first = books.first()
        val second = books.last()
        val firstChapters = dao.getChaptersListForBook(first.id)
        com.slukhayka.audiobooks.data.listening.ListeningStateStore(dao).updateProgress(first.id, 0, 9L)
        val listening = dao.getPlaybackProgressSync(first.id)
        val renamed = initial.single { it.parentFolder == first.title }.copy(parentFolder = "Перейменована")
        val untouched = initial.single { it.parentFolder == second.title }
        val outcome = runCatching {
            imports(failOnCall = 1).rescanAudioEntries(
                listOf(renamed, untouched, entry("02.mp3", 3, second.title)),
                tree
            )
        }

        assertTrue(
            "one failed provenance transaction must not abort the scan: ${outcome.exceptionOrNull()}",
            outcome.isSuccess
        )
        val report = outcome.getOrThrow()
        assertEquals(0, report.newBooks)
        assertEquals(1, report.newChapters)
        assertEquals(1, report.movedFiles)
        assertEquals(firstChapters, dao.getChaptersListForBook(first.id))
        assertEquals(listening, dao.getPlaybackProgressSync(first.id))
        assertEquals(2, dao.getChaptersListForBook(second.id).size)
        assertTrue(privateCopies().isEmpty())
    }

    @Test
    fun `an injected failure rolls the Edition back`() = runBlocking {
        val result = imports(failOnCall = 1).importAudioEntries(listOf(entry("01.mp3", 1)))

        assertEquals("a rolled-back Edition is never counted", 0, result.booksImported)
        assertEquals(0, result.filesImported)
        assertEquals(0, dao.getAllAudiobooksOnce().size)
        assertTrue("nothing was written to disk to clean up", privateCopies().isEmpty())
        assertTrue("no staged leftover either", stagingDir().listFiles().isNullOrEmpty())
    }

    @Test
    fun `a failed Edition keeps an already-committed book intact`() = runBlocking {
        val first = imports().importAudioEntries(listOf(entry("01.mp3", 1)))
        assertEquals(1, first.booksImported)
        val committed = dao.getAllAudiobooksOnce().single()
        val committedChapters = dao.getChaptersListForBook(committed.id)

        val second = imports(failOnCall = 1).importAudioEntries(listOf(entry("02.mp3", 2)))

        assertEquals(0, second.booksImported)
        assertEquals(listOf(committed), dao.getAllAudiobooksOnce())
        assertEquals(committedChapters, dao.getChaptersListForBook(committed.id))
        assertTrue(privateCopies().isEmpty())
    }

    @Test
    fun `one failed Edition does not cancel an already-committed sibling`() = runBlocking {
        // Two loose root files = two Editions; the FIRST transaction fails.
        val result = imports(failOnCall = 1).importAudioEntries(
            listOf(entry("01.mp3", 1), entry("02.mp3", 2))
        )

        assertEquals("the second Edition committed", 1, result.booksImported)
        assertEquals(1, result.filesImported)
        assertEquals(1, dao.getAllAudiobooksOnce().size)
        assertTrue(privateCopies().isEmpty())
    }

    @Test
    fun `staging leftovers from a dead process are swept by the next pass`() = runBlocking {
        stagingDir().mkdirs()
        val leftover = File(stagingDir(), "01-1.mp3").apply { writeBytes(ByteArray(8) { 9 }) }
        assertTrue(leftover.exists())

        val result = imports().importAudioEntries(listOf(entry("01.mp3", 1)))

        assertEquals(1, result.booksImported)
        assertFalse("the staged leftover is gone", leftover.exists())
        assertTrue(stagingDir().listFiles().isNullOrEmpty())
        assertTrue("the library dir stays empty (no copies)", privateCopies().isEmpty())
    }

    @Test
    fun `a successful import leaves no staging and no private copies`() = runBlocking {
        val result = imports().importAudioEntries(
            listOf(entry("01.mp3", 1, "Кобзар"), entry("02.mp3", 2, "Кобзар"))
        )

        assertEquals(1, result.booksImported)
        assertEquals(2, result.filesImported)
        assertTrue(stagingDir().listFiles().isNullOrEmpty())
        assertTrue(privateCopies().isEmpty())
    }

    @Test
    fun `concurrent imports of the same bytes never fork a duplicate`() = runBlocking {
        val imports = imports()

        val results = listOf(
            async(Dispatchers.IO) { imports.importAudioEntries(listOf(entry("a.mp3", 5))) },
            async(Dispatchers.IO) { imports.importAudioEntries(listOf(entry("b.mp3", 5))) }
        ).awaitAll()

        assertEquals("exactly one Edition", 1, dao.getAllAudiobooksOnce().size)
        assertEquals(1, results.sumOf { it.booksImported })
        assertEquals(
            "the loser is reported as a duplicate, not a second card",
            1,
            results.sumOf { it.duplicateFiles }
        )
        assertTrue(privateCopies().isEmpty())
    }
}
