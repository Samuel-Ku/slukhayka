package com.slukhayka.audiobooks.data.imports

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.contentHashOf
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
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
 * #1185 T4 / ADR-0061 §2 — the one-time copy migration: an older version's
 * private copies move onto the original SAF documents where the grant is alive
 * and the bytes still match, and only then are the files deleted. A dead
 * grant, an unmatched stream or a single-file import keeps its copy — the copy
 * is the only audio there — and every boundary is counted in the report.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalCopyMigrationTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao
    private lateinit var copyRoot: File

    private val tree = "content://tree/books"
    private val hash = contentHashOf(ByteArray(16) { 1 }.inputStream())
    private val otherHash = contentHashOf(ByteArray(16) { 2 }.inputStream())

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
        copyRoot = File(context.filesDir, LibraryImport.LOCAL_AUDIO_DIR)
        copyRoot.deleteRecursively()
        copyRoot.mkdirs()
    }

    @After
    fun tearDown() {
        db.close()
        copyRoot.deleteRecursively()
    }

    private fun migration(
        scan: suspend (String) -> List<LocalAudioEntry>,
        runner: (suspend (suspend () -> Unit) -> Unit)? = null
    ) = LocalCopyMigration(
        dao = dao,
        copyRoot = copyRoot,
        scanTree = scan,
        writeBatchRunner = runner ?: { block -> db.withTransaction { block() } }
    )

    private fun copy(name: String, bytes: Int = 16): File =
        File(copyRoot, name).apply { writeBytes(ByteArray(bytes) { 1 }) }

    private suspend fun seedBook(bookId: String, sourceTreeUri: String?, tracks: List<Pair<String, File>>) {
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = bookId,
                    title = "Книга $bookId",
                    author = "Автор",
                    narrator = "",
                    description = "",
                    coverDrawableRes = 0,
                    sourceUrl = "",
                    genre = "",
                    sourceTreeUri = sourceTreeUri
                )
            )
        )
        val sourceId = "local-$bookId"
        dao.insertSources(
            listOf(SourceEntity(id = sourceId, bookId = bookId, editionId = null, type = "local", url = ""))
        )
        dao.insertTracks(
            tracks.mapIndexed { index, (contentHash, file) ->
                SourceTrackEntity(
                    id = "$sourceId-tr$index",
                    sourceId = sourceId,
                    trackIndex = index,
                    url = file.absolutePath,
                    localFilePath = file.absolutePath,
                    contentHash = contentHash,
                    isDownloaded = true
                )
            }
        )
    }

    private suspend fun seedMemory(bookId: String, files: List<FolderRescan.RescanFile>) {
        LocalFolderMemory(dao).remember(
            bookId,
            LocalFolderOrigin(tree, LocalFolderGrouping.SEPARATE_BOOKS, files)
        )
    }

    private fun entry(
        name: String,
        contentHash: String,
        size: Long = 16L,
        modified: Long = 100L,
        onRead: () -> Unit = {}
    ) = LocalAudioEntry(
        fileName = name,
        parentFolder = "Кобзар",
        uri = "$tree/$name",
        sizeBytes = size,
        lastModifiedMs = modified,
        openStream = {
            onRead()
            ByteArrayInputStream(ByteArray(16) { 1 })
        }
    )

    private suspend fun trackOf(bookId: String): SourceTrackEntity =
        dao.getTracksForSourceSync("local-$bookId").single()

    @Test
    fun `migrates a copied tree import from remembered metadata and frees the copy`() = runBlocking {
        val file = copy("01.mp3")
        seedBook("b1", tree, listOf(hash to file))
        seedMemory("b1", listOf(FolderRescan.RescanFile("01.mp3", "Кобзар", hash, 16L, 100L)))
        var read = 0

        val report = migration {
            listOf(entry("01.mp3", hash, onRead = { read++ }))
        }.runOnce()

        assertEquals(1, report.relinkedTracks)
        assertEquals(1, report.booksMigrated)
        assertEquals(16L, report.freedBytes)
        assertEquals(0, report.keptTracks)
        assertEquals("T3-пам'ять дала адресу без читання потоку", 0, read)
        val track = trackOf("b1")
        assertEquals("$tree/01.mp3", track.url)
        assertNull(track.localFilePath)
        assertTrue("копія лишається доступною офлайн", track.isDownloaded)
        assertFalse("файл звільнено", file.exists())
    }

    @Test
    fun `reads the stream only when the memory has no answer`() = runBlocking {
        val file = copy("01.mp3")

        seedBook("b1", tree, listOf(hash to file))
        var read = 0

        val report = migration {
            listOf(entry("01.mp3", hash, onRead = { read++ }))
        }.runOnce()

        assertEquals(1, report.relinkedTracks)
        assertEquals(1, read)
        assertEquals("$tree/01.mp3", trackOf("b1").url)
        assertFalse(file.exists())
    }

    @Test
    fun `a dead grant keeps every copy and reports the tree unavailable`() = runBlocking {
        val file = copy("01.mp3")
        seedBook("b1", tree, listOf(hash to file))

        val report = migration { throw SecurityException("grant revoked") }.runOnce()

        assertEquals(0, report.relinkedTracks)
        assertEquals(1, report.keptTracks)
        assertEquals(1, report.unavailableTrees)
        assertTrue("копія — єдине аудіо книги", file.exists())
        val track = trackOf("b1")
        assertEquals(file.absolutePath, track.localFilePath)
        assertFalse(report.changed)
    }

    @Test
    fun `bytes that no longer match keep the copy`() = runBlocking {
        val file = copy("01.mp3")
        seedBook("b1", tree, listOf(otherHash to file))

        val report = migration {
            listOf(entry("01.mp3", hash)) // the tree has different bytes now
        }.runOnce()

        assertEquals(0, report.relinkedTracks)
        assertEquals(1, report.keptTracks)
        assertTrue(file.exists())
        assertEquals(file.absolutePath, trackOf("b1").localFilePath)
    }

    @Test
    fun `a single-file import has no address to migrate to`() = runBlocking {
        val file = copy("solo.mp3")
        seedBook("b1", null, listOf(hash to file))

        val report = migration { error("a single-file book has no tree to scan") }.runOnce()

        assertEquals(0, report.relinkedTracks)
        assertEquals(1, report.keptTracks)
        assertTrue(file.exists())
    }

    @Test
    fun `a foreign path is never relinked or deleted`() = runBlocking {
        val foreign = File(context.cacheDir, "foreign.mp3").apply { writeBytes(ByteArray(16)) }
        seedBook("b1", tree, listOf(hash to foreign))
        seedMemory("b1", listOf(FolderRescan.RescanFile("01.mp3", "Кобзар", hash, 16L, 100L)))

        val report = migration { listOf(entry("01.mp3", hash)) }.runOnce()

        assertEquals("чужий шлях не наша копія — не чіпаємо", 0, report.relinkedTracks)
        assertEquals(1, report.keptTracks)
        assertTrue(foreign.exists())
    }

    @Test
    fun `a failed transaction keeps the rows and the files`() = runBlocking {
        val file = copy("01.mp3")
        seedBook("b1", tree, listOf(hash to file))
        seedMemory("b1", listOf(FolderRescan.RescanFile("01.mp3", "Кобзар", hash, 16L, 100L)))

        val report = migration(
            scan = { listOf(entry("01.mp3", hash)) },
            runner = { block -> db.withTransaction { block(); throw IllegalStateException("injected") } }
        ).runOnce()

        assertEquals(0, report.relinkedTracks)
        assertEquals(1, report.keptTracks)
        assertTrue(file.exists())
        assertEquals(file.absolutePath, trackOf("b1").localFilePath)
    }

    @Test
    fun `the pass runs once per device`() = runBlocking {
        val file = copy("01.mp3")
        seedBook("b1", tree, listOf(hash to file))
        seedMemory("b1", listOf(FolderRescan.RescanFile("01.mp3", "Кобзар", hash, 16L, 100L)))
        val migration = migration { listOf(entry("01.mp3", hash)) }

        assertEquals(1, migration.runOnce().relinkedTracks)
        val second = migration.runOnce()

        assertTrue("другий запуск — не міграція", second.alreadyRan)
        assertEquals(0, second.relinkedTracks)
        assertFalse("маркер у пам'яті корекцій", dao.getCorrectionsForMergeKey("local-copy-migration").isEmpty())
    }
}
