package com.slukhayka.audiobooks.data.merge

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One catalog-only Commands/typed Room tracer; it never manufactures a Book anchor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ManualWorkMergeCatalogOnlyRoomTest {
    @Test(timeout = 60_000)
    fun `catalog-only roots preserve Editions without library rows and undo exact typed payloads after reopening`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "manual-catalog-only-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, AudiobookDatabase::class.java, name)
            .allowMainThreadQueries().build()
        assertFalse(context.getDatabasePath(name).exists())
        var database = open()
        try {
            var dao = database.audiobookDao()
            seed(dao)
            val before = typedImage(dao)
            assertEquals(listOf(workA(), workB()), before.works)
            assertEquals(listOf(editionA(), editionB()), before.editions)
            assertEquals(listOf(claimA(), claimB()), before.claims)
            assertEquals(listOf(fantasy(), poetry()), before.genres)
            assertEquals(listOf(stateA(), stateB()), before.genreStates)
            assertEquals(listOf(assertionA(), assertionB()), before.genreAssertions)
            assertEquals(listOf(linkA(), linkB()), before.workGenres)
            assertNoLibraryRows(dao, listOf(A, B))

            val commands = ManualWorkMergeCommands(database, ManualWorkMergeGate(database))
            val prepared = commands.prepareManualWorkMerge(A, B)
            assertTrue("SETUP: catalog roots are valid typed references", prepared.preview.conflicts.isEmpty())
            assertEquals(listOf(A, B), prepared.preview.participants.map { it.work.id }.sorted())
            assertEquals(listOf(EDITION_A, EDITION_B), prepared.preview.participants.flatMap { it.editions }.map { it.edition.id }.sorted())
            assertTrue("SETUP: catalog Editions have no library Book association", prepared.preview.participants.flatMap { it.editions }.all { it.bookId == null })
            assertEquals(
                "968_CATALOG_ONLY_ROOTS_MUST_APPLY_WITHOUT_LIBRARY_CARRIERS",
                ManualWorkMergeApplyResult.Applied(OPERATION, C),
                commands.applyManualWorkMerge(ManualWorkMergeApprovedPlan(prepared, OPERATION, "Catalog Combined", "Catalog Author")),
            )
            assertApplied(dao)
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertApplied(dao)

            val reopenedCommands = ManualWorkMergeCommands(database, ManualWorkMergeGate(database))
            assertEquals(ManualWorkMergeUndoResult.Undone(OPERATION), reopenedCommands.undoManualWorkMerge(OPERATION))
            database.close()
            database = open()
            dao = database.audiobookDao()
            assertEquals("Undo restores every seeded typed payload, including original claim/assertion ids", before, typedImage(dao))
            assertNull(dao.getWorkById(C))
            assertEquals(listOf(A, B), dao.observeWorks().first().map { it.id }.sorted())
            assertNoLibraryRows(dao, listOf(A, B))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun seed(dao: AudiobookDao) {
        dao.upsertWork(workA())
        dao.upsertWork(workB())
        dao.replaceEdition(editionA())
        dao.replaceEdition(editionB())
        dao.upsertWorkSource(claimA())
        dao.upsertWorkSource(claimB())
        dao.insertGenreFacets(listOf(fantasy(), poetry()))
        dao.upsertGenreAssertionState(stateA())
        dao.upsertGenreAssertionState(stateB())
        dao.insertGenreAssertions(listOf(assertionA(), assertionB()))
        dao.insertWorkGenres(listOf(linkA(), linkB()))
        dao.refreshWorkSearchIndex(A)
        dao.refreshWorkSearchIndex(B)
    }

    private suspend fun assertApplied(dao: AudiobookDao) {
        assertEquals(listOf(C), dao.observeWorks().first().map { it.id })
        assertNull(dao.getWorkById(A))
        assertNull(dao.getWorkById(B))
        assertEquals(WorkEntity(C, C, "Catalog Combined", "Catalog Author", addedAt = 10L), dao.getWorkById(C))
        val snapshot = dao.readManualWorkMergeRows(listOf(C))
        assertEquals(listOf(editionA().copy(workId = C), editionB().copy(workId = C)), snapshot.library.editions.sortedBy { it.id })
        assertEquals(2, snapshot.catalog.workSources.size)
        assertTrue(snapshot.catalog.workSources.all { it.workId == C })
        assertEquals(listOf(claimPayload(claimA()), claimPayload(claimB())).sortedBy { it.sourceId }, snapshot.catalog.workSources.map(::claimPayload).sortedBy { it.sourceId })
        assertEquals(listOf(fantasy(), poetry()), snapshot.catalog.genres.sortedBy { it.id })
        assertEquals(listOf(stateA().copy(workId = C), stateB().copy(workId = C)).sortedBy { it.sourceId }, snapshot.catalog.genreStates.sortedBy { it.sourceId })
        assertEquals(listOf(assertionPayload(assertionA().copy(workId = C)), assertionPayload(assertionB().copy(workId = C))).sortedBy { it.sourceId }, snapshot.catalog.genreAssertions.map(::assertionPayload).sortedBy { it.sourceId })
        assertEquals(listOf(linkA().copy(workId = C), linkB().copy(workId = C)).sortedBy { it.genreId }, snapshot.catalog.workGenres.sortedBy { it.genreId })
        assertNoLibraryRows(dao, listOf(C))
    }

    private suspend fun assertNoLibraryRows(dao: AudiobookDao, roots: List<String>) {
        assertEquals(0, dao.countLibraryEntries())
        assertTrue(dao.getAllAudiobooksOnce().isEmpty())
        val rows = dao.readManualWorkMergeRows(roots).library
        val nonempty = listOf(
            "entries" to rows.entries, "books" to rows.books, "sources" to rows.sources,
            "tracks" to rows.tracks, "chapters" to rows.chapters, "progress" to rows.progress,
            "bookmarks" to rows.bookmarks, "settings" to rows.settings, "readthroughs" to rows.readthroughs,
            "events" to rows.events, "failures" to rows.failures, "submissions" to rows.submissions,
        ).filter { it.second.isNotEmpty() }.map { it.first }
        assertEquals("no phantom library or playable-audio carriers", emptyList<String>(), nonempty)
    }

    private suspend fun typedImage(dao: AudiobookDao): TypedImage {
        val snapshot = dao.readManualWorkMergeRows(listOf(A, B))
        return TypedImage(
            snapshot.library.works.sortedBy { it.id }, snapshot.library.editions.sortedBy { it.id },
            snapshot.catalog.workSources.sortedBy { it.id }, snapshot.catalog.genres.sortedBy { it.id },
            snapshot.catalog.genreStates.sortedWith(compareBy<GenreAssertionStateEntity> { it.workId }.thenBy { it.sourceId }),
            snapshot.catalog.genreAssertions.sortedBy { it.id },
            snapshot.catalog.workGenres.sortedWith(compareBy<WorkGenreEntity> { it.workId }.thenBy { it.genreId }.thenBy { it.sourceId }),
            snapshot.catalog.ftsRows.sortedBy { it.workId },
        )
    }

    private data class TypedImage(
        val works: List<WorkEntity>, val editions: List<EditionEntity>, val claims: List<WorkSourceEntity>,
        val genres: List<GenreFacetEntity>, val genreStates: List<GenreAssertionStateEntity>,
        val genreAssertions: List<GenreAssertionEntity>, val workGenres: List<WorkGenreEntity>,
        val searchRows: List<WorkSearchFtsEntity>,
    )

    private data class ClaimPayload(val sourceId: String, val url: String, val streamOnly: Boolean, val cover: String?, val duration: Long?, val addedAt: Long)
    private fun claimPayload(claim: WorkSourceEntity) = ClaimPayload(claim.sourceId, claim.sourceUrl, claim.streamOnly, claim.coverImageUrl, claim.durationSeconds, claim.addedAt)
    private data class GenrePayload(val assertionId: String, val workId: String, val genreId: String, val rawText: String, val sourceId: String, val observedAt: Long)
    private fun assertionPayload(row: GenreAssertionEntity) = GenrePayload(row.assertionId, row.workId, row.genreId, row.rawText, row.sourceId, row.observedAt)

    private fun workA() = WorkEntity(A, A, "Catalog A", "Catalog Author", addedAt = 10L)
    private fun workB() = WorkEntity(B, B, "Catalog B", "Catalog Author", addedAt = 20L)
    private fun editionA() = EditionEntity(EDITION_A, A, "uk", "Reader A", 7, 120L, 10L)
    private fun editionB() = EditionEntity(EDITION_B, B, "en", "Reader B", 9, 180L, 20L)
    private fun claimA() = WorkSourceEntity("catalog-claim-a", A, "sound-books", "https://sound-books.net/catalog-a", false, "https://example.test/a.jpg", 120L, 11L)
    private fun claimB() = WorkSourceEntity("catalog-claim-b", B, "sluhayua", "https://sluhay.com.ua/200:catalog-b", true, "https://example.test/b.jpg", 180L, 21L)
    private fun fantasy() = GenreFacetEntity("fantasy", "Фентезі", "фентезі")
    private fun poetry() = GenreFacetEntity("poetry", "Поезія", "поезія")
    private fun stateA() = GenreAssertionStateEntity(A, "sound-books", 100L, "enumeration")
    private fun stateB() = GenreAssertionStateEntity(B, "sluhayua", 200L, "enumeration")
    private fun assertionA() = GenreAssertionEntity("catalog-genre-a", "genre-a", A, "fantasy", "Фентезі", "sound-books", 101L)
    private fun assertionB() = GenreAssertionEntity("catalog-genre-b", "genre-b", B, "poetry", "Поезія", "sluhayua", 201L)
    private fun linkA() = WorkGenreEntity(A, "fantasy", "sound-books")
    private fun linkB() = WorkGenreEntity(B, "poetry", "sluhayua")

    private companion object {
        const val A = "catalog a|catalog author"
        const val B = "catalog b|catalog author"
        const val C = "catalog combined|catalog author"
        const val EDITION_A = "catalog-edition-a"
        const val EDITION_B = "catalog-edition-b"
        const val OPERATION = "catalog-only-merge"
    }
}
