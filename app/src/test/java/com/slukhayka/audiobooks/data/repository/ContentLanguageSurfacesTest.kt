package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.collections.CollectionEntry
import com.slukhayka.audiobooks.data.collections.CollectionList
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-45 (#405) T5 (#493): every ephemeral catalogue surface — the «Увесь
 * каталог» union, smart collections (matched over the union), the
 * cross-source «Новинки» rail, the per-source feed rows and global search —
 * drops cards whose content language is outside the injected selection, while
 * unknown-language ("" or unclaimed-source) rows stay visible (US17/US21).
 * The surfaces read ONE preference source: the [contentLanguageSelection]
 * flow injected into SourceCatalog — the same flow T6's persisted
 * «Мови контенту» preference will supply. An EMPTY selection = «Усі» = the
 * filter is inactive (US6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContentLanguageSurfacesTest {

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
        override val contentLanguage: String,
        private val catalogBooks: List<SourceBook> = emptyList(),
        private val newBooks: List<SourceBook> = emptyList(),
        private val searchBooks: List<SourceBook> = emptyList()
    ) : SourceAdapter {
        override suspend fun search(query: String): List<SourceBook> = searchBooks

        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            SourceBookDetail("", "", url = url, chapters = emptyList())

        override suspend fun fetchNew(limit: Int): List<SourceBook> = newBooks

        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = catalogBooks
    }

    private fun book(title: String, author: String, sourceId: String, language: String = "") =
        SourceBook(
            title = title,
            author = author,
            url = "https://$sourceId.example/$title",
            sourceId = sourceId,
            language = language
        )

    private fun repo(
        selection: MutableStateFlow<Set<String>>,
        adapters: List<SourceAdapter>,
        collections: List<CollectionList> = emptyList()
    ) = SourceCatalog(
        dao,
        adapters,
        LibraryImport(dao, context, adapters),
        collectionLists = collections,
        contentLanguageSelection = selection
    )

    @Test
    fun `union drops books outside the selection and keeps unknown rows`() = runBlocking {
        val selection = MutableStateFlow(setOf("uk"))
        val catalog = repo(
            selection,
            listOf(
                FakeAdapter("librivox", "en", catalogBooks = listOf(book("Emma", "Jane Austen", "librivox"))),
                FakeAdapter("soundbooks", "uk", catalogBooks = listOf(book("Кобзар", "Тарас Шевченко", "soundbooks"))),
                // An unclaimed source: every card stays unknown (""), never hidden.
                FakeAdapter("unclaimed", "", catalogBooks = listOf(book("Тінь", "Автор X", "unclaimed")))
            )
        )
        catalog.refreshUnifiedCatalog()

        assertEquals(
            setOf("Кобзар", "Тінь"),
            catalog.unifiedCatalog.value.map { it.title }.toSet()
        )
    }

    @Test
    fun `surfaces re-filter when the selection changes`() = runBlocking {
        val selection = MutableStateFlow(setOf("uk"))
        val catalog = repo(
            selection,
            listOf(
                FakeAdapter("librivox", "en", catalogBooks = listOf(book("Emma", "Jane Austen", "librivox"))),
                FakeAdapter("soundbooks", "uk", catalogBooks = listOf(book("Кобзар", "Тарас Шевченко", "soundbooks"))),
                FakeAdapter("unclaimed", "", catalogBooks = listOf(book("Тінь", "Автор X", "unclaimed")))
            )
        )
        catalog.refreshUnifiedCatalog()
        assertEquals(setOf("Кобзар", "Тінь"), catalog.unifiedCatalog.value.map { it.title }.toSet())

        // English on, Ukrainian off.
        selection.value = setOf("en")
        catalog.refreshUnifiedCatalog()
        assertEquals(setOf("Emma", "Тінь"), catalog.unifiedCatalog.value.map { it.title }.toSet())

        // Both on («Усі») = the filter is inactive.
        selection.value = emptySet()
        catalog.refreshUnifiedCatalog()
        assertEquals(setOf("Emma", "Кобзар", "Тінь"), catalog.unifiedCatalog.value.map { it.title }.toSet())
        // The merged cards carry truthful claims: the source's language for
        // unclaimed books (the T1 fallback), "" only when truly unknown.
        val byTitle = catalog.unifiedCatalog.value.associateBy { it.title }
        assertEquals("en", byTitle.getValue("Emma").language)
        assertEquals("uk", byTitle.getValue("Кобзар").language)
        assertEquals("", byTitle.getValue("Тінь").language)
    }

    @Test
    fun `global search filters at read time without any refresh`() = runBlocking {
        val selection = MutableStateFlow(setOf("uk"))
        val catalog = repo(
            selection,
            listOf(
                // Book carries no per-record claim — the adapter's en claim is
                // the effective language (the exact T1 write-path fallback).
                FakeAdapter("librivox", "en", searchBooks = listOf(book("Emma", "Jane Austen", "librivox"))),
                FakeAdapter("soundbooks", "uk", searchBooks = listOf(book("Кобзар", "Тарас Шевченко", "soundbooks")))
            )
        )
        // Search reads the CURRENT selection on every call — a pref change
        // re-filters search immediately, no refresh or cache invalidation.
        assertEquals(listOf("Кобзар"), catalog.searchAllSources("об").map { it.title })
        selection.value = setOf("en")
        assertEquals(listOf("Emma"), catalog.searchAllSources("об").map { it.title })
        selection.value = emptySet()
        assertEquals(setOf("Emma", "Кобзар"), catalog.searchAllSources("об").map { it.title }.toSet())
    }

    @Test
    fun `new-arrivals rail and per-source feed rows drop hidden-language books`() = runBlocking {
        val selection = MutableStateFlow(setOf("uk"))
        val catalog = repo(
            selection,
            listOf(
                FakeAdapter("librivox", "en", newBooks = listOf(book("Emma", "Jane Austen", "librivox"))),
                FakeAdapter("soundbooks", "uk", newBooks = listOf(book("Кобзар", "Тарас Шевченко", "soundbooks")))
            )
        )
        catalog.refreshSourceFeeds()

        // The per-source rows: the en source contributes no row at all.
        assertEquals(listOf("soundbooks"), catalog.sourceFeeds.value.map { it.sourceId })
        assertEquals(listOf("Кобзар"), catalog.sourceFeeds.value.single().books.map { it.title })
        // The cross-source «Новинки» rail shows the same filtered world.
        assertEquals(listOf("Кобзар"), catalog.newArrivals.value.map { it.title })

        // Both on: the rail returns to one card per source.
        selection.value = emptySet()
        catalog.refreshSourceFeeds()
        assertEquals(setOf("Emma", "Кобзар"), catalog.newArrivals.value.map { it.title }.toSet())
    }

    @Test
    fun `smart collections match only the visible union`() = runBlocking {
        val selection = MutableStateFlow(setOf("uk"))
        val austen = CollectionList(
            id = "en-classics",
            name = "Jane Austen",
            entries = listOf(CollectionEntry("Jane Austen", "Emma"))
        )
        val catalog = repo(
            selection,
            listOf(
                FakeAdapter("librivox", "en", catalogBooks = listOf(book("Emma", "Jane Austen", "librivox"))),
                FakeAdapter("soundbooks", "uk", catalogBooks = listOf(book("Кобзар", "Тарас Шевченко", "soundbooks")))
            ),
            collections = listOf(austen)
        )
        catalog.refreshUnifiedCatalog()
        // Emma is hidden → the English collection has no corpus → absent.
        assertTrue("no collection may render hidden cards", catalog.smartCollections.value.isEmpty())

        selection.value = emptySet()
        catalog.refreshUnifiedCatalog()
        assertEquals(listOf("en-classics"), catalog.smartCollections.value.map { it.id })
        assertEquals(listOf("Emma"), catalog.smartCollections.value.single().books.map { it.title })
    }
}
