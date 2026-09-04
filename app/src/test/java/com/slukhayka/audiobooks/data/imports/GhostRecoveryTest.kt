package com.slukhayka.audiobooks.data.imports

import com.slukhayka.audiobooks.data.catalog.CatalogBook
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import com.slukhayka.audiobooks.testing.FakeAudiobookDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-1 loop for #476/#479 (pure JVM — no Robolectric): a catalog ghost
 * row (audiobooks + Work + Library Entry, but no Edition/Source/chapters)
 * must import on browser capture instead of failing recovery.
 *
 * Production-faithful: the ghost is seeded through the real catalog write
 * path ([LibraryImport.upsertCatalogBook]), the capture through the real
 * [BrowserRecoveryCoordinator.recover] with a stub adapter.
 */
class GhostRecoveryTest {

    private fun fakeAdapter(detail: SourceBookDetail): SourceAdapter =
        object : SourceAdapter {
            override val sourceId: String = "4read"
            override suspend fun search(query: String): List<SourceBook> = emptyList()
            override suspend fun fetchBookPage(url: String): SourceBookDetail = throw IllegalStateException("not used")
            override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
            override suspend fun parseCapturedPage(html: String, url: String): SourceBookDetail = detail
        }

    @Test
    fun `catalog ghost without sources imports on browser capture`() = runTest {
        val dao = FakeAudiobookDao()
        val url = "https://4read.org/7589-neostannij-bij.html"
        val seedImports = LibraryImport(dao, null, listOf(fakeAdapter(
            SourceBookDetail(title = "", author = "", url = url, chapters = emptyList())
        )))
        val ghost = seedImports.upsertCatalogBook(
            CatalogBook(
                id = "4read-7589-neostannij-bij",
                title = "Неостанній бій",
                author = "Костянтин Шелест",
                url = url,
                coverImageUrl = null
            )
        ) ?: error("ghost seed failed")
        assertTrue(
            "seed must leave no Source behind",
            dao.getSourcesForBookSync(ghost.id).isEmpty()
        )

        val captured = SourceBookDetail(
            title = "Неостанній бій",
            author = "Костянтин Шелест",
            narrator = "Валерій Завалко",
            url = url,
            chapters = listOf(
                SourceChapter("Глава 1", "https://s1.reasd.org/7589/01.mp3"),
                SourceChapter("Глава 2", "https://s1.reasd.org/7589/02.mp3")
            )
        )
        val imports = LibraryImport(dao, null, listOf(fakeAdapter(captured)))
        val coordinator = BrowserRecoveryCoordinator(
            dao = dao,
            libraryImport = imports,
            playbackVerifier = BrowserRecoveryCoordinator.PlaybackVerifier { _, _ -> true }
        )

        val outcome = coordinator.recover(ghost.id, "4read", url, "cap")

        assertTrue("ghost must import, was $outcome", outcome is BrowserRecoveryCoordinator.Outcome.Success)
        val succ = outcome as BrowserRecoveryCoordinator.Outcome.Success
        assertEquals(ghost.id, succ.book.id)
        assertEquals(2, dao.getChaptersListForBook(ghost.id).size)
        assertEquals(2, dao.getTracksForBookSync(ghost.id).size)
    }
}
