package com.slukhayka.audiobooks.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.testing.FakeFetcher
import kotlinx.coroutines.flow.first
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
 * Repository seam (spec-23 T2): 4read full-catalog hydration into the
 * persisted Works/Editions layer. The crawl iterates the homepage posters,
 * the sidebar genre categories and the homepage's series pages — not the
 * single default page — and every row lands merge-on-write. Fixtures mirror
 * real 4read markup (the same poster blocks CatalogParserTest uses); the
 * fetcher is faked so no network is touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FourReadHydrationRepositoryTest {

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

    private fun poster(url: String, title: String, author: String) = """
        <div class="poster has-overlay grid-item d-flex fd-column">
            <div class="poster__desc order-last">
                <a href="$url" class="poster__link"><div class="poster__title line-clamp">$title</div></a>
                <div class="poster__subtitle ws-nowrap">$author</div>
            </div>
            <div class="poster__img img-responsive img-responsive--portrait img-fit-cover anim">
                <img src="/uploads/posts/2026-06/medium/x.webp" loading="lazy">
            </div>
        </div>
    """.trimIndent()

    private val seriesUrl = "https://4read.org/xfsearch/cikl/%D0%BC%D0%B0%D0%BA%D1%81%D0%B8%D0%BC%20%D1%82%D0%B5%D0%BC%D0%BD%D0%B8%D0%B9/"

    private val seriesPoster = """
        <div class="poster has-overlay grid-item d-flex fd-column">
            <div class="poster__desc order-last">
                <a href="https://4read.org/7589-neostannij-bij.html" class="poster__link"><div class="poster__title line-clamp">Неостанній бій</div></a>
                <div class="poster__subtitle ws-nowrap">Костянтин Шелест</div>
            </div>
            <div class="poster__img img-responsive img-responsive--portrait img-fit-cover anim">
                <img src="/uploads/posts/2026-05/medium/4_ks_mt7.webp" loading="lazy">
                <div class="poster__label poster__label--blue">7</div>
                <div class="poster__series anim"><a href="$seriesUrl">Максим Темний</a></div>
            </div>
        </div>
    """.trimIndent()

    private val homepage = """
        <html><body>
            <ul class="sb__content sb__nav">
                <li><a href="/kazka/">Казка</a></li>
                <li><a href="/fentezi/">Фентезі</a></li>
            </ul>
            ${poster("https://4read.org/7611-vkradi-mene-zaraz.html", "Вкради мене... Зараз!", "Сергій Оріанець")}
            $seriesPoster
        </body></html>
    """.trimIndent()

    private val kazkaPage = """
        <html><body>
            ${poster("https://4read.org/1234-charivna-kazka.html", "Чарівна казка", "Олена Пчілка")}
            ${poster("https://4read.org/7611-vkradi-mene-zaraz.html", "Вкради мене... Зараз!", "Сергій Оріанець")}
        </body></html>
    """.trimIndent()

    private val fenteziPage = """
        <html><body>
            ${poster("https://4read.org/5678-magichnyj-svit.html", "Магічний світ", "Наталя Довгопола")}
        </body></html>
    """.trimIndent()

    private val seriesPage = """
        <html><body>
            ${poster("https://4read.org/9999-nastupnyj-bij.html", "Наступний бій", "Костянтин Шелест")}
        </body></html>
    """.trimIndent()

    private fun catalog(pages: Map<String, String>) = SourceCatalog(
        dao,
        emptyList(),
        LibraryImport(dao, context, emptyList()),
        fourReadFetcher = FakeFetcher(pages)
    )

    @Test
    fun `hydration iterates homepage categories and series pages into works and editions`() = runBlocking {
        val catalog = catalog(
            mapOf(
                "https://4read.org/" to homepage,
                "https://4read.org/kazka/" to kazkaPage,
                "https://4read.org/fentezi/" to fenteziPage,
                seriesUrl to seriesPage
            )
        )

        val result = catalog.hydrateFourReadCatalog()

        // Homepage 2 (one re-listed on kazka is deduped by URL) + kazka 1 new
        // + fentezi 1 + series 1 = 5 distinct books.
        assertEquals(5, result.found)
        assertEquals(5, result.imported)
        assertEquals(0, result.merged)
        assertEquals(0, result.failed)
        assertEquals("4read", result.sourceId)

        assertEquals(5, dao.countWorks())
        // ADR-0007: the persisted browse rows are work_sources.
        assertEquals(5, dao.countWorkSources())
        val works = dao.observeWorks().first()
        assertEquals(setOf("Вкради мене... Зараз!", "Неостанній бій", "Чарівна казка", "Магічний світ", "Наступний бій"),
            works.map { it.title }.toSet())
        // Every source row carries the 4read source and its real policy: 4read
        // is downloadable, so streamOnly is false — added Works are playable.
        for (work in works) {
            val sources = dao.getWorkSourcesForWorkSync(work.id)
            assertEquals(1, sources.size)
            assertEquals("4read", sources.single().sourceId)
            assertEquals(false, sources.single().streamOnly)
        }
        // Series metadata from the homepage poster survived into the Work.
        val neostannij = works.first { it.title == "Неостанній бій" }
        assertEquals("Максим Темний", neostannij.seriesTitle)
    }

    @Test
    fun `re-running hydration is incremental and idempotent`() = runBlocking {
        val catalog = catalog(
            mapOf(
                "https://4read.org/" to homepage,
                "https://4read.org/kazka/" to kazkaPage,
                "https://4read.org/fentezi/" to fenteziPage,
                seriesUrl to seriesPage
            )
        )

        catalog.hydrateFourReadCatalog()
        val second = catalog.hydrateFourReadCatalog()

        // Nothing new on the re-run: every book merged into its known Work.
        assertEquals(5, second.found)
        assertEquals(0, second.imported)
        assertEquals(5, second.merged)
        assertEquals(0, second.failed)
        // No duplicates accumulated.
        assertEquals(5, dao.countWorks())
        assertEquals(5, dao.countWorkSources())
    }

    @Test
    fun `a failing category page counts as failed without aborting the crawl`() = runBlocking {
        // fentezi page is missing from the fixture -> blank fetch -> failed.
        val catalog = catalog(
            mapOf(
                "https://4read.org/" to homepage,
                "https://4read.org/kazka/" to kazkaPage,
                seriesUrl to seriesPage
            )
        )

        val result = catalog.hydrateFourReadCatalog()

        assertTrue("the missing category page must count as failed", result.failed >= 1)
        // The rest of the crawl still landed.
        assertTrue("books from reachable pages must land", dao.countWorks() >= 4)
        assertTrue("works are playable (not stream-only)", dao.countWorkSources() >= 4)
    }
}
