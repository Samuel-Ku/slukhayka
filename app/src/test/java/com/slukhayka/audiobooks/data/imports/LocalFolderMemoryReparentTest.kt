package com.slukhayka.audiobooks.data.imports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.CorrectionEntity
import com.slukhayka.audiobooks.data.sha256Hex
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The narration-claim merge (`LocalFolderMemory.reparent`) keeps physical
 * provenance of two owners under one book. It must keep the CURRENT hash when
 * both owners agreed on it — otherwise the next re-scan loses the cache hit and
 * reads the file again — and must drop it when they disagreed, because a guess
 * there would reuse a hash the bytes may no longer have (ADR-0061 §3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalFolderMemoryReparentTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)
    private val tree = "content://tree/reparent"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed(bookId: String, lastHash: String?, sizeBytes: Long?, lastModifiedMs: Long?) {
        val lineage = LocalFolderLineage(
            bookId = bookId,
            grouping = LocalFolderGrouping.SEPARATE_BOOKS,
            groups = setOf("folder:Кобзар"),
            files = mapOf(
                "Кобзар/01.mp3" to LocalFileRecord(
                    hashes = setOf(hashA, hashB),
                    lastHash = lastHash,
                    sizeBytes = sizeBytes,
                    lastModifiedMs = lastModifiedMs
                )
            )
        )
        dao.upsertCorrection(
            CorrectionEntity(
                mergeKey = "local-folder:${sha256Hex(tree.toByteArray(Charsets.UTF_8))}",
                kind = "FIELD",
                value = LocalFolderMemoryCodec.encode(lineage),
                origin = "USER_MADE"
            )
        )
    }

    @Test
    fun `reparent keeps the current hash when both owners agree`() = runBlocking {
        seed("from", hashA, 10L, 5L)
        seed("to", hashA, 10L, 5L)

        LocalFolderMemory(dao).reparent(fromBookId = "from", toBookId = "to")

        val survivor = LocalFolderMemory(dao).read(tree).single()
        assertEquals("to", survivor.bookId)
        assertEquals(hashA, survivor.files.getValue("Кобзар/01.mp3").lastHash)
        assertEquals(10L, survivor.files.getValue("Кобзар/01.mp3").sizeBytes)
    }

    @Test
    fun `reparent drops the current hash when owners disagree`() = runBlocking {
        seed("from", hashA, 10L, 5L)
        seed("to", hashB, 11L, 6L)

        LocalFolderMemory(dao).reparent(fromBookId = "from", toBookId = "to")

        val record = LocalFolderMemory(dao).read(tree).single().files.getValue("Кобзар/01.mp3")
        assertNull("розбіжність власників — не вгадуємо поточний хеш", record.lastHash)
        assertNull(record.sizeBytes)
        assertNull(record.lastModifiedMs)
        assertEquals(setOf(hashA, hashB), record.hashes)
    }
}
