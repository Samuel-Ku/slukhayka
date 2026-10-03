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
 * partial card) and removes only the private copies of that attempt; existing
 * copies survive; staged leftovers from a dead process are swept on the next
 * pass; one failed Edition never cancels an already-committed sibling; and
 * concurrent imports of the same bytes never fork a duplicate.
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

    private fun entry(name: String, byte: Int, folder: String? = null) =
        LocalAudioEntry(name, folder) { ByteArrayInputStream(ByteArray(16) { byte.toByte() }) }

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
    fun `a failed preview book reports every unadded file without losing committed siblings`() = runBlocking {
        assertEquals(1, imports().importAudioEntries(listOf(entry("committed.mp3", 9, "Кобзар"))).booksImported)
        val prior = dao.getAllAudiobooksOnce().single()
        val priorChapters = dao.getChaptersListForBook(prior.id)
        com.slukhayka.audiobooks.data.listening.ListeningStateStore(dao).updateProgress(prior.id, 0, 17L)
        val listening = dao.getPlaybackProgressSync(prior.id)
        val priorCopy = libraryDir().listFiles()!!.single()
        val unreadable = LocalAudioEntry("03.mp3", "A") { throw java.io.IOException("unreadable fixture") }
        val entries = listOf(entry("01.mp3", 1, "A"), entry("02.mp3", 2, "A"), unreadable,
            entry("04.mp3", 9, "A"), entry("01.mp3", 5, "B"))
        val plan = ImportPlanner.buildPlan(SourceRef.Folder("content://tree/partial-write"), entries)
        val result = imports(failOnCall = 1).applyImportPlan(plan)
        assertEquals(1, result.booksImported)
        assertEquals(1, result.filesImported)
        assertEquals("two rolled-back copies and one unreadable file", 3, result.skippedFiles)
        assertEquals("the committed library duplicate is counted separately", 1, result.duplicateFiles)
        assertEquals("Імпортовано 1 книг (1 файлів) · 1 дублікатів пропущено · 3 файлів не вдалося додати",
            com.slukhayka.audiobooks.ui.library.OutcomeMessages.importOutcome(result))
        assertEquals(setOf(prior.id, dao.getAllAudiobooksOnce().single { it.title == "B" }.id), dao.getAllAudiobooksOnce().map { it.id }.toSet())
        assertEquals(priorChapters, dao.getChaptersListForBook(prior.id))
        assertEquals(listening, dao.getPlaybackProgressSync(prior.id))
        assertTrue(priorCopy.exists())
        assertEquals(2, libraryDir().listFiles()!!.size)
        assertTrue(stagingDir().listFiles().isNullOrEmpty())
    }

    @Test
    fun `cancelling a confirmed plan never commits partial chapters or orphan copies`() = runBlocking {
        val importer = imports()
        assertEquals(1, importer.importAudioEntries(listOf(entry("01.mp3", 9, "Кобзар"))).booksImported)
        val existing = dao.getAllAudiobooksOnce().single()
        val chapters = dao.getChaptersListForBook(existing.id)
        com.slukhayka.audiobooks.data.listening.ListeningStateStore(dao).updateProgress(existing.id, 0, 12L)
        val progress = dao.getPlaybackProgressSync(existing.id)
        val committedCopies = libraryDir().listFiles()!!.toSet()
        for (merge in listOf(false, true)) {
            val cancelled = LocalAudioEntry("03.mp3", "Кобзар") { throw kotlinx.coroutines.CancellationException("cancel plan copy") }
            var plan = ImportPlanner.buildPlan(SourceRef.Folder("content://tree/cancel-plan"), listOf(entry("02.mp3", 2, "Кобзар"), cancelled),
                listOf(ImportPlanner.ExistingWork(existing.id, existing.title, existing.mergeKey.orEmpty())))
            if (merge) plan = ImportPlanner.acceptMerge(plan, plan.books.single().id)
            val result = runCatching { importer.applyImportPlan(plan) }
            assertTrue("cancellation must reach the caller (merge=$merge)", result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals(listOf(existing), dao.getAllAudiobooksOnce())
            assertEquals(chapters, dao.getChaptersListForBook(existing.id))
            assertEquals(progress, dao.getPlaybackProgressSync(existing.id))
            assertEquals(committedCopies, libraryDir().listFiles()!!.toSet())
            assertTrue(stagingDir().listFiles().isNullOrEmpty())
        }
    }

    @Test
    fun `cancelling a rescan copy removes only uncommitted copies for new and existing books`() = runBlocking {
        val importer = imports()
        assertEquals(1, importer.importAudioEntries(listOf(entry("committed.mp3", 9))).booksImported)
        val committedCopies = libraryDir().listFiles()!!.toSet()
        fun cancelling(name: String, byte: Int): LocalAudioEntry {
            var reads = 0
            return LocalAudioEntry(name, "Кобзар") {
                reads++
                if (reads == 2) throw kotlinx.coroutines.CancellationException("cancel second copy")
                ByteArrayInputStream(ByteArray(16) { byte.toByte() })
            }
        }
        val tree = "content://tree/cancelled-copy"
        val cancelledNew = runCatching {
            importer.rescanAudioEntries(listOf(entry("01.mp3", 1, "Кобзар"), cancelling("02.mp3", 2)), tree)
        }
        assertTrue(cancelledNew.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals("the new book was never committed", 1, dao.getAllAudiobooksOnce().size)
        assertEquals("only previously committed copies remain", committedCopies, libraryDir().listFiles()!!.toSet())
        assertTrue(stagingDir().listFiles().isNullOrEmpty())

        assertEquals(1, importer.applyImportPlan(ImportPlanner.buildPlan(SourceRef.Folder(tree), listOf(entry("01.mp3", 1, "Кобзар")))).booksImported)
        val book = dao.getAudiobooksBySourceTree(tree).single()
        val chapters = dao.getChaptersListForBook(book.id)
        com.slukhayka.audiobooks.data.listening.ListeningStateStore(dao).updateProgress(book.id, 0, 12L)
        val progress = dao.getPlaybackProgressSync(book.id)
        val existingCopies = libraryDir().listFiles()!!.toSet()
        val cancelledAppend = runCatching {
            importer.rescanAudioEntries(listOf(entry("01.mp3", 1, "Кобзар"), entry("03.mp3", 3, "Кобзар"), cancelling("04.mp3", 4)), tree)
        }
        assertTrue(cancelledAppend.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(chapters, dao.getChaptersListForBook(book.id))
        assertEquals(progress, dao.getPlaybackProgressSync(book.id))
        assertEquals(book, dao.getAudiobookById(book.id))
        assertEquals(existingCopies, libraryDir().listFiles()!!.toSet())
        assertTrue(stagingDir().listFiles().isNullOrEmpty())
    }

    @Test
    fun `a source changed between hashing and copying is skipped without a book or private copy`() = runBlocking {
        var reads = 0
        val changing = LocalAudioEntry("01.mp3", "Кобзар") {
            reads++
            ByteArrayInputStream(ByteArray(16) { reads.toByte() })
        }
        val report = imports().rescanAudioEntries(listOf(changing), "content://tree/changing")
        assertEquals(2, reads)
        assertEquals(1, report.skippedFiles)
        assertEquals(0, report.newBooks)
        assertEquals(0, report.newChapters)
        assertEquals(0, report.duplicateFiles)
        assertTrue(dao.getAllAudiobooksOnce().isEmpty())
        assertTrue(libraryDir().listFiles().isNullOrEmpty())
        assertTrue(stagingDir().listFiles().isNullOrEmpty())
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
        val privateCopies = libraryDir().listFiles()!!.toList()
        val renamed = initial.single { it.parentFolder == first.title }.copy(parentFolder = "Перейменована")
        val untouched = initial.single { it.parentFolder == second.title }
        val outcome = runCatching {
            imports(failOnCall = 1).rescanAudioEntries(listOf(renamed, untouched, entry("02.mp3", 3, second.title)), tree)
        }
        assertTrue("one failed provenance transaction must not abort the scan: ${outcome.exceptionOrNull()}", outcome.isSuccess)
        val report = outcome.getOrThrow()
        assertEquals(0, report.newBooks)
        assertEquals(1, report.newChapters)
        assertEquals(1, report.movedFiles)
        assertEquals(firstChapters, dao.getChaptersListForBook(first.id))
        assertEquals(listening, dao.getPlaybackProgressSync(first.id))
        assertEquals(2, dao.getChaptersListForBook(second.id).size)
        assertTrue(privateCopies.all { it.exists() })
        assertEquals(3, libraryDir().listFiles()!!.size)
    }

    @Test
    fun `an injected failure rolls the Edition back and removes its copies`() = runBlocking {
        val result = imports(failOnCall = 1).importAudioEntries(listOf(entry("01.mp3", 1)))

        assertEquals("a rolled-back Edition is never counted", 0, result.booksImported)
        assertEquals(0, result.filesImported)
        assertEquals(0, dao.getAllAudiobooksOnce().size)
        assertTrue("the promoted copy of the failed attempt is gone", libraryDir().listFiles().isNullOrEmpty())
        assertTrue("no staged leftover either", stagingDir().listFiles().isNullOrEmpty())
    }

    @Test
    fun `a failed Edition never removes an already-committed private copy`() = runBlocking {
        val first = imports().importAudioEntries(listOf(entry("01.mp3", 1)))
        assertEquals(1, first.booksImported)
        val committed = libraryDir().listFiles()!!.single()

        val second = imports(failOnCall = 1).importAudioEntries(listOf(entry("02.mp3", 2)))

        assertEquals(0, second.booksImported)
        assertTrue("the committed copy survives the sibling's rollback", committed.exists())
        assertEquals(1, dao.getAllAudiobooksOnce().size)
        assertEquals("only the committed copy remains", 1, libraryDir().listFiles()!!.size)
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
        assertEquals(1, libraryDir().listFiles()!!.size)
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
        assertEquals("the committed copy lives in the library dir", 1, libraryDir().listFiles()!!.size)
    }

    @Test
    fun `staging is promoted, never left behind, on a successful import`() = runBlocking {
        val result = imports().importAudioEntries(
            listOf(entry("01.mp3", 1, "Кобзар"), entry("02.mp3", 2, "Кобзар"))
        )

        assertEquals(1, result.booksImported)
        assertEquals(2, result.filesImported)
        assertTrue(stagingDir().listFiles().isNullOrEmpty())
        assertEquals(2, libraryDir().listFiles()!!.size)
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
        assertEquals(1, libraryDir().listFiles()!!.size)
    }
}
