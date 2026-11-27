package com.slukhayka.audiobooks.data.merge

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ManualWorkMergeExistingCanonicalOpenCarriersRoomTest {
    @Test(timeout = 180_000)
    fun `existing canonical metadata Book without Edition preserves both physical Books through undo`() = runBlocking {
        exercise(metadataBooks = true)
    }

    @Test(timeout = 180_000)
    fun `existing canonical personal Entry without Book preserves paper pages and ebook percent through undo`() = runBlocking {
        exercise(metadataBooks = false)
    }

    private suspend fun exercise(metadataBooks: Boolean) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "manual-existing-canonical-carriers-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries().build()
        assertFalse(context.getDatabasePath(name).exists())
        var database = open()
        val titleA = if (metadataBooks) "Canonical metadata" else "Canonical personal"
        val titleB = if (metadataBooks) "Donor metadata" else "Donor personal"
        val a = if (metadataBooks) "canonical metadata|fixture author" else "canonical personal|fixture author"
        val b = if (metadataBooks) "donor metadata|fixture author" else "donor personal|fixture author"
        val op = if (metadataBooks) "existing-canonical-metadata-book" else "existing-canonical-personal-entry"
        val works = listOf(WorkEntity(a, a, titleA, "Fixture Author", addedAt = 10L),
            WorkEntity(b, b, titleB, "Fixture Author", addedAt = 20L))
        val entries = listOf(LibraryEntryEntity(a, a, "EXPLICIT_SAVE", true, 13L, 0f, "IDLE"),
            LibraryEntryEntity(if (metadataBooks) b else "manual:ebook:donor personal|fixture author", b,
                if (metadataBooks) "EXPLICIT_SAVE" else "EXPLICIT_IMPORT", false, 23L, 0f, "IDLE"))
        val passes = if (metadataBooks) emptyList() else listOf(
            ReadthroughEntity("pass-paper", a, a, "PAPER", "IN_PROGRESS", 300L, null, null, "PAGES", 80,
                """[ {"at":301,"u":"PAGES","v":20}, {"at":302,"u":"PAGES","v":80}, {"futureTail":{"paper":"own units"}} ]"""),
            ReadthroughEntity("pass-ebook", entries[1].id, b, "EBOOK", "IN_PROGRESS", 400L, null, null, "PERCENT", 42,
                """[{"at":401,"u":"PERCENT","v":42}, {"futureTail":{"layout":"unknown future"}}]"""))
        val books = if (metadataBooks) listOf(metadataBook(a, titleA), metadataBook(b, titleB)) else emptyList()
        // ADR-0041: personal Works without provider claims are not discovery-indexed.
        val fts = emptyList<WorkSearchFtsEntity>()
        try {
            var dao = database.audiobookDao()
            works.forEach { dao.upsertWork(it) }
            entries.forEach {
                dao.upsertLibraryEntry(id = it.id, workId = it.workId, origin = it.origin,
                    isFavorite = it.isFavorite, createdAt = it.createdAt,
                    downloadProgress = it.downloadProgress, downloadState = it.downloadState)
            }
            if (metadataBooks) for ((id, title) in listOf(a to titleA, b to titleB)) {
                dao.insertCatalogBookIfNotTombstoned(id, title, "Fixture Author", "", "", 0,
                    null, "", "", false, 0L, 0, 0f, null)
            }
            passes.forEach { dao.upsertReadthrough(it) }
            listOf(a, b).forEach { dao.refreshWorkSearchIndex(it) }
            val expectedBefore = Image(works, entries, books, passes.sortedBy { it.id }, fts, emptyList())
            assertEquals("SETUP: exact typed legitimate carrier records", expectedBefore, image(dao, listOf(a, b)))
            assertEquals(0, database.workIdentityDao().operationCount())
            val commands = ManualWorkMergeCommands(database, ManualWorkMergeGate(database))
            val prepared = commands.prepareManualWorkMerge(a, b)
            assertTrue("SETUP: participant ownership is unambiguous", prepared.preview.conflicts.isEmpty())
            assertTrue(prepared.preview.participants.all { it.editions.isEmpty() })
            assertEquals(expectedBefore, image(dao, listOf(a, b)))
            assertEquals(if (metadataBooks) "968_EXISTING_CANONICAL_METADATA_BOOK_WITHOUT_EDITION_MUST_APPLY"
                else "968_EXISTING_CANONICAL_PERSONAL_ENTRY_WITHOUT_BOOK_MUST_APPLY",
                ManualWorkMergeApplyResult.Applied(op, a), commands.applyManualWorkMerge(
                    ManualWorkMergeApprovedPlan(prepared, op, titleA, "Fixture Author")))
            val expectedAfter = Image(listOf(works[0]), entries.map { it.copy(workId = a) }, books,
                passes.map { it.copy(workId = a) }.sortedBy { it.id }, fts, emptyList())
            assertEquals(expectedAfter, image(dao, listOf(a)))
            assertPolicy(database, op, a, b, metadataBooks, WorkMergeOperationState.APPLIED)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals(expectedAfter, image(dao, listOf(a)))
            assertPolicy(database, op, a, b, metadataBooks, WorkMergeOperationState.APPLIED)
            assertEquals(ManualWorkMergeUndoResult.Undone(op),
                ManualWorkMergeCommands(database, ManualWorkMergeGate(database)).undoManualWorkMerge(op))
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals("Undo restores all typed carrier and FTS beforeimages", expectedBefore, image(dao, listOf(a, b)))
            assertPolicy(database, op, a, b, metadataBooks, WorkMergeOperationState.UNDONE)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun assertPolicy(database: AudiobookDatabase, op: String, a: String, b: String,
        metadataBooks: Boolean, state: WorkMergeOperationState) {
        val identity = database.workIdentityDao()
        assertEquals(1, identity.operationCount())
        assertEquals(state, identity.operationStateById(op))
        assertTrue("No synthetic Edition protection pairs", identity.preservedParticipants(op).isEmpty())
        assertEquals(if (metadataBooks) listOf(PreservedWorkMergeBook(op, a, true), PreservedWorkMergeBook(op, b, true))
            else emptyList<PreservedWorkMergeBook>(), identity.preservedBooks(op))
        assertEquals(metadataBooks, identity.bookAutoMergeExcluded(a))
        assertEquals(metadataBooks, identity.bookAutoMergeExcluded(b))
        assertEquals(if (state == WorkMergeOperationState.APPLIED) 2 else 0, identity.redirectCount())
        for (namespace in listOf("WORK_ID", "MERGE_KEY")) {
            assertNull("Canonical participant never redirects to itself", identity.redirectByAlias(namespace, a))
            val donor = identity.redirectByAlias(namespace, b)
            if (state == WorkMergeOperationState.APPLIED) {
                assertEquals(WorkIdentityRedirectRow(namespace, b, a, op), donor)
            } else assertNull(donor)
        }
    }

    private fun metadataBook(id: String, title: String) = ManualWorkMergeBookRow(id, title, "Fixture Author", "", "", 0,
        null, "", "", false, 0L, 0, 0f, null)

    private data class Image(val works: List<WorkEntity>, val entries: List<LibraryEntryEntity>,
        val books: List<ManualWorkMergeBookRow>, val readthroughs: List<ReadthroughEntity>,
        val fts: List<WorkSearchFtsEntity>, val unexpectedNonemptyCarriers: List<String>)

    private suspend fun image(dao: AudiobookDao, roots: List<String>): Image {
        val snapshot = dao.readManualWorkMergeRows(roots)
        assertEquals(roots.size, dao.countWorks())
        assertEquals(2, dao.countLibraryEntries())
        assertEquals(snapshot.library.books.map { it.id }.sorted(), dao.getAllAudiobooksOnce().map { it.id }.sorted())
        assertEquals(snapshot.library.readthroughs.sortedBy { it.id }, dao.allReadthroughs().sortedBy { it.id })
        val other = listOf(
            "editions" to snapshot.library.editions, "sources" to snapshot.library.sources,
            "tracks" to snapshot.library.tracks, "chapters" to snapshot.library.chapters,
            "progress" to snapshot.library.progress, "bookmarks" to snapshot.library.bookmarks,
            "settings" to snapshot.library.settings, "events" to snapshot.library.events,
            "failures" to snapshot.library.failures, "submissions" to snapshot.library.submissions,
            "workSources" to snapshot.catalog.workSources, "series" to snapshot.catalog.series,
            "seriesMembers" to snapshot.catalog.seriesMembers, "universes" to snapshot.catalog.universes,
            "workFacets" to snapshot.catalog.workFacets, "facetSeries" to snapshot.catalog.facetSeries,
            "genres" to snapshot.catalog.genres, "genreStates" to snapshot.catalog.genreStates,
            "genreAssertions" to snapshot.catalog.genreAssertions, "workGenres" to snapshot.catalog.workGenres,
            "editionFacets" to snapshot.catalog.editionFacets, "authors" to snapshot.catalog.authors,
            "authorAliases" to snapshot.catalog.authorAliases, "popularity" to snapshot.catalog.popularity,
            "vectors" to snapshot.catalog.vectors, "corrections" to snapshot.personal.corrections,
            "tombstones" to snapshot.personal.tombstones, "preferences" to snapshot.personal.preferences,
            "collections" to snapshot.personal.collections, "collectionItems" to snapshot.personal.collectionItems,
            "feedSnapshots" to snapshot.personal.feedSnapshots, "stats" to snapshot.retained.stats,
            "sessions" to snapshot.retained.sessions, "achievements" to snapshot.retained.achievements,
            "achievementFacts" to snapshot.retained.achievementFacts, "achievementCounters" to snapshot.retained.achievementCounters,
            "personBookmarks" to snapshot.retained.personBookmarks, "hiddenReviewers" to snapshot.retained.hiddenReviewers,
            "friendships" to snapshot.retained.friendships, "socialBlocks" to snapshot.retained.socialBlocks)
        return Image(snapshot.library.works.sortedBy { it.id }, snapshot.library.entries.sortedBy { it.id },
            snapshot.library.books.sortedBy { it.id }, snapshot.library.readthroughs.sortedBy { it.id },
            snapshot.catalog.ftsRows.sortedBy { it.workId }, other.filter { it.second.isNotEmpty() }.map { it.first })
    }
}
