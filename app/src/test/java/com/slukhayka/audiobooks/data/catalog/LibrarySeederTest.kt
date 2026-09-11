package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import com.slukhayka.audiobooks.data.catalog.LibrarySeeder.SeedBudget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Авто-сід медіатеки (spec `2026-09-10-remove-4read-source`, рішення
 * «авто-сід із перевіркою»): каталог → резолв сторінки → preflight першого
 * треку → імпорт крізь звичайні двері. Книжка рахується робочою лише після
 * перевіреного стріма; бюджет обмежує прохід; послідовні провали зупиняють.
 * Pure JVM: усі залежності — шви-ламбди.
 */
class LibrarySeederTest {

    private val directCard = GlobalSearchResult(
        title = "Кобзар",
        author = "Тарас Шевченко",
        mergeKey = "кобзар|шевченко",
        sources = listOf(GlobalSearchSource("soundbooks", "Sound-Books", "https://sound-books.net/kobzar"))
    )

    private val browserCard = GlobalSearchResult(
        title = "Тільки в браузері",
        author = "Хтось",
        mergeKey = "браузер|автор",
        sources = listOf(GlobalSearchSource("sluhay", "Sluhay", "https://sluhay.com/x"))
    )

    private val resolvedAt = mutableListOf<String>()
    private val probedAt = mutableListOf<String>()
    private val importedAt = mutableListOf<String>()
    private var probeAnswer = true

    private fun reset() {
        resolvedAt.clear(); probedAt.clear(); importedAt.clear()
        probeAnswer = true
    }

    private fun detail(url: String, stream: String? = "https://cdn/x.mp3") = SourceBookDetail(
        title = "Кобзар",
        author = "Тарас Шевченко",
        url = url,
        chapters = if (stream == null) emptyList() else listOf(SourceChapter("1", stream))
    )

    private fun fakeAdapter(sourceId: String, chapters: List<SourceChapter>) = object : SourceAdapter {
        override val sourceId = sourceId
        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): SourceBookDetail {
            resolvedAt.add(url)
            return SourceBookDetail(
                title = "Кобзар",
                author = "Тарас Шевченко",
                url = url,
                chapters = chapters
            )
        }
    }

    private fun seeder(cards: List<GlobalSearchResult>) = LibrarySeeder(
        candidates = { cards },
        adapterFor = { sourceId -> fakeAdapter(sourceId, detail("https://sound-books.net/kobzar").chapters) },
        streamProbe = { url -> probedAt.add(url); probeAnswer },
        known = { false },
        import = { sourceId, _ -> importedAt.add(sourceId) }
    )

    @Test
    fun `a verified stream is imported`() = runBlocking {
        reset()

        val result = seeder(listOf(directCard)).seedOnce()

        assertEquals(1, result.imported)
        assertEquals(listOf("https://sound-books.net/kobzar"), resolvedAt)
        // The proof is the stream probe, before the import.
        assertEquals(listOf("https://cdn/x.mp3"), probedAt)
        assertEquals(listOf("soundbooks"), importedAt)
    }

    @Test
    fun `a failed stream probe never imports`() = runBlocking {
        reset()
        probeAnswer = false

        val result = seeder(listOf(directCard)).seedOnce()

        assertEquals(0, result.imported)
        assertTrue(importedAt.isEmpty())
        // The probe ran — the verdict is honest, not skipped.
        assertTrue(probedAt.isNotEmpty())
    }

    @Test
    fun `works already in the library are skipped without requests`() = runBlocking {
        reset()
        var knownChecked: String? = null
        val s = LibrarySeeder(
            candidates = { listOf(directCard) },
            adapterFor = { null },
            streamProbe = { true },
            known = { key -> knownChecked = key; true },
            import = { _, _ -> importedAt.add("x") }
        )

        val result = s.seedOnce()

        assertEquals(0, result.imported)
        assertEquals("кобзар|шевченко", knownChecked)
        assertTrue(resolvedAt.isEmpty())
        assertTrue(probedAt.isEmpty())
    }

    @Test
    fun `browser-only cards are skipped without requests`() = runBlocking {
        reset()

        val result = seeder(listOf(browserCard)).seedOnce()

        assertEquals(0, result.imported)
        assertTrue(resolvedAt.isEmpty())
        assertTrue(probedAt.isEmpty())
    }

    @Test
    fun `the run is bounded by maxBooks`() = runBlocking {
        reset()
        val cards = (1..10).map { i -> directCard.copy(mergeKey = "mk-$i", title = "Книга $i") }

        val result = seeder(cards).seedOnce(budget = SeedBudget(maxBooks = 3))

        assertEquals(3, result.imported)
        assertEquals(3, importedAt.size)
    }

    @Test
    fun `consecutive failures stop the pass`() = runBlocking {
        reset()
        probeAnswer = false
        val cards = (1..10).map { i -> directCard.copy(mergeKey = "mk-$i", title = "Книга $i") }

        val result = seeder(cards).seedOnce(budget = SeedBudget(maxBooks = 10, maxConsecutiveFailures = 3))

        assertEquals(0, result.imported)
        // Stopped after 3 probed failures, not 10.
        assertEquals(3, probedAt.size)
    }

    @Test
    fun `a page without chapters is a failure, never an import`() = runBlocking {
        reset()
        val s = LibrarySeeder(
            candidates = { listOf(directCard) },
            adapterFor = { sourceId -> fakeAdapter(sourceId, emptyList()) },
            streamProbe = { true },
            known = { false },
            import = { _, _ -> importedAt.add("x") }
        )

        val result = s.seedOnce()

        assertEquals(0, result.imported)
        assertTrue(importedAt.isEmpty())
        assertTrue(resolvedAt.isNotEmpty())
    }
}
