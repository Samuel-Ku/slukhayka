package com.slukhayka.audiobooks.data.imports

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.db.PlaybackProgressEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.db.TombstoneEntity
import kotlinx.coroutines.flow.first
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

/**
 * #612 Local Source T1 — a re-scan of a local folder mutates ONLY the exact
 * local Source of the matched Edition: a mixed-Source Edition's direct tracks,
 * Chapter ids/indices and Listening State survive untouched; a structural
 * change without a proven mapping is rejected explicitly with zero Room
 * writes; a local-only Edition appends new Chapters at the end; a tombstoned
 * book is never resurrected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalSourceRescanGuardTest {

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
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun imports() = LibraryImport(dao, context, emptyList())

    /** #1052 — loose files at the TREE ROOT: no parent folder. */
    private fun rootEntry(name: String, byte: Int) =
        LocalAudioEntry(name, null) { ByteArrayInputStream(ByteArray(16) { byte.toByte() }) }

    private fun entry(name: String, byte: Int) =
        LocalAudioEntry(name, "Кобзар") { ByteArrayInputStream(ByteArray(16) { byte.toByte() }) }

    private suspend fun importTwoChapters(): String {
        imports().importAudioEntries(
            listOf(entry("01.mp3", 1), entry("02.mp3", 2)),
            sourceTreeUri = "content://tree/books"
        )
        return dao.getAllAudiobooks().first().first { it.title == "Кобзар" }.id
    }

    @Test
    fun `duplicate only explicit merge remembers the removed owners folder before any rescan`() = runBlocking {
        val targetId = importTwoChapters()
        val tree = "content://tree/duplicate-consent"
        val files = listOf(rootEntry("01.mp3", 1), rootEntry("02.mp3", 2))
        val plan = ImportPlanner.buildPlan(
            SourceRef.Folder(tree, "Кобзар", LocalFolderGrouping.ONE_BOOK), files,
            existingWorks = listOf(ImportPlanner.ExistingWork(targetId, "Кобзар", ""))
        )
        val confirmed = ImportPlanner.acceptMerge(plan, "root-folder")
        assertEquals(targetId, confirmed.books.single().mergedIntoBookId)
        val result = LibraryImport(dao, context, emptyList(), writeBatchRunner = { block -> db.withTransaction { block() } }).applyImportPlan(confirmed)
        assertEquals(0, result.filesImported)
        assertEquals(2, result.duplicateFiles)
        assertEquals(LocalFolderGrouping.ONE_BOOK, ImportGrantStore(context).folder(tree).grouping)
        com.slukhayka.audiobooks.data.entries.LibraryEntries(dao, emptyList()).deleteBook(targetId)
        val rescan = imports().rescanAudioEntries(files, tree)
        assertEquals("confirmed removed bytes must not come back", 0, rescan.newBooks)
        assertEquals(0, rescan.newChapters)
        assertTrue(dao.getAllAudiobooksOnce().isEmpty())
    }

    @Test
    fun `rescan keeps planned merge order and split ownership while refusing an ambiguous new chapter`() = runBlocking {
        val tree = "content://tree/manual-corrections"
        fun file(name: String, folder: String?, byte: Int) = LocalAudioEntry(name, folder) { ByteArrayInputStream(ByteArray(16) { byte.toByte() }) }
        val initial = listOf(file("01.mp3", "A", 1), file("02.mp3", "A", 2), file("03.mp3", "A", 3),
            file("01.mp3", "B", 5), file("00.mp3", null, 6))
        var plan = ImportPlanner.buildPlan(SourceRef.Folder(tree, "Оповідання"), initial)
        plan = ImportPlanner.splitBook(plan, "folder:A", 2)
        plan = ImportPlanner.editBook(plan, "folder:A", title = "Початок")
        plan = ImportPlanner.editBook(plan, "folder:A#2", title = "Кінець")
        plan = ImportPlanner.mergePlannedBooks(plan, "folder:B", "root:00.mp3")
        plan = ImportPlanner.editBook(plan, "root:00.mp3", title = "Збірка", author = "Мій автор")
        plan = ImportPlanner.reorderChapters(plan, "root:00.mp3", listOf(1, 0))
        val imported = imports().applyImportPlan(plan)
        assertEquals(3, imported.booksImported)
        assertEquals(5, imported.filesImported)
        val books = dao.getAllAudiobooksOnce()
        val merged = books.single { it.title == "Збірка" }
        val snapshots = books.associate { it.id to dao.getChapterPlaybackSnapshot(it.id) }
        com.slukhayka.audiobooks.data.listening.ListeningStateStore(dao).updateProgress(merged.id, 0, 19L)
        val listening = dao.getPlaybackProgressSync(merged.id)
        val unchanged = imports().rescanAudioEntries(initial, tree)
        assertEquals(0, unchanged.newBooks)
        assertEquals(0, unchanged.newChapters)
        assertEquals(0, unchanged.missingFiles)
        assertFalse(unchanged.structuralChangeRejected)
        snapshots.forEach { (id, snapshot) -> assertEquals(snapshot, dao.getChapterPlaybackSnapshot(id)) }
        val newMergedFile = file("02.mp3", "B", 7)
        val appended = imports().rescanAudioEntries(initial + newMergedFile, tree)
        assertEquals(1, appended.newChapters)
        assertEquals(0, appended.newBooks)
        assertFalse(appended.structuralChangeRejected)
        assertEquals(snapshots.getValue(merged.id).chapters, dao.getChaptersListForBook(merged.id).take(2))
        assertEquals(listOf("01", "00", "02"), dao.getChaptersListForBook(merged.id).map { it.title })
        val beforeAmbiguous = books.associate { it.id to dao.getChapterPlaybackSnapshot(it.id) }
        val ambiguous = imports().rescanAudioEntries(initial + newMergedFile + file("04.mp3", "A", 4), tree)
        assertTrue("two live split owners must not silently claim a new file", ambiguous.structuralChangeRejected)
        assertEquals(0, ambiguous.newChapters)
        assertEquals(0, ambiguous.newBooks)
        assertEquals(0, ambiguous.missingFiles)
        beforeAmbiguous.forEach { (id, snapshot) -> assertEquals(snapshot, dao.getChapterPlaybackSnapshot(id)) }
        assertEquals(listening, dao.getPlaybackProgressSync(merged.id))
        assertEquals(setOf("Початок", "Кінець", "Збірка"), dao.getAllAudiobooksOnce().map { it.title }.toSet())
        assertEquals("Мій автор", dao.getAudiobookById(merged.id)!!.author)
    }

    @Test
    fun `explicit reimport owns new chapters and replacement paths ahead of removed folder memory`() = runBlocking {
        val tree = "content://tree/reimport-owner"
        fun root(name: String, byte: Int) = LocalAudioEntry(name, null) { ByteArrayInputStream(ByteArray(16) { byte.toByte() }) }
        val original = root("01.mp3", 1)
        val plan = ImportPlanner.buildPlan(SourceRef.Folder(tree, "Кобзар", LocalFolderGrouping.ONE_BOOK), listOf(original))
        assertEquals(1, imports().applyImportPlan(plan).booksImported)
        val removed = dao.getAllAudiobooksOnce().single()
        com.slukhayka.audiobooks.data.entries.LibraryEntries(dao, emptyList()).deleteBook(removed.id)
        assertEquals(1, imports().applyImportPlan(plan).booksImported)
        val live = dao.getAllAudiobooksOnce().single()
        assertTrue(removed.id != live.id)
        val before = dao.getChaptersListForBook(live.id)
        com.slukhayka.audiobooks.data.listening.ListeningStateStore(dao).updateProgress(live.id, 0, 23L)
        val listening = dao.getPlaybackProgressSync(live.id)
        val next = root("02.mp3", 2)
        val appended = imports().rescanAudioEntries(listOf(original, next), tree)
        assertEquals("a live explicit import owns new files in the saved group", 1, appended.newChapters)
        assertEquals(0, appended.newBooks)
        assertEquals(0, appended.missingFiles)
        assertFalse(appended.structuralChangeRejected)
        val afterAppend = dao.getChaptersListForBook(live.id)
        assertEquals(before.map { it.id }, afterAppend.take(1).map { it.id })
        val replaced = imports().rescanAudioEntries(listOf(root("01.mp3", 3), next), tree)
        assertEquals("a live explicit import also owns a previously claimed path", 1, replaced.newChapters)
        assertEquals(0, replaced.newBooks)
        assertEquals(1, replaced.missingFiles)
        assertFalse(replaced.structuralChangeRejected)
        assertEquals(afterAppend.map { it.id }, dao.getChaptersListForBook(live.id).take(2).map { it.id })
        assertEquals(listening, dao.getPlaybackProgressSync(live.id))
        assertEquals(listOf(live.id), dao.getAllAudiobooksOnce().map { it.id })
        assertTrue(dao.isBookTombstoned(removed.id))
    }

    @Test
    fun `renaming a removed books files never restores it without explicit import`() = runBlocking {
        val tree = "content://tree/removed-rename"
        val original = entry("01.mp3", 1)
        assertEquals(1, imports().applyImportPlan(ImportPlanner.buildPlan(SourceRef.Folder(tree), listOf(original))).booksImported)
        val removed = dao.getAllAudiobooksOnce().single()
        com.slukhayka.audiobooks.data.entries.LibraryEntries(dao, emptyList()).deleteBook(removed.id)
        assertTrue(dao.getAllAudiobooksOnce().isEmpty())
        val renamed = original.copy(fileName = "Перейменований.mp3", parentFolder = "Нова тека")
        val unrelated = entry("01.mp3", 3).copy(parentFolder = "Інша")
        val scanned = imports().rescanAudioEntries(listOf(renamed, unrelated), tree)
        assertEquals("only an unrelated new folder may create a book", 1, scanned.newBooks)
        assertEquals(1, scanned.newChapters)
        assertEquals(listOf("Інша"), dao.getAllAudiobooksOnce().map { it.title })
        assertTrue(dao.isBookTombstoned(removed.id))

        // A later explicit import is personal intent, unlike an automatic
        // rescan. Its live Source wins over the removed owner's memory.
        assertEquals(1, imports().applyImportPlan(ImportPlanner.buildPlan(SourceRef.Folder(tree), listOf(renamed))).booksImported)
        val explicit = dao.getAllAudiobooksOnce().single { it.title == "Нова тека" }
        val chapters = dao.getChaptersListForBook(explicit.id)
        val nextScan = imports().rescanAudioEntries(listOf(renamed, unrelated), tree)
        assertEquals(0, nextScan.newBooks)
        assertEquals(0, nextScan.newChapters)
        assertEquals(0, nextScan.missingFiles)
        assertFalse(nextScan.structuralChangeRejected)
        assertEquals(chapters, dao.getChaptersListForBook(explicit.id))
    }

    @Test
    fun `confirmed preview installs all metadata including narrator series and volume`() = runBlocking {
        val plan = ImportPlanner.buildPlan(SourceRef.Folder("content://tree/books"), listOf(entry("01.mp3", 1), entry("02.mp3", 2)))
        val edited = ImportPlanner.editBook(plan, plan.books.single().id, title = "Поезії", author = "Тарас Шевченко", narrator = "Диктор", seriesTitle = "Збірки", seriesIndex = 2)
        assertTrue(dao.getAllAudiobooksOnce().isEmpty())
        assertEquals(1, imports().applyImportPlan(edited, "content://tree/books").booksImported)
        val book = dao.getAllAudiobooksOnce().single()
        assertEquals("Поезії", book.title)
        assertEquals("Тарас Шевченко", book.author)
        assertEquals("Диктор", book.narrator)
        assertEquals("Збірки", book.seriesTitle)
        assertEquals(2, book.seriesIndex)
        assertEquals("Диктор", dao.getEditionForWork(book.id)!!.narrator)
    }

    @Test
    fun `one root book keeps confirmed title and receives new files after recreating importer`() = runBlocking {
        val tree = "content://tree/root-chapters"
        fun root(name: String, byte: Int) = LocalAudioEntry(name, null) { ByteArrayInputStream(ByteArray(16) { byte.toByte() }) }
        val initial = listOf(root("03.mp3", 3), root("01.mp3", 1), root("02.mp3", 2))
        var plan = ImportPlanner.buildPlan(SourceRef.Folder(tree, "Кобзар", LocalFolderGrouping.ONE_BOOK), initial)
        plan = ImportPlanner.editBook(plan, plan.books.single().id, title = "Поезії", author = "Тарас Шевченко")
        val imported = imports().applyImportPlan(plan, tree)
        assertEquals(1, imported.booksImported)
        val book = dao.getAllAudiobooksOnce().single()
        val before = dao.getChaptersListForBook(book.id)
        assertEquals(listOf("01", "02", "03"), before.map { it.title })
        com.slukhayka.audiobooks.data.listening.ListeningStateStore(dao).updateProgress(book.id, 1, 42L)
        val listening = dao.getPlaybackProgressSync(book.id)
        val report = imports().rescanAudioEntries(initial + root("00.mp3", 4), tree)
        assertEquals(0, report.newBooks)
        assertEquals(1, report.newChapters)
        assertEquals(0, report.missingFiles)
        assertFalse(report.structuralChangeRejected)
        assertEquals(before.map { it.id }, dao.getChaptersListForBook(book.id).take(3).map { it.id })
        assertEquals(listening, dao.getPlaybackProgressSync(book.id))
        assertEquals("Поезії", dao.getAudiobookById(book.id)!!.title)
        assertEquals("Тарас Шевченко", dao.getAudiobookById(book.id)!!.author)
    }

    @Test
    fun `one book accepts two folders and rescans each physical tree independently`() = runBlocking {
        fun root(name: String, byte: Int) = LocalAudioEntry(name, null) { ByteArrayInputStream(ByteArray(16) { byte.toByte() }) }
        val firstTree = "content://tree/first"
        val secondTree = "content://tree/second"
        val firstFile = root("01.mp3", 1)
        val secondFile = root("02.mp3", 2)
        val first = ImportPlanner.buildPlan(SourceRef.Folder(firstTree, "Кобзар", LocalFolderGrouping.ONE_BOOK), listOf(firstFile))
        assertEquals(1, imports().applyImportPlan(first).booksImported)
        val book = dao.getAllAudiobooksOnce().single()
        var second = ImportPlanner.buildPlan(SourceRef.Folder(secondTree, "Кобзар", LocalFolderGrouping.ONE_BOOK), listOf(secondFile),
            listOf(ImportPlanner.ExistingWork(book.id, book.title, book.mergeKey.orEmpty())))
        second = ImportPlanner.acceptMerge(second, second.books.single().id)
        assertEquals(book.id, second.books.single().mergedIntoBookId)
        val joined = imports().applyImportPlan(second)
        assertEquals(0, joined.booksImported)
        assertEquals(1, joined.filesImported)
        assertEquals(2, dao.getChaptersListForBook(book.id).size)
        val fromFirst = imports().rescanAudioEntries(listOf(firstFile), firstTree)
        assertEquals(0, fromFirst.missingFiles)
        assertEquals(0, fromFirst.newChapters)
        val fromSecond = imports().rescanAudioEntries(listOf(secondFile), secondTree)
        assertEquals(0, fromSecond.missingFiles)
        assertEquals(0, fromSecond.newBooks)
        assertEquals(setOf(firstTree, secondTree), imports().rescanAllLocalFolders().map { it.treeUri }.toSet())
        assertEquals(2, dao.getChaptersListForBook(book.id).size)
    }

    @Test
    fun `a proven folder rename is remembered even when no new chapter was added`() = runBlocking {
        val tree = "content://tree/renamed-folder"
        fun file(name: String, folder: String, byte: Int) = LocalAudioEntry(name, folder) {
            ByteArrayInputStream(ByteArray(16) { byte.toByte() })
        }
        val initial = listOf(file("01.mp3", "Оригінальна", 1), file("02.mp3", "Оригінальна", 2))
        assertEquals(1, imports().applyImportPlan(ImportPlanner.buildPlan(SourceRef.Folder(tree), initial)).booksImported)
        val book = dao.getAllAudiobooksOnce().single()
        val chapters = dao.getChaptersListForBook(book.id)
        com.slukhayka.audiobooks.data.listening.ListeningStateStore(dao).updateProgress(book.id, 1, 17L)
        val progress = dao.getPlaybackProgressSync(book.id)
        val renamed = listOf(file("01.mp3", "Нова тека", 1), file("02.mp3", "Нова тека", 2))
        val firstScan = imports().rescanAudioEntries(renamed, tree)
        assertEquals(0, firstScan.newChapters)
        assertEquals(0, firstScan.newBooks)
        assertEquals(2, firstScan.movedFiles)
        // The old files disappear later; the already-proven folder keeps
        // its owner after the importer is recreated.
        val laterScan = imports().rescanAudioEntries(listOf(file("03.mp3", "Нова тека", 3)), tree)
        assertEquals(0, laterScan.newBooks)
        assertEquals(1, laterScan.newChapters)
        assertEquals(2, laterScan.missingFiles)
        assertEquals(chapters.map { it.id }, dao.getChaptersListForBook(book.id).take(2).map { it.id })
        assertEquals(progress, dao.getPlaybackProgressSync(book.id))
        assertEquals("Оригінальна", dao.getAudiobookById(book.id)!!.title)
    }

    @Test
    fun `moving a known file into another books folder never steals that folders new chapters`() = runBlocking {
        val tree = "content://tree/foreign-file"
        fun file(name: String, folder: String, byte: Int) = LocalAudioEntry(name, folder) {
            ByteArrayInputStream(ByteArray(16) { byte.toByte() })
        }
        val initial = listOf(file("01.mp3", "Перша", 1), file("02.mp3", "Перша", 2), file("01.mp3", "Друга", 3))
        assertEquals(2, imports().applyImportPlan(ImportPlanner.buildPlan(SourceRef.Folder(tree), initial)).booksImported)
        val first = dao.getAllAudiobooksOnce().single { it.title == "Перша" }
        val second = dao.getAllAudiobooksOnce().single { it.title == "Друга" }
        val firstIds = dao.getChaptersListForBook(first.id).map { it.id }
        val secondIds = dao.getChaptersListForBook(second.id).map { it.id }
        val moved = listOf(file("02.mp3", "Перша", 2), file("Чужий.mp3", "Друга", 1), file("01.mp3", "Друга", 3))
        val scan = imports().rescanAudioEntries(moved + file("02.mp3", "Друга", 4), tree)
        assertEquals(0, scan.newBooks)
        assertEquals(1, scan.newChapters)
        assertEquals(1, scan.movedFiles)
        assertFalse(scan.structuralChangeRejected)
        assertEquals(firstIds, dao.getChaptersListForBook(first.id).map { it.id })
        assertEquals(secondIds, dao.getChaptersListForBook(second.id).take(1).map { it.id })
        // Recording the moved file must not turn the destination into a
        // shared folder and block the next import after recreation.
        val later = imports().rescanAudioEntries(moved + file("02.mp3", "Друга", 4) + file("03.mp3", "Друга", 5), tree)
        assertEquals(0, later.newBooks)
        assertEquals(1, later.newChapters)
        assertEquals(0, later.movedFiles)
        assertFalse(later.structuralChangeRejected)
        assertEquals(firstIds, dao.getChaptersListForBook(first.id).map { it.id })
        assertEquals(listOf("01", "02", "03"), dao.getChaptersListForBook(second.id).map { it.title })
    }

    @Test
    fun `rescan retains manual order and extends original anchors only for appended chapters`() = runBlocking {
        val id = importTwoChapters()
        val before = dao.getChaptersListForBook(id)
        val listening = com.slukhayka.audiobooks.data.listening.ListeningStateStore(dao)
        listening.updateProgress(id, 0, 42L)
        val saved = dao.getPlaybackProgressSync(id)
        assertEquals(ChapterReorderResult.APPLIED, imports().reorderChapters(id, before.map { it.id }, before.reversed().map { it.id }))
        val report = imports().rescanAudioEntries(listOf(entry("01.mp3", 1), entry("02.mp3", 2), entry("00.mp3", 3)), "content://tree/books")
        assertEquals(1, report.newChapters)
        assertEquals(before.reversed().map { it.id }, dao.getChaptersListForBook(id).take(2).map { it.id })
        assertEquals(saved, dao.getPlaybackProgressSync(id))
        assertEquals(1, listening.getProgressSync(id)!!.currentChapterIndex)
        listening.updateProgress(id, 2, 7L)
        assertEquals(2, dao.getPlaybackProgressSync(id)!!.currentChapterIndex)
    }

    @Test
    fun `a structural change on a mixed-Source Edition is rejected with zero writes`() = runBlocking {
        val bookId = importTwoChapters()
        val edition = dao.getEditionForWork(bookId)!!
        val localSource = dao.getSourcesForBookSync(bookId).first { it.type == "local" }
        // A real direct Source of the same Edition — the mixed-Source case.
        dao.insertSources(
            listOf(
                SourceEntity(
                    id = "soundbooks-${edition.id}",
                    bookId = bookId,
                    editionId = edition.id,
                    type = "soundbooks",
                    url = "https://sound-books.net/kobzar"
                )
            )
        )
        // Its track index collides with the local track 0 — an index alone is
        // never an identity.
        dao.insertTracks(
            listOf(
                SourceTrackEntity(
                    id = "soundbooks-${edition.id}_tr_1",
                    sourceId = "soundbooks-${edition.id}",
                    trackIndex = 0,
                    url = "https://arch.sound-books.net/01.mp3",
                    contentHash = "direct-hash"
                )
            )
        )
        dao.savePlaybackProgress(
            PlaybackProgressEntity(editionId = edition.id, bookId = bookId, currentPositionSeconds = 42L)
        )
        dao.insertBookmark(
            BookmarkEntity(bookId = bookId, editionId = edition.id, chapterIndex = 0, chapterTitle = "01.mp3", timestampSeconds = 10L, note = "")
        )
        dao.updateBookMetadata(bookId, author = "Тарас Шевченко", narrator = null, genre = null, rating = 4.5f)
        val chaptersBefore = dao.getChaptersListForBook(bookId).map { it.id to it.chapterIndex }
        val localTracksBefore = dao.getTracksForSourceSync(localSource.id).map { it.id to it.trackIndex }

        // The user drops a new file into the folder — an unproven topology change.
        val report = imports().rescanAudioEntries(
            listOf(entry("01.mp3", 1), entry("02.mp3", 2), entry("03.mp3", 3)),
            treeUri = "content://tree/books"
        )

        assertTrue("the explicit rejection result", report.structuralChangeRejected)
        assertEquals(0, report.newChapters)
        assertEquals("rejected files are not miscounted as duplicates", 0, report.duplicateFiles)
        assertEquals(chaptersBefore, dao.getChaptersListForBook(bookId).map { it.id to it.chapterIndex })
        assertEquals(localTracksBefore, dao.getTracksForSourceSync(localSource.id).map { it.id to it.trackIndex })
        assertEquals(
            "direct tracks are never touched",
            listOf("direct-hash"),
            dao.getTracksForSourceSync("soundbooks-${edition.id}").map { it.contentHash }
        )
        assertEquals(42L, dao.getPlaybackProgressSyncByEdition(edition.id)?.currentPositionSeconds)
        assertEquals(1, dao.getBookmarksForBookSync(bookId).size)
        val metadataAfter = dao.getAudiobookById(bookId)!!
        assertEquals("Тарас Шевченко", metadataAfter.author)
        assertEquals(4.5f, metadataAfter.rating)

        // The unchanged folder keeps reporting itself unchanged: the direct
        // Source stays complete (its track index 0 collides with the local one).
        val steady = imports().rescanAudioEntries(
            listOf(entry("01.mp3", 1), entry("02.mp3", 2)),
            treeUri = "content://tree/books"
        )
        assertFalse(steady.structuralChangeRejected)
        assertEquals(0, steady.missingFiles)
        assertEquals(0, steady.movedFiles)
        assertEquals(
            listOf("direct-hash"),
            dao.getTracksForSourceSync("soundbooks-${edition.id}").map { it.contentHash }
        )
    }

    @Test
    fun `a local-only Edition appends new chapters at the end without moving stored anchors`() = runBlocking {
        val bookId = importTwoChapters()
        val edition = dao.getEditionForWork(bookId)!!
        val localSource = dao.getSourcesForBookSync(bookId).first { it.type == "local" }
        dao.savePlaybackProgress(
            PlaybackProgressEntity(editionId = edition.id, bookId = bookId, currentPositionSeconds = 42L)
        )
        dao.insertBookmark(
            BookmarkEntity(bookId = bookId, editionId = edition.id, chapterIndex = 1, chapterTitle = "02.mp3", timestampSeconds = 7L, note = "")
        )
        val chaptersBefore = dao.getChaptersListForBook(bookId).map { it.id to it.chapterIndex }

        // "00-intro.mp3" sorts FIRST naturally — the append must still place
        // it after the stored chapters and keep their ids/indices.
        val report = imports().rescanAudioEntries(
            listOf(entry("00-intro.mp3", 3), entry("01.mp3", 1), entry("02.mp3", 2)),
            treeUri = "content://tree/books"
        )

        assertFalse(report.structuralChangeRejected)
        assertEquals(1, report.newChapters)
        val chaptersAfter = dao.getChaptersListForBook(bookId)
        assertEquals(chaptersBefore, chaptersAfter.take(2).map { it.id to it.chapterIndex })
        assertEquals(2, chaptersAfter.last().chapterIndex)
        assertEquals("00-intro", chaptersAfter.last().title)
        assertEquals(42L, dao.getPlaybackProgressSyncByEdition(edition.id)?.currentPositionSeconds)
        assertEquals(setOf(1), dao.getBookmarksForBookSync(bookId).map { it.chapterIndex }.toSet())
        val localTracks = dao.getTracksForSourceSync(localSource.id).sortedBy { it.trackIndex }
        assertEquals(listOf(0, 1, 2), localTracks.map { it.trackIndex })
        assertEquals(3, dao.getAudiobookById(bookId)?.totalChapters)
        assertEquals("the Edition list stays honest", 3, dao.getEditionForWork(bookId)?.totalChapters)
    }

    @Test
    fun `a tombstoned book is never resurrected nor mutated by a rescan`() = runBlocking {
        val bookId = importTwoChapters()
        dao.insertTombstone(TombstoneEntity(bookId = bookId))
        val chaptersBefore = dao.getChaptersListForBook(bookId).map { it.id }

        val report = imports().rescanAudioEntries(
            listOf(entry("01.mp3", 1), entry("02.mp3", 2), entry("03.mp3", 3)),
            treeUri = "content://tree/books"
        )

        assertEquals(0, report.newChapters)
        assertEquals(0, report.newBooks)
        assertTrue("the tombstone stays", dao.isBookTombstoned(bookId))
        assertEquals(chaptersBefore, dao.getChaptersListForBook(bookId).map { it.id })
        assertEquals(1, dao.getAllAudiobooks().first().size)
    }

    /** A persisted one-book preview remains one book on an unchanged rescan. */
    @Test
    fun `a folder imported as ONE book is not re-created by the rescan`() = runBlocking {
        val files = listOf(rootEntry("01.mp3", 1), rootEntry("02.mp3", 2), rootEntry("03.mp3", 3))
        // The REAL path: the preview plans, the listener confirms, the plan is
        // applied. `importAudioEntries` is the older direct door and has its
        // own grouping — testing it here would prove nothing about the feature
        // that ships.
        val plan = ImportPlanner.buildPlan(
            source = SourceRef.Folder("content://tree/one", "Книга", LocalFolderGrouping.ONE_BOOK),
            entries = files
        )
        imports().applyImportPlan(plan, "content://tree/one")

        val before = dao.getAllAudiobooksOnce()
        assertEquals("одна книга з трьох файлів", 1, before.size)
        assertEquals(3, dao.getChaptersListForBook(before.single().id).size)

        val report = imports().rescanAudioEntries(files, "content://tree/one")

        assertEquals(
            "повторний скан не створює книг — жодної структурної зміни не було",
            0, report.newBooks
        )
        assertEquals("і не додає розділів", 0, report.newChapters)
        assertEquals("кількість книг не змінилась", 1, dao.getAllAudiobooksOnce().size)
    }

    /**
     * The container reading must keep working: root files that were imported
     * as separate books are still separate books after a rescan. Without this
     * half, "fix the one-book case" could quietly collapse every root into one
     * book instead.
     */
    @Test
    fun `root files imported as separate books stay separate after a rescan`() = runBlocking {
        val files = listOf(rootEntry("a.mp3", 11), rootEntry("b.mp3", 12))
        val plan = ImportPlanner.buildPlan(
            source = SourceRef.Folder("content://tree/many"),
            entries = files
        )
        imports().applyImportPlan(plan, "content://tree/many")

        assertEquals(2, dao.getAllAudiobooksOnce().size)

        val report = imports().rescanAudioEntries(files, "content://tree/many")

        assertEquals(0, report.newBooks)
        assertEquals("дві книги лишились двома", 2, dao.getAllAudiobooksOnce().size)
    }
}
