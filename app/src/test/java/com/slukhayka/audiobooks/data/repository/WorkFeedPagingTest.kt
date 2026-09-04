package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.facets.GenreFacetAssertion
import com.slukhayka.audiobooks.data.facets.GenreSourceFacetReplacement
import com.slukhayka.audiobooks.data.facets.EditionFacetDelta
import com.slukhayka.audiobooks.data.facets.LocalFacetDelta
import com.slukhayka.audiobooks.data.facets.WorkFacetDelta
import com.slukhayka.audiobooks.data.facets.WorkFacetFilter
import com.slukhayka.audiobooks.data.metadata.FacetDurationBucket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repository seam (spec-23 T4): the endless merged feed pages through a
 * large synthetic catalogue without gaps or duplicates, and the local facet
 * sets / sort state compose with paging. Pages are pulled directly from
 * the PagingSource (deterministic — no flow-collection timing), which is
 * exactly what Pager does over these sources: `LoadParams.Refresh(key =
 * offset)` returns a page whose `nextKey` is the next offset, looped until
 * exhaustion. Dedup is inherited from merge-on-write — the feed never
 * re-implements it at read time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkFeedPagingTest {

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

    // ADR-0002 (#140): the catalog tests construct the Source Catalog module
    // directly — no god module.
    private fun catalog() = SourceCatalog(dao, emptyList(), LibraryImport(dao, context, emptyList()))

    /** Pulls every page of [source] via Refresh-key loops, mimicking Pager. */
    private suspend fun collectAll(source: PagingSource<Int, WorkFeedRow>): List<WorkFeedRow> {
        val rows = mutableListOf<WorkFeedRow>()
        var key: Int? = null
        do {
            val result = source.load(
                PagingSource.LoadParams.Refresh(key = key, loadSize = 25, placeholdersEnabled = false)
            )
            assertTrue("expected a page, got $result", result is PagingSource.LoadResult.Page)
            val page = result as PagingSource.LoadResult.Page
            rows += page.data
            key = page.nextKey
        } while (key != null)
        return rows
    }

    /** Applies an Edition language facet to one seeded Work. */
    private suspend fun languageOf(
        catalog: SourceCatalog,
        workId: String,
        editionId: String,
        language: String?
    ) {
        catalog.facetWriter.apply(
            listOf(
                LocalFacetDelta(
                    work = WorkFacetDelta(workId),
                    editions = listOf(
                        EditionFacetDelta(
                            editionId = editionId,
                            workId = workId,
                            language = language,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `language filter keeps selected-language and unknown works and hides the rest`() = runBlocking {
        val catalog = catalog()
        // Same author/title works, distinct by title for readable assertions.
        catalog.writeWorkEdition("4read", "Pride and Prejudice", "Jane Austen", "", "https://4read/a", genreTexts = listOf("Фантастика"))
        catalog.writeWorkEdition("4read", "Emma", "Jane Austen", "", "https://4read/b")
        catalog.writeWorkEdition("soundbooks", "Кобзар", "Шевченко", "", "https://sb/a")
        catalog.writeWorkEdition("soundbooks", "Без мови", "Автор", "", "https://sb/unknown")
        val works = dao.observeWorks().first().associateBy { it.title }
        languageOf(catalog, works.getValue("Pride and Prejudice").id, "en-edition", "en")
        languageOf(catalog, works.getValue("Emma").id, "uk-edition", "uk")
        languageOf(catalog, works.getValue("Кобзар").id, "uk-edition-2", "uk")
        // «Без мови» has an Edition facet with an unknown (blank) language.
        languageOf(catalog, works.getValue("Без мови").id, "blank-edition", "")

        // English selection: the en Work, the blank-language Work and the
        // Works with no language signal at all stay; uk-only Works hide.
        assertEquals(
            setOf("Pride and Prejudice", "Без мови"),
            collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(languages = setOf("en")))).map { it.title }.toSet()
        )
        // Empty selection (both content languages on) shows everything.
        assertEquals(4, collectAll(catalog.pagedWorkFeedRecent()).size)
        assertEquals(
            setOf("Pride and Prejudice", "Emma", "Кобзар", "Без мови"),
            collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(languages = setOf("en", "uk")))).map { it.title }.toSet()
        )
        // A Work carrying BOTH an en and a uk Edition shows under either
        // selection — Pride was hidden under {uk} before its uk Edition arrived.
        languageOf(catalog, works.getValue("Pride and Prejudice").id, "en-edition-2", "uk")
        assertEquals(
            setOf("Pride and Prejudice", "Emma", "Кобзар", "Без мови"),
            collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(languages = setOf("uk")))).map { it.title }.toSet()
        )
        // ... and its en facet is still there: {en} still shows Pride.
        assertEquals(
            setOf("Pride and Prejudice", "Без мови"),
            collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(languages = setOf("en")))).map { it.title }.toSet()
        )
    }

    @Test
    fun `a work mixing a known and an unknown edition stays visible under any selection (R4)`() = runBlocking {
        val catalog = catalog()
        catalog.writeWorkEdition("4read", "Mixed Facet", "Автор А", "", "https://4read/mf")
        catalog.writeWorkEdition("4read", "English Only", "Автор Б", "", "https://4read/eo")
        val works = dao.observeWorks().first().associateBy { it.title }
        // FACET-signal mix: an en facet AND a blank facet on the SAME Work.
        languageOf(catalog, works.getValue("Mixed Facet").id, "mf-en", "en")
        languageOf(catalog, works.getValue("Mixed Facet").id, "mf-blank", "")
        languageOf(catalog, works.getValue("English Only").id, "eo-en", "en")

        // R4 (#511): a Work with ANY unknown-language Edition stays visible
        // under a uk-only selection (US17) — the unknown member is neutral,
        // never hidden by a known sibling and never hiding one.
        assertEquals(
            setOf("Mixed Facet"),
            collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(languages = setOf("uk")))).map { it.title }.toSet()
        )
        // The same rule on the title-sorted feed and on later pages (Refresh
        // with a key beyond the first page exercises the paging cursor).
        assertEquals(
            setOf("Mixed Facet"),
            collectAll(catalog.pagedWorkFeedByTitle(WorkFacetFilter(languages = setOf("uk")))).map { it.title }.toSet()
        )
        // Under an en selection BOTH works show: English Only has a known en
        // signal, Mixed Facet has one too (plus its neutral blank member).
        assertEquals(
            setOf("English Only", "Mixed Facet"),
            collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(languages = setOf("en")))).map { it.title }.toSet()
        )
        // Empty selection stays inactive — everything returns.
        assertEquals(2, collectAll(catalog.pagedWorkFeedRecent()).size)
    }

    @Test
    fun `a work mixing known and unknown EDITION rows follows the same rule (R4)`() = runBlocking {
        val catalog = catalog()
        catalog.writeWorkEdition("4read", "Mixed Rows", "Автор В", "", "https://4read/mr")
        val workId = dao.observeWorks().first().single { it.title == "Mixed Rows" }.id
        // The import write path anchors editions on the library ENTRY; the
        // feed bridges through library_entries.workId to the Works row.
        dao.upsertLibraryEntry(
            id = workId, workId = workId, isFavorite = false,
            createdAt = System.currentTimeMillis(), downloadProgress = 0f
        )
        dao.insertEdition(
            EditionEntity(
                id = "mr-en", workId = workId, narrator = "Narrator A", language = "en",
                totalChapters = 0, totalDurationSeconds = 0L
            )
        )
        dao.insertEdition(
            EditionEntity(
                id = "mr-unknown", workId = workId, narrator = "Narrator A", language = "",
                totalChapters = 0, totalDurationSeconds = 0L
            )
        )

        // uk selection: the en row is hidden, the unknown row keeps the Work.
        assertEquals(
            listOf("Mixed Rows"),
            collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(languages = setOf("uk")))).map { it.title }
        )
        assertEquals(
            listOf("Mixed Rows"),
            collectAll(catalog.pagedWorkFeedByTitle(WorkFacetFilter(languages = setOf("uk")))).map { it.title }
        )
    }

    @Test
    fun `the unknown-stays rule composes AND with a genre dimension (R4)`() = runBlocking {
        val catalog = catalog()
        catalog.writeWorkEdition("4read", "Mixed Fantasy", "Автор А", "", "https://4read/xf", genreTexts = listOf("Фентезі"))
        catalog.writeWorkEdition("4read", "English Fantasy", "Автор Б", "", "https://4read/ef", genreTexts = listOf("Фентезі"))
        val works = dao.observeWorks().first().associateBy { it.title }
        languageOf(catalog, works.getValue("Mixed Fantasy").id, "xf-en", "en")
        languageOf(catalog, works.getValue("Mixed Fantasy").id, "xf-blank", "")
        languageOf(catalog, works.getValue("English Fantasy").id, "ef-en", "en")

        // Genre AND language: the ORs live INSIDE the language dimension and
        // the genre dimension still composes with AND.
        val filter = WorkFacetFilter(genreIds = setOf("fantasy"), languages = setOf("uk"))
        assertEquals(
            listOf("Mixed Fantasy"),
            collectAll(catalog.pagedWorkFeedByTitle(filter)).map { it.title }
        )
        assertEquals(
            listOf("Mixed Fantasy"),
            collectAll(catalog.pagedWorkFeedRecent(filter)).map { it.title }
        )
    }

    @Test
    fun `language filter composes AND with a genre dimension`() = runBlocking {
        val catalog = catalog()
        catalog.writeWorkEdition("4read", "English Fantasy", "Автор А", "", "https://4read/ef", genreTexts = listOf("Фентезі"))
        catalog.writeWorkEdition("4read", "English Detective", "Автор Б", "", "https://4read/ed", genreTexts = listOf("Детектив"))
        val works = dao.observeWorks().first().associateBy { it.title }
        languageOf(catalog, works.getValue("English Fantasy").id, "ef-en", "en")
        languageOf(catalog, works.getValue("English Detective").id, "ed-en", "en")

        val englishFantasy = WorkFacetFilter(genreIds = setOf("fantasy"), languages = setOf("en"))

        assertEquals(
            listOf("English Fantasy"),
            collectAll(catalog.pagedWorkFeedByTitle(englishFantasy)).map { it.title }
        )
        // The genre alone (no language selection) still shows the detective book.
        assertEquals(
            setOf("English Fantasy", "English Detective"),
            collectAll(catalog.pagedWorkFeedByTitle(WorkFacetFilter(genreIds = setOf("fantasy", "detective")))).map { it.title }.toSet()
        )
    }

    @Test
    fun `feed pages through a large synthetic catalogue without gaps or duplicates`() = runBlocking {
        val catalog = catalog()
        val count = 500
        for (i in 0 until count) {
            catalog.writeWorkEdition(
                sourceId = "4read",
                title = "Книга $i",
                author = "Автор ${i % 50}",
                narrator = "",
                sourceUrl = "https://4read.org/book-$i.html"
            )
        }
        assertEquals(count, dao.countWorks())

        val rows = collectAll(catalog.pagedWorkFeedRecent())

        // Every synthetic book present exactly once — no gaps, no duplicates.
        assertEquals(count, rows.size)
        assertEquals(count, rows.map { it.workId }.toSet().size)
        assertEquals(count, rows.map { it.title }.toSet().size)
    }

    @Test
    fun `no facet filter includes Works from every Source`() = runBlocking {
        val catalog = catalog()
        val fourRead = 100
        val sluhay = 100
        for (i in 0 until fourRead) {
            catalog.writeWorkEdition(
                sourceId = "4read",
                title = "4read-книга $i",
                author = "Автор $i",
                narrator = "",
                sourceUrl = "https://4read.org/b$i.html"
            )
        }
        for (i in 0 until sluhay) {
            catalog.writeWorkEdition(
                sourceId = "sluhay",
                title = "Sluhay-книга $i",
                author = "Автор $i",
                narrator = "",
                sourceUrl = "https://sluhay.com/b$i.html"
            )
        }
        assertEquals(fourRead + sluhay, dao.countWorks())

        val rows = collectAll(catalog.pagedWorkFeedRecent())

        assertEquals(fourRead + sluhay, rows.size)
    }

    @Test
    fun `single and multi genre filters use indexed OR and empty selection means all`() = runBlocking {
        val catalog = catalog()
        catalog.writeWorkEdition("4read", "Дюна", "Френк Герберт", "", "https://4read/dune", genreTexts = listOf("Фантастика"))
        catalog.writeWorkEdition("4read", "Відьмак", "Анджей Сапковський", "", "https://4read/witcher", genreTexts = listOf("Фентезі"))
        catalog.writeWorkEdition("sluhay", "Пасажир", "Жан-Крістоф Гранже", "", "https://sluhay/passenger", genreTexts = listOf("Детектив"))
        catalog.writeWorkEdition("sluhay", "Без жанру", "Автор", "", "https://sluhay/unknown")

        assertEquals(listOf("Дюна"), collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(setOf("science-fiction")))).map { it.title })
        assertEquals(
            setOf("Дюна", "Відьмак"),
            collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(setOf("science-fiction", "fantasy")))).map { it.title }.toSet()
        )
        assertEquals(4, collectAll(catalog.pagedWorkFeedRecent()).size)
        assertTrue(collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(setOf("poetry")))).isEmpty())
    }

    @Test
    fun `duration OR composes with genre AND while one Work carries an honest Edition range`() = runBlocking {
        val catalog = catalog()
        catalog.writeWorkEdition("4read", "Коротке фентезі", "Автор А", "", "https://4read/short-fantasy", genreTexts = listOf("Фентезі"))
        catalog.writeWorkEdition("4read", "Середнє фентезі", "Автор Б", "", "https://4read/medium-fantasy", genreTexts = listOf("Фентезі"))
        catalog.writeWorkEdition("4read", "Невідоме фентезі", "Автор В", "", "https://4read/unknown-fantasy", genreTexts = listOf("Фентезі"))
        catalog.writeWorkEdition("4read", "Короткий детектив", "Автор Г", "", "https://4read/short-detective", genreTexts = listOf("Детектив"))
        val works = dao.observeWorks().first().associateBy { it.title }

        suspend fun durations(title: String, vararg values: Pair<String, Long>) {
            val workId = works.getValue(title).id
            catalog.facetWriter.apply(
                listOf(
                    LocalFacetDelta(
                        work = WorkFacetDelta(workId),
                        editions = values.map { (editionId, seconds) ->
                            EditionFacetDelta(
                                editionId = editionId,
                                workId = workId,
                                durationSeconds = seconds,
                                updatedAt = seconds
                            )
                        }
                    )
                )
            )
        }
        durations("Коротке фентезі", "short-edition" to 10_800L, "long-edition" to 43_200L)
        durations("Середнє фентезі", "medium-edition" to 21_600L)
        durations("Короткий детектив", "detective-edition" to 14_399L)

        val durationOr = WorkFacetFilter(
            durationBucketIds = setOf(
                FacetDurationBucket.UNDER_FIVE_HOURS.wireName,
                FacetDurationBucket.FIVE_TO_TEN_HOURS.wireName
            )
        )
        assertEquals(
            setOf("Коротке фентезі", "Середнє фентезі", "Короткий детектив"),
            collectAll(catalog.pagedWorkFeedRecent(durationOr)).map { it.title }.toSet()
        )

        val fantasyAndShort = WorkFacetFilter(
            genreIds = setOf("fantasy"),
            durationBucketIds = setOf(FacetDurationBucket.UNDER_FIVE_HOURS.wireName)
        )
        val rows = collectAll(catalog.pagedWorkFeedRecent(fantasyAndShort))
        assertEquals(1, rows.size)
        assertEquals("Коротке фентезі", rows.single().title)
        assertEquals(10_800L, rows.single().durationSeconds)
        assertEquals(43_200L, rows.single().durationMaxSeconds)
        assertEquals("short-edition", rows.single().matchingEditionId)

        assertTrue(collectAll(catalog.pagedWorkFeedRecent(durationOr)).none { it.title == "Невідоме фентезі" })
        assertTrue(collectAll(catalog.pagedWorkFeedRecent()).any { it.title == "Невідоме фентезі" })
    }

    @Test
    fun `duration-filtered Work resolves an already imported matching Edition first`() = runBlocking {
        val catalog = catalog()
        catalog.writeWorkEdition("4read", "Дві начитки", "Автор", "", "https://4read/two-editions")
        val work = dao.observeWorks().first().single()

        suspend fun insertLibraryEdition(bookId: String, editionId: String, narrator: String, seconds: Long) {
            dao.insertAudiobooks(
                listOf(
                    AudiobookEntity(
                        id = bookId, title = work.title, author = work.author, narrator = narrator,
                        description = "", coverDrawableRes = 0, genre = "", sourceUrl = "https://example/$bookId",
                        totalDurationSeconds = seconds
                    )
                )
            )
            dao.upsertLibraryEntry(bookId, work.id, false, 0L, 0f)
            dao.insertEdition(EditionEntity(editionId, bookId, narrator = narrator, totalDurationSeconds = seconds))
        }
        insertLibraryEdition("book-short", "edition-short", "Коротка начитка", 10_800L)
        insertLibraryEdition("book-long", "edition-long", "Довга начитка", 43_200L)
        catalog.facetWriter.apply(
            listOf(
                LocalFacetDelta(
                    work = WorkFacetDelta(work.id),
                    editions = listOf(
                        EditionFacetDelta("edition-short", work.id, durationSeconds = 10_800L, updatedAt = 1),
                        EditionFacetDelta("edition-long", work.id, durationSeconds = 43_200L, updatedAt = 2)
                    )
                )
            )
        )

        val row = collectAll(
            catalog.pagedWorkFeedRecent(
                WorkFacetFilter(
                    durationBucketIds = setOf(FacetDurationBucket.TEN_TO_TWENTY_HOURS.wireName)
                )
            )
        ).single()

        assertEquals("edition-long", row.matchingEditionId)
        assertEquals("book-long", catalog.libraryBookForEdition(row.matchingEditionId)?.id)
    }

    @Test
    fun `duration filter prioritizes a matching browse Source when no Edition is imported`() = runBlocking {
        val catalog = catalog()
        val short = catalog.writeWorkEdition(
            sourceId = "4read",
            title = "Дюна",
            author = "Френк Герберт",
            narrator = "Коротка начитка",
            sourceUrl = "https://4read/dune-short",
            durationSeconds = 10_800L
        )
        catalog.writeWorkEdition(
            sourceId = "sluhay",
            title = "Дюна",
            author = "Френк Герберт",
            narrator = "Довга начитка",
            sourceUrl = "https://sluhay/dune-long",
            durationSeconds = 43_200L
        )

        val sources = catalog.workSourcesForWork(
            workId = short.work.id,
            preferredDurationBucketIds = setOf(FacetDurationBucket.TEN_TO_TWENTY_HOURS.wireName)
        )

        assertEquals("sluhay", sources.first().sourceId)
    }

    @Test
    fun `duration filter keeps Work stable across fresh stale and negative availability`() = runBlocking {
        val catalog = catalog()
        listOf("Свіжа", "Протермінована", "Недоступна", "Без спостереження").forEachIndexed { index, title ->
            catalog.writeWorkEdition("4read", title, "Автор", "", "https://4read/availability-$index")
        }
        val works = dao.observeWorks().first().associateBy { it.title }
        suspend fun facet(title: String, available: Boolean?, observedAt: Long?, ttlSeconds: Long?) {
            val workId = works.getValue(title).id
            catalog.facetWriter.apply(
                listOf(
                    LocalFacetDelta(
                        work = WorkFacetDelta(workId),
                        editions = listOf(
                            EditionFacetDelta(
                                editionId = "edition-$title",
                                workId = workId,
                                durationSeconds = 3_600L,
                                availabilityAvailable = available,
                                availabilityObservedAtMillis = observedAt,
                                availabilityTtlSeconds = ttlSeconds,
                                updatedAt = 1
                            )
                        )
                    )
                )
            )
        }
        facet("Свіжа", available = true, observedAt = 1_001L, ttlSeconds = 9L)
        facet("Протермінована", available = true, observedAt = 1_000L, ttlSeconds = 9L)
        facet("Недоступна", available = false, observedAt = 9_999L, ttlSeconds = 900L)
        facet("Без спостереження", available = null, observedAt = null, ttlSeconds = null)

        val rows = collectAll(
            catalog.pagedWorkFeedRecent(
                filter = WorkFacetFilter(
                    durationBucketIds = setOf(FacetDurationBucket.UNDER_FIVE_HOURS.wireName)
                )
            )
        )

        assertEquals(
            setOf("Свіжа", "Протермінована", "Недоступна", "Без спостереження"),
            rows.map { it.title }.toSet()
        )
    }

    @Test
    fun `facet sync under active filter keeps the filter and one card per Work`() = runBlocking {
        val catalog = catalog()
        catalog.writeWorkEdition("4read", "Дюна", "Френк Герберт", "", "https://4read/dune", genreTexts = listOf("Фантастика"))
        catalog.writeWorkEdition("sluhay", "Дюна", "Френк Герберт", "", "https://sluhay/dune", genreTexts = listOf("Фентезі"))
        val filter = WorkFacetFilter(setOf("science-fiction"))

        assertEquals(listOf("Дюна"), collectAll(catalog.pagedWorkFeedRecent(filter)).map { it.title })
        val work = dao.observeWorks().first().single()
        catalog.facetWriter.apply(
            listOf(LocalFacetDelta(WorkFacetDelta(work.id, listOf(GenreFacetAssertion("Фантастика", "sync", 5)))))
        )

        val afterSync = collectAll(catalog.pagedWorkFeedRecent(filter))
        assertEquals(1, afterSync.size)
        assertEquals(work.id, afterSync.single().workId)
    }

    @Test
    fun `newer Source genre replacement removes only that Source and never duplicates a Work`() = runBlocking {
        val catalog = catalog()
        catalog.writeWorkEdition("4read", "Дюна", "Френк Герберт", "", "https://4read/dune", genreTexts = listOf("Фантастика"))
        catalog.writeWorkEdition("sluhay", "Дюна", "Френк Герберт", "", "https://sluhay/dune", genreTexts = listOf("Фентезі"))
        val work = dao.observeWorks().first().single()

        catalog.facetWriter.apply(
            listOf(
                LocalFacetDelta(
                    WorkFacetDelta(
                        workId = work.id,
                        genreSourceReplacements = listOf(
                            GenreSourceFacetReplacement(
                                sourceId = "4read",
                                documentUpdatedAt = Long.MAX_VALUE,
                                assertions = emptyList()
                            )
                        )
                    )
                )
            )
        )

        assertTrue(collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(setOf("science-fiction")))).isEmpty())
        val remaining = collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(setOf("fantasy"))))
        assertEquals(1, remaining.size)
        assertEquals(work.id, remaining.single().workId)
    }

    @Test
    fun `catalogue refresh with an explicit empty genre set clears that Source`() = runBlocking {
        val catalog = catalog()
        catalog.writeWorkEdition("4read", "Дюна", "Френк Герберт", "", "https://4read/dune")
        val work = dao.observeWorks().first().single()
        catalog.facetWriter.apply(
            listOf(
                LocalFacetDelta(
                    WorkFacetDelta(
                        workId = work.id,
                        genres = listOf(
                            GenreFacetAssertion(
                                rawText = "Фантастика",
                                sourceId = "4read",
                                observedAt = 1,
                                documentUpdatedAt = 1
                            )
                        )
                    )
                )
            )
        )
        assertEquals(1, collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(setOf("science-fiction")))).size)

        catalog.writeWorkEdition(
            "4read", "Дюна", "Френк Герберт", "", "https://4read/dune", genreTexts = emptyList()
        )

        assertTrue(collectAll(catalog.pagedWorkFeedRecent(WorkFacetFilter(setOf("science-fiction")))).isEmpty())
    }

    @Test
    fun `title sort returns Works ordered by title and the module accessor serves the same feed`() = runBlocking {
        val catalog = catalog()
        val titles = listOf("Зебра", "атом", "Яблуко", "Море", "Вітер")
        titles.forEachIndexed { i, title ->
            catalog.writeWorkEdition(
                sourceId = "4read",
                title = title,
                author = "Автор",
                narrator = "",
                sourceUrl = "https://4read.org/sort-$i.html"
            )
        }

        val rows = collectAll(catalog.pagedWorkFeedByTitle())

        assertEquals(titles.size, rows.size)
        // SQLite COLLATE NOCASE is ASCII-only: it does NOT fold Cyrillic
        // case, so uppercase Cyrillic (U+0410–U+042F) sorts before lowercase
        // (U+0430–U+044F). Pin the real ordering contract — that is exactly
        // what the app's title-sorted feed shows.
        assertEquals(
            listOf("Вітер", "Зебра", "Море", "Яблуко", "атом"),
            rows.map { it.title }
        )
        // The recent-first feed also serves the same rows (different order),
        // and the module accessor is the same PagingSource the Pager uses.
        val recent = collectAll(catalog.pagedWorkFeedRecent())
        assertEquals(titles.size, recent.map { it.workId }.toSet().size)
    }

    @Test
    fun `feed row carries its Work's duration from the Edition`() = runBlocking {
        // Spec-24 T1: the feed card shows the full book duration, and the
        // duration is the Edition's listening total (ADR-0010) — joined for
        // Works whose library copy has an Edition; null for browse-only Works.
        val catalog = catalog()
        catalog.writeWorkEdition(
            sourceId = "4read",
            title = "Пасажир",
            author = "Жан-Крістоф Гранже",
            narrator = "",
            sourceUrl = "https://4read.org/pasazhir.html"
        )
        catalog.writeWorkEdition(
            sourceId = "sluhay",
            title = "Інша книга",
            author = "Інший автор",
            narrator = "",
            sourceUrl = "https://sluhay.com/insha.html"
        )

        // Link «Пасажир» into the library and give its Edition a real total
        // — the same shape importBookFromSource produces.
        val work = dao.observeWorks().first().first { it.title == "Пасажир" }
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = "lib-pasazhir",
                    title = work.title,
                    author = work.author,
                    narrator = "",
                    description = "",
                    coverDrawableRes = 0,
                    coverImageUrl = null,
                    genre = "Детектив",
                    sourceUrl = "https://4read.org/pasazhir.html",
                    isDownloaded = false,
                    totalDurationSeconds = 60_061L,
                    totalChapters = 0,
                    rating = 0f
                )
            )
        )
        dao.upsertLibraryEntry(
            id = "lib-pasazhir",
            workId = work.id,
            isFavorite = false,
            createdAt = 0L,
            downloadProgress = 0f
        )
        dao.insertEdition(
            EditionEntity(
                id = "ed-pasazhir",
                workId = "lib-pasazhir",
                narrator = "",
                totalChapters = 0,
                totalDurationSeconds = 60_061L
            )
        )
        // A sibling rendition has its own library/Edition rows but must not
        // multiply the bibliographic Work card.
        dao.insertAudiobooks(
            listOf(
                AudiobookEntity(
                    id = "lib-pasazhir-2", title = work.title, author = work.author,
                    narrator = "Інший диктор", description = "", coverDrawableRes = 0,
                    genre = "Детектив", sourceUrl = "https://sluhay/pasazhir.html"
                )
            )
        )
        dao.upsertLibraryEntry("lib-pasazhir-2", work.id, false, 0L, 0f)
        dao.insertEdition(EditionEntity("ed-pasazhir-2", "lib-pasazhir-2", narrator = "Інший диктор", totalDurationSeconds = 61_000L))

        val rows = collectAll(catalog.pagedWorkFeedRecent())

        val pasazhir = rows.first { it.title == "Пасажир" }
        val insha = rows.first { it.title == "Інша книга" }
        assertEquals(1, rows.count { it.title == "Пасажир" })
        // The linked Work carries its Edition's total; the browse-only Work
        // has no Edition yet — null, never a fabricated duration.
        assertEquals(60_061L, pasazhir.durationSeconds)
        assertNull(insha.durationSeconds)
    }

    @Test
    fun `feed row carries the source count for the sources badge`() = runBlocking {
        val catalog = catalog()
        catalog.writeWorkEdition(
            sourceId = "4read",
            title = "Пасажир",
            author = "Жан-Крістоф Гранже",
            narrator = "",
            sourceUrl = "https://4read.org/pasazhir.html"
        )
        catalog.writeWorkEdition(
            sourceId = "sluhay",
            title = "Пасажир",
            author = "Жан-Крістоф Гранже",
            narrator = "",
            sourceUrl = "https://sluhay.com/pasazhir.html"
        )

        val rows = collectAll(catalog.pagedWorkFeedRecent())

        assertEquals(1, rows.size)
        // Two sources carry one Work — the «2 джерела» badge input
        // (ADR-0007: counted over work_sources).
        assertEquals(2, rows.single().sourceCount)
    }
}
