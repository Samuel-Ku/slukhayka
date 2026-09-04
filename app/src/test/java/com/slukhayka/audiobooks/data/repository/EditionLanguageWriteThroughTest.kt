package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.EditionId
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-45 (#405) R1 (#508) — the Edition's content language survives BOTH
 * write paths (the import door and the catalogue merge-on-write) and lands in
 * the edition_facets projection through the shared seam, so the feed's
 * language dimension and the shared sync see the same claim. Same Work +
 * same narrator with uk/en claims are TWO Editions; a re-import of a legacy
 * blank-language Edition never duplicates it, loses its chapters/tracks, or
 * merges a foreign language onto it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EditionLanguageWriteThroughTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao
    private lateinit var imports: LibraryImport
    private lateinit var catalog: SourceCatalog

    private class ClaimingAdapter(
        override val sourceId: String,
        override val contentLanguage: String
    ) : SourceAdapter {
        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            SourceBookDetail("", "", url = url, chapters = emptyList())
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
        override suspend fun fetchCatalog(limit: Int): List<SourceBook> = emptyList()
    }

    private fun detail(
        title: String = "Кобзар",
        author: String = "Тарас Шевченко",
        narrator: String = "Валерій Завалко",
        language: String = "uk",
        url: String = "https://sound-books.net/kobzar.html",
        chapters: Int = 2
    ) = SourceBookDetail(
        title = title,
        author = author,
        narrator = narrator,
        language = language,
        url = url,
        chapters = List(chapters) { i ->
            SourceChapter("Розділ ${i + 1}", "https://arch.sound-books.net/kobzar/${i + 1}.mp3")
        }
    )

    /** Reads the edition_facets row's language claim directly (raw Room). */
    private fun facetLanguageOf(editionId: String): String? {
        db.openHelper.readableDatabase
            .query("SELECT language FROM edition_facets WHERE editionId = ?", arrayOf<Any?>(editionId))
            .use { cursor ->
                return if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
        val adapters = listOf(
            ClaimingAdapter("soundbooks", "uk"),
            ClaimingAdapter("librivox", "en")
        )
        imports = LibraryImport(dao, context, adapters)
        catalog = SourceCatalog(dao, adapters, imports)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `import writes the Edition language and its facet projection`() = runBlocking {
        imports.importBookFromSource("soundbooks", detail(language = "uk", narrator = "Начитка А"))
        imports.importBookFromSource("librivox", detail(language = "en", narrator = "Narrator B"))

        val works = dao.getAllAudiobooks().first()
        assertEquals(2, works.size)
        val editions = works.mapNotNull { dao.getEditionForWork(it.id) }
        assertEquals(setOf("uk", "en"), editions.map { it.language }.toSet())
        // R1: the facet projection carries the same claim through the seam.
        editions.forEach { edition ->
            assertEquals("facet language mirrors the Edition row", edition.language, facetLanguageOf(edition.id))
        }
    }

    @Test
    fun `same Work same narrator with uk and en claims are two Editions of one Work`() = runBlocking {
        val uk = imports.importBookFromSource("soundbooks", detail(language = "uk"))
        val en = imports.importBookFromSource("librivox", detail(language = "en", url = "https://archive.org/details/kobzar_en"))

        // One Work (the merge key is bibliographic), two library cards.
        assertEquals(1, dao.countWorks())
        assertEquals(2, dao.getAllAudiobooks().first().size)
        assertTrue(uk.id != en.id)
        val workIdA = dao.getAudiobookById(uk.id)?.workId
        val workIdB = dao.getAudiobookById(en.id)?.workId
        assertEquals(workIdA, workIdB)
        // The identity formula carries the language — distinct Edition rows.
        val editionUk = dao.getEditionForWork(uk.id)!!
        val editionEn = dao.getEditionForWork(en.id)!!
        assertEquals("uk", editionUk.language)
        assertEquals("en", editionEn.language)
        assertTrue(editionUk.id != editionEn.id)
        // Each Edition keeps its own chapters.
        assertEquals(2, dao.getChaptersListForBook(uk.id).size)
        assertEquals(2, dao.getChaptersListForBook(en.id).size)
        // Both facet projections are present.
        assertEquals("uk", facetLanguageOf(editionUk.id))
        assertEquals("en", facetLanguageOf(editionEn.id))
    }

    @Test
    fun `re-importing either language is idempotent`() = runBlocking {
        imports.importBookFromSource("soundbooks", detail(language = "uk"))
        imports.importBookFromSource("librivox", detail(language = "en", url = "https://archive.org/details/kobzar_en"))

        // Re-import both renditions — nothing may duplicate.
        imports.importBookFromSource("soundbooks", detail(language = "uk"))
        imports.importBookFromSource("librivox", detail(language = "en", url = "https://archive.org/details/kobzar_en"))

        assertEquals(1, dao.countWorks())
        assertEquals(2, dao.getAllAudiobooks().first().size)
        // Both Edition rows (one per language) survive the re-imports.
        val editions = dao.getAllAudiobooks().first().mapNotNull { dao.getEditionForWork(it.id) }
        assertEquals(2, editions.size)
    }

    @Test
    fun `re-import of a legacy blank-language Edition does not duplicate or relabel it`() = runBlocking {
        // A T1-era row: Edition written with the "" language id.
        val legacy = detail(language = "uk")
        val mergeKey = com.slukhayka.audiobooks.data.merge.MergeKey.keyFor(legacy.title, legacy.author)
        val legacyEditionId = EditionId.forBook(mergeKey, "", "Валерій Завалко", "")
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = "legacy-book",
                    title = legacy.title, author = legacy.author, narrator = "Валерій Завалко",
                    description = "", coverDrawableRes = 0, genre = "", sourceUrl = legacy.url,
                    totalChapters = legacy.chapters.size
                )
            )
        )
        dao.upsertWork(
            WorkEntity(
                id = mergeKey,
                mergeKey = mergeKey,
                title = legacy.title,
                author = legacy.author,
                addedAt = System.currentTimeMillis()
            )
        )
        dao.upsertLibraryEntry(
            id = "legacy-book", workId = mergeKey, isFavorite = false,
            createdAt = System.currentTimeMillis(), downloadProgress = 0f
        )
        dao.insertEdition(
            EditionEntity(
                id = legacyEditionId,
                workId = "legacy-book",
                narrator = "Валерій Завалко",
                language = "",
                totalChapters = legacy.chapters.size,
                totalDurationSeconds = 0L
            )
        )

        // Re-import the same rendition with a KNOWN language claim: the legacy
        // ""-id lookup matches the old card (no duplicate) and the language is
        // NOT merged onto the legacy Edition (a re-import never relabels an
        // existing rendition).
        val result = imports.importBookFromSource("soundbooks", legacy)
        assertEquals("legacy-book", result.id)
        assertEquals(1, dao.getAllAudiobooks().first().size)
        val edition = dao.getEditionForWork("legacy-book")!!
        assertEquals("legacy Edition keeps its blank language", "", edition.language)
        assertEquals(legacyEditionId, edition.id)
        // The facet projection carries nothing (unknown) — never a fabricated claim.
        assertNull(facetLanguageOf(legacyEditionId))
    }

    @Test
    fun `catalogue merge-on-write lands the Edition language and its facet`() = runBlocking {
        catalog.writeWorkEdition(
            sourceId = "4read",
            title = "Тіні забутих предків",
            author = "Михайло Коцюбинський",
            narrator = "",
            sourceUrl = "https://4read.org/tini.html",
            language = "uk"
        )

        val work = dao.findWorkByMergeKey(
            com.slukhayka.audiobooks.data.merge.MergeKey.keyFor("Тіні забутих предків", "Михайло Коцюбинський")
        )
        assertNotNull(work)
        val edition = dao.getEditionForWork(work!!.id)
        assertNotNull("catalogue write materialises an Edition", edition)
        assertEquals("uk", edition!!.language)
        assertEquals("uk", facetLanguageOf(edition.id))
    }
}
