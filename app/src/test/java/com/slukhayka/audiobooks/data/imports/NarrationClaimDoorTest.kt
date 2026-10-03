package com.slukhayka.audiobooks.data.imports

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.db.TombstoneEntity
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.metadata.MetadataAssertions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-0037 §4 (spec-49 T3) — the Narration Claim door on [LibraryImport]:
 * the listener's claim re-anchors the mapped sibling Edition's Sources onto
 * the current Edition (progress carries — ADR-0007), the found page's
 * narrator fills the current Edition with listener precedence (like
 * Metadata Override), and rejection is not a door at all — the sibling
 * stays untouched. The chapter-count agreement is never consulted: the
 * door reads narrators only (ADR-0014).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NarrationClaimDoorTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao
    private lateinit var libraryImport: LibraryImport

    private val mergeKey = MergeKey.keyFor("Лісова пісня", "Леся Українка")
    private val currentId = "sluhayua-42"
    private val siblingId = "soundbooks-7"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
        libraryImport = LibraryImport(dao, context, emptyList(), writeBatchRunner = { block -> db.withTransaction { block() } })
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // Seeding: the current card (4read-era, narrator unknown) and the
    // mapped sibling Edition (soundbooks, narrator named by its page).
    // ------------------------------------------------------------------

    private fun book(id: String, narrator: String, url: String) = AudiobookEntity(
        id = id,
        title = "Лісова пісня",
        author = "Леся Українка",
        narrator = narrator,
        description = "",
        coverDrawableRes = 0,
        sourceUrl = url,
        genre = ""
    )

    private fun seed(currentNarrator: String = "", siblingNarrator: String = "Степан Бандура") = runBlocking {
        listOf(
            book(currentId, currentNarrator, "https://sluhay.com.ua/42"),
            book(siblingId, siblingNarrator, "https://sound-books.net/lisova-pisnia")
        ).forEach { dao.insertAudiobooks(listOf(it)) }
        // One Work identity: the sibling is the mapped same-Work card.
        // The Work row first — the Library Entry's workId is an FK to it.
        dao.upsertWork(
            com.slukhayka.audiobooks.data.db.WorkEntity(
                id = mergeKey, mergeKey = mergeKey, title = "Лісова пісня", author = "Леся Українка", addedAt = 1L
            )
        )
        dao.upsertLibraryEntry(id = currentId, workId = mergeKey, isFavorite = false, createdAt = 1L, downloadProgress = 0f)
        dao.upsertLibraryEntry(id = siblingId, workId = mergeKey, isFavorite = false, createdAt = 2L, downloadProgress = 0f)
        val currentEdition = EditionEntity(
            id = "ed-current", workId = currentId, narrator = currentNarrator, totalChapters = 3, totalDurationSeconds = 180L
        )
        dao.insertEdition(currentEdition)
        dao.insertChapters(
            (0 until 3).map { index ->
                ChapterEntity(
                    id = MetadataAssertions.chapterId(currentId, index),
                    bookId = currentId,
                    editionId = currentEdition.id,
                    chapterIndex = index,
                    title = "Розділ ${index + 1}",
                    durationSeconds = 60L
                )
            }
        )
        // The current card's source carries progress on the current Edition.
        val currentSource = SourceEntity(
            id = "sluhayua-ed-current", bookId = currentId, editionId = currentEdition.id,
            type = "sluhayua", url = "https://sluhay.com.ua/42"
        )
        dao.insertSources(listOf(currentSource))
        dao.savePlaybackProgress(
            com.slukhayka.audiobooks.data.db.PlaybackProgressEntity(
                editionId = currentEdition.id, bookId = currentId,
                currentChapterIndex = 2, currentPositionSeconds = 100L
            )
        )
        // The sibling Edition: own narrator, own chapters, own Source+tracks.
        val siblingEdition = EditionEntity(
            id = "ed-sibling", workId = siblingId, narrator = siblingNarrator, totalChapters = 3, totalDurationSeconds = 200L
        )
        dao.insertEdition(siblingEdition)
        dao.insertChapters(
            (0 until 3).map { index ->
                ChapterEntity(
                    id = MetadataAssertions.chapterId(siblingId, index),
                    bookId = siblingId,
                    editionId = siblingEdition.id,
                    chapterIndex = index,
                    title = "Частина ${index + 1}",
                    durationSeconds = 66L
                )
            }
        )
        val siblingSource = SourceEntity(
            id = "soundbooks-ed-sibling", bookId = siblingId, editionId = siblingEdition.id,
            type = "soundbooks", url = "https://sound-books.net/lisova-pisnia"
        )
        dao.insertSources(listOf(siblingSource))
        dao.insertTracks(
            (0 until 3).map { index ->
                SourceTrackEntity(
                    id = MetadataAssertions.trackId(siblingSource.id, index),
                    sourceId = siblingSource.id,
                    trackIndex = index,
                    url = "https://audio.sound-books.net/track${index + 1}.mp3"
                )
            }
        )
        dao.upsertWorkSource(
            WorkSourceEntity(
                id = "$mergeKey|soundbooks|claim", workId = mergeKey,
                sourceId = "soundbooks", sourceUrl = "https://sound-books.net/lisova-pisnia"
            )
        )
    }

    @Test
    fun `claim keeps local folder rescan attached to surviving narration`() = runBlocking {
        seed()
        val tree = "content://tree/narration-local"
        fun file(number: Int) = LocalAudioEntry("0$number.mp3", null) {
            java.io.ByteArrayInputStream(ByteArray(16) { number.toByte() })
        }
        val files = (1..3).map(::file)
        var plan = ImportPlanner.buildPlan(SourceRef.Folder(tree, "Лісова пісня", LocalFolderGrouping.ONE_BOOK), files)
        plan = ImportPlanner.editBook(plan, plan.books.single().id, author = "Леся Українка", narrator = "Локальна начитка")
        assertEquals(1, libraryImport.applyImportPlan(plan).booksImported)
        val local = dao.getAudiobooksBySourceTree(tree).single()
        val chapters = dao.getChaptersListForBook(currentId)
        assertNotNull(libraryImport.claimSameNarration(currentId, local.id, "Локальна начитка"))
        assertNull(dao.getAudiobookById(local.id))
        val progress = dao.getPlaybackProgressSync(currentId)
        val recreated = LibraryImport(dao, context, emptyList(), writeBatchRunner = { block -> db.withTransaction { block() } })
        val unchanged = recreated.rescanAudioEntries(files, tree)
        assertEquals(0, unchanged.newBooks)
        assertEquals(0, unchanged.missingFiles)
        assertEquals(0, unchanged.movedFiles)
        val expanded = recreated.rescanAudioEntries(files + file(4), tree)
        assertTrue("the surviving mixed-source Edition must receive an explicit structural rejection", expanded.structuralChangeRejected)
        assertEquals(0, expanded.newBooks)
        assertEquals(0, expanded.newChapters)
        assertEquals(chapters.map { it.id }, dao.getChaptersListForBook(currentId).map { it.id })
        assertEquals(progress, dao.getPlaybackProgressSync(currentId))
    }

    @Test
    fun `claim re-anchors the sibling source and fills the narrator`() = runBlocking {
        seed()
        val merged = libraryImport.claimSameNarration(currentId, siblingId, "Степан Бандура")

        assertNotNull(merged)
        assertEquals("Степан Бандура", merged!!.narrator)

        // The sibling's Source now plays the CURRENT narration's Edition —
        // which the narrator rename re-derives deterministically (ADR-0010:
        // the Edition id carries the narrator; the hash of the new identity).
        val newEditionId = com.slukhayka.audiobooks.data.EditionId.forBook(
            mergeKey, currentId, "Степан Бандура", ""
        )
        val sources = dao.getSourcesForBookSync(currentId)
        assertEquals(2, sources.size)
        val reAnchored = sources.first { it.type == "soundbooks" }
        assertEquals(newEditionId, reAnchored.editionId)
        assertEquals(currentId, reAnchored.bookId)
        // Its physical tracks moved with it, ids re-derived deterministically.
        val tracks = dao.getTracksForSourceSync(reAnchored.id)
        assertEquals(3, tracks.size)
        assertEquals("https://audio.sound-books.net/track1.mp3", tracks.first().url)
        // The current Edition's chapters re-parented onto the renamed id.
        assertTrue(dao.getChaptersListForBook(currentId).all { it.editionId == newEditionId })

        // The merged card plays through the current Edition's chapters.
        assertTrue(dao.getChaptersListForBook(currentId).isNotEmpty())

        // The browse claim hangs off the shared Work row — untouched.
        val claims = dao.getWorkSourcesForWorkSync(mergeKey)
        assertTrue(claims.any { it.sourceId == "soundbooks" })
    }

    @Test
    fun `claim pairs original audio after both renditions were manually reordered`() = runBlocking {
        seed()
        val current = dao.getChaptersListForBook(currentId).map { it.id }
        val sibling = dao.getChaptersListForBook(siblingId).map { it.id }
        assertEquals(ChapterReorderResult.APPLIED, libraryImport.reorderChapters(currentId, current, current.reversed()))
        assertEquals(ChapterReorderResult.APPLIED, libraryImport.reorderChapters(siblingId, sibling, listOf(sibling[1], sibling[2], sibling[0])))
        assertNotNull(libraryImport.claimSameNarration(currentId, siblingId, "Степан Бандура"))
        val source = dao.getSourcesForBookSync(currentId).first { it.type == "soundbooks" }
        assertEquals(listOf("track3.mp3", "track2.mp3", "track1.mp3"), dao.getTracksForSourceSync(source.id).map { it.url.substringAfterLast('/') })
        assertEquals(current.reversed(), dao.getChaptersListForBook(currentId).map { it.id })
        assertEquals(2, dao.getPlaybackProgressSyncByEdition(source.editionId!!)!!.currentChapterIndex)
        assertEquals(ChapterReorderResult.APPLIED, libraryImport.reorderChapters(currentId, current.reversed(), current))
        assertEquals(listOf("track1.mp3", "track2.mp3", "track3.mp3"), dao.getTracksForSourceSync(source.id).map { it.url.substringAfterLast('/') })
    }

    @Test
    fun `a sibling reorder cannot interleave the claim snapshot and its tracks`() = runBlocking {
        seed()
        val ids = dao.getChaptersListForBook(siblingId).map { it.id }
        val snapshotRead = CompletableDeferred<Unit>()
        val reorderAttempted = CompletableDeferred<Unit>()
        val reorderFinished = CompletableDeferred<Unit>()
        val claimDao = object : AudiobookDao by dao {
            override suspend fun getTracksForBookSync(bookId: String): List<SourceTrackEntity> {
                if (bookId == siblingId) {
                    snapshotRead.complete(Unit)
                    reorderAttempted.await()
                    // A broken, non-transactional claim lets reorder finish here;
                    // the correct Room batch keeps it waiting until the claim ends.
                    withTimeoutOrNull(500L) { reorderFinished.await() }
                }
                return dao.getTracksForBookSync(bookId)
            }
        }
        val otherDao = object : AudiobookDao by dao {
            override suspend fun reorderChapterPairs(bookId: String, expectedIds: List<String>, chapterIds: List<String>): ChapterReorderResult {
                reorderAttempted.complete(Unit)
                return dao.reorderChapterPairs(bookId, expectedIds, chapterIds)
            }
        }
        val claimImport = LibraryImport(claimDao, context, emptyList(), writeBatchRunner = { block -> db.withTransaction { block() } })
        val otherImport = LibraryImport(otherDao, context, emptyList())
        val claim = async(Dispatchers.IO) { claimImport.claimSameNarration(currentId, siblingId, "Степан Бандура") }
        snapshotRead.await()
        val reorder = async(Dispatchers.IO) {
            try { otherImport.reorderChapters(siblingId, ids, ids.reversed()) }
            finally { reorderFinished.complete(Unit) }
        }
        assertNotNull(claim.await())
        reorder.await()
        val source = dao.getSourcesForBookSync(currentId).first { it.type == "soundbooks" }
        assertEquals(listOf("track1.mp3", "track2.mp3", "track3.mp3"), dao.getTracksForSourceSync(source.id).map { it.url.substringAfterLast('/') })
    }

    @Test
    fun `claim empties and removes the sibling card`() = runBlocking {
        seed()
        libraryImport.claimSameNarration(currentId, siblingId, "Степан Бандура")

        assertNull(dao.getAudiobookById(siblingId))
        assertTrue(dao.getSourcesForBookSync(siblingId).isEmpty())
        assertTrue(dao.getChaptersListForBook(siblingId).isEmpty())
        // The tombstone keeps the dead row identity hidden from refreshes.
        assertTrue(dao.isBookTombstoned(siblingId))
    }

    @Test
    fun `progress on the current edition survives the claim`() = runBlocking {
        seed()
        libraryImport.claimSameNarration(currentId, siblingId, "Степан Бандура")

        // The Listening State row moved with the renamed Edition id.
        val newEditionId = com.slukhayka.audiobooks.data.EditionId.forBook(
            mergeKey, currentId, "Степан Бандура", ""
        )
        val progress = dao.getPlaybackProgressSyncByEdition(newEditionId)
        assertNotNull(progress)
        assertEquals(2, progress!!.currentChapterIndex)
        assertEquals(100L, progress.currentPositionSeconds)
    }

    @Test
    fun `rejected claim is the absence of the door - sibling untouched`() = runBlocking {
        seed()
        // No door call at all — the sibling stays a separate narration.
        val sources = dao.getSourcesForBookSync(siblingId)
        assertEquals(1, sources.size)
        assertEquals("ed-sibling", sources.first().editionId)
        assertNotNull(dao.getAudiobookById(siblingId))
        // The current Edition's narrator is exactly what the import stored.
        assertEquals("", dao.getEditionForWork(currentId)!!.narrator)
    }

    @Test
    fun `blank found claim writes nothing - sibling stays`() = runBlocking {
        seed()
        val merged = libraryImport.claimSameNarration(currentId, siblingId, "  ")

        assertNotNull(merged)
        // The narrator keeps the stored (blank) value — nothing is invented.
        assertEquals("", dao.getEditionForWork(currentId)!!.narrator)
        // But the claim is still a claim: the sibling re-anchored and left.
        assertTrue(dao.getSourcesForBookSync(currentId).any { it.type == "soundbooks" })
        assertNull(dao.getAudiobookById(siblingId))
    }

    @Test
    fun `self-claim and unknown ids are refused`() = runBlocking {
        seed()
        assertNull(libraryImport.claimSameNarration(currentId, currentId, "Хтось"))
        assertNull(libraryImport.claimSameNarration(currentId, "неіснуюча-книга", "Хтось"))
        assertNull(libraryImport.claimSameNarration("немає-такої", siblingId, "Хтось"))
        // Nothing changed.
        assertNotNull(dao.getAudiobookById(siblingId))
        assertEquals(1, dao.getSourcesForBookSync(currentId).size)
    }

    @Test
    fun `tombstoned sibling never re-imports as a duplicate narration`() = runBlocking {
        seed()
        libraryImport.claimSameNarration(currentId, siblingId, "Степан Бандура")
        dao.insertTombstone(TombstoneEntity(bookId = siblingId, deletedAt = 5L))

        // The same source page re-importing later would merge onto the
        // surviving card: the tombstone hides the old row identity.
        assertTrue(dao.getSourcesForBookSync(currentId).any { it.type == "soundbooks" })
        assertFalse(dao.getChaptersListForBook(siblingId).isNotEmpty())
    }
}
