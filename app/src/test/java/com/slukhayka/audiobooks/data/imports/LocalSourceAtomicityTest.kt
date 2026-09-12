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
