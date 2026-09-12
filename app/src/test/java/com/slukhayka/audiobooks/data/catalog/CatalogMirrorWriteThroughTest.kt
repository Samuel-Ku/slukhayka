package com.slukhayka.audiobooks.data.catalog

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.TombstoneEntity
import com.slukhayka.audiobooks.data.facets.WorkFacetFilter
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-0041 / #731 — the Catalog Mirror write-through: every enumeration of
 * the union or of a source feed lands its mergeable cards in the local
 * works/editions layer through the merge-on-write door. Tombstoned Works are
 * never resurrected; cards without a Work identity never materialize; a
 * repeated refresh is idempotent; the pass makes no page requests; and the
 * endless feed reads the enumerations without any network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CatalogMirrorWriteThroughTest {

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

    private open class FakeAdapter(
        override val sourceId: String,
        private val catalogBooks: List<SourceBook>,
        private val newBooks: List<SourceBook> = emptyList()
    ) : SourceAdapter {
        override val contentLanguage: String = "uk"
        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            SourceBookDetail("", "", url = url, chapters = emptyList())
        override suspend fun fetchNew(limit: Int): List<SourceBook> = newBooks
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = catalogBooks
    }

    private fun catalog(vararg adapters: SourceAdapter) =
        SourceCatalog(dao, adapters.toList(), LibraryImport(dao, context, adapters.toList()))

    private fun book(
        title: String,
        author: String,
        sourceId: String,
        url: String = "https://$sourceId.example/$title"
    ) = SourceBook(title = title, author = author, url = url, sourceId = sourceId)

    @Test
    fun `a union refresh lands enumerated works in the local mirror`() = runBlocking {
        val repository = catalog(
            FakeAdapter("soundbooks", listOf(book("Кобзар", "Тарас Шевченко", "soundbooks")))
        )

        repository.refreshUnifiedCatalog()

        val work = dao.findWorkByMergeKey(MergeKey.keyFor("Кобзар", "Тарас Шевченко"))
        assertNotNull(work)
        assertEquals("Кобзар", work?.title)
    }

    @Test
    fun `a union refresh lands the card's language in the known set (spec-51 T5)`() = runBlocking {
        // #744 — the «Мови контенту» options read every Edition, not just the
        // Library: a multilingual enumeration must widen the offered set.
        val repository = catalog(
            FakeAdapter(
                "librivox",
                listOf(
                    book(
                        "Die Schatzinsel",
                        "Robert Louis Stevenson",
                        "librivox",
                        "https://archive.org/details/die_schatzinsel_2212_librivox"
                    ).copy(language = "de")
                )
            )
        )

        repository.refreshUnifiedCatalog()

        assertTrue("admitted language reaches the preference options", dao.knownEditionLanguages().contains("de"))
    }

    @Test
    fun `a repeated refresh is idempotent`() = runBlocking {
        val card = book("Кобзар", "Тарас Шевченко", "soundbooks")
        val repository = catalog(FakeAdapter("soundbooks", listOf(card)))

        repository.refreshUnifiedCatalog()
        repository.refreshUnifiedCatalog()

        val workId = MergeKey.keyFor("Кобзар", "Тарас Шевченко")
        assertEquals(1, dao.observeWorks().first().count { it.id == workId })
        assertEquals(1, dao.getWorkSourcesForWorkSync(workId).size)
    }

    @Test
    fun `a source feed refresh lands its works in the local mirror`() = runBlocking {
        val repository = catalog(
            FakeAdapter(
                "sluhayua",
                emptyList(),
                newBooks = listOf(book("Сад", "Ольга Слоньовська", "sluhayua"))
            )
        )

        repository.refreshSourceFeeds()

        assertNotNull(dao.findWorkByMergeKey(MergeKey.keyFor("Сад", "Ольга Слоньовська")))
    }

    @Test
    fun `a tombstoned work is never resurrected by enumeration`() = runBlocking {
        val repository = catalog(
            FakeAdapter("soundbooks", listOf(book("Кобзар", "Тарас Шевченко", "soundbooks"))),
            FakeAdapter(
                "lihtar",
                listOf(book("Кобзар", "Тарас Шевченко", "lihtar", "https://lihtar.example/kobzar"))
            )
        )
        repository.writeWorkEdition(
            sourceId = "soundbooks",
            title = "Кобзар",
            author = "Тарас Шевченко",
            narrator = "",
            sourceUrl = "https://soundbooks.example/Кобзар"
        )
        val workId = MergeKey.keyFor("Кобзар", "Тарас Шевченко")
        dao.insertTombstone(TombstoneEntity(bookId = workId))
        val sourcesBefore = dao.getWorkSourcesForWorkSync(workId).size

        repository.refreshUnifiedCatalog()

        assertEquals(sourcesBefore, dao.getWorkSourcesForWorkSync(workId).size)
        assertTrue(dao.getWorkSourcesForWorkSync(workId).none { it.sourceId == "lihtar" })
    }

    @Test
    fun `cards without a work identity never materialize`() = runBlocking {
        val repository = catalog(
            FakeAdapter(
                "sluhay",
                listOf(SourceBook(title = "Без автора", author = "", url = "https://sluhay.com/x", sourceId = "sluhay"))
            )
        )

        repository.refreshUnifiedCatalog()

        assertTrue(dao.observeWorks().first().isEmpty())
    }

    @Test
    fun `a failing enumeration leaves no partial rows`() = runBlocking {
        val repository = catalog(
            object : FakeAdapter("soundbooks", emptyList()) {
                override suspend fun fetchCatalog(limit: Int): List<SourceBook> =
                    throw RuntimeException("boom")
            }
        )

        val union = repository.refreshUnifiedCatalog()

        assertTrue(union.isEmpty())
        assertTrue(dao.observeWorks().first().isEmpty())
    }

    @Test
    fun `the write-through adds no page fetches beyond the enumeration`() = runBlocking {
        var catalogCalls = 0
        var pageCalls = 0
        val adapter = object : FakeAdapter("soundbooks", emptyList()) {
            override suspend fun fetchCatalog(limit: Int): List<SourceBook> {
                catalogCalls++
                return listOf(book("Кобзар", "Тарас Шевченко", "soundbooks"))
            }

            override suspend fun fetchBookPage(url: String): SourceBookDetail {
                pageCalls++
                return SourceBookDetail("", "", url = url, chapters = emptyList())
            }
        }

        catalog(adapter).refreshUnifiedCatalog()

        assertEquals(1, catalogCalls)
        assertEquals(0, pageCalls)
    }

    @Test
    fun `the endless feed reads enumerated works without network`() = runBlocking {
        val repository = catalog(
            FakeAdapter("soundbooks", listOf(book("Кобзар", "Тарас Шевченко", "soundbooks")))
        )

        repository.refreshUnifiedCatalog()

        val page = repository.pagedWorkFeedRecent(WorkFacetFilter()).load(
            PagingSource.LoadParams.Refresh<Int>(
                key = null,
                loadSize = 30,
                placeholdersEnabled = false
            )
        )
        val rows = (page as PagingSource.LoadResult.Page).data
        assertEquals(listOf("Кобзар"), rows.map { it.title })
    }
}
