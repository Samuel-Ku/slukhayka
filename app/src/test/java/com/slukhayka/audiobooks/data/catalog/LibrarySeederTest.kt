package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import com.slukhayka.audiobooks.data.catalog.LibrarySeeder.SeedBudget
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
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

    @Test
    fun `a received detail contributes recommendations even without a playable stream`() = runBlocking {
        val url = "https://sluhay.com.ua/100:tyhrolovy"
        val received = SourceBookDetail("Тигролови", "Іван Багряний", url = url,
            chapters = emptyList(), related = listOf(com.slukhayka.audiobooks.data.source.RelatedBook(
                "Місто", "Валер’ян Підмогильний", "https://sluhay.com.ua/102:misto")))
        val requests = mutableListOf<String>()
        val observed = mutableListOf<Pair<String, SourceBookDetail>>()
        var probes = 0
        var imports = 0
        val adapter = object : SourceAdapter {
            override val sourceId = "sluhayua"
            override suspend fun search(query: String): List<SourceBook> = error("no search")
            override suspend fun fetchNew(limit: Int): List<SourceBook> = error("no feed")
            override suspend fun fetchBookPage(url: String): SourceBookDetail {
                requests += url
                return received
            }
        }
        val candidate = directCard.copy(title = received.title, author = received.author,
            mergeKey = "тигролови|багряний", sources = listOf(GlobalSearchSource("sluhayua", "Слухай UA", url)))
        val seeder = LibrarySeeder(candidates = { listOf(candidate) }, adapterFor = { adapter },
            streamProbe = { probes++; error("no unplayable stream probe") }, known = { false },
            import = { _, _ -> imports++; error("no unplayable book import") },
            onSourceDetailObserved = { sourceId, detail -> observed += sourceId to detail })

        val result = seeder.seedOnce()

        assertEquals("already received recommendations are observed before the playable guard", 1, observed.size)
        assertEquals("sluhayua" to received, observed.single())
        assertEquals(listOf(url), requests)
        assertEquals(0, probes)
        assertEquals(0, imports)
        assertEquals(LibrarySeeder.SeedResult(0, 0, 1, 0), result)
    }

    /** Fixture only for the external source/probe/import/observer boundaries. */
    private fun observationPass(
        events: MutableList<String>,
        known: Boolean = false,
        playable: Boolean = true,
        fetchFailure: Exception? = null,
        observed: suspend () -> Unit = {}
    ): LibrarySeeder {
        val url = "https://sluhay.com.ua/100:tyhrolovy"
        val received = detail(url).copy(related = listOf(com.slukhayka.audiobooks.data.source.RelatedBook(
            "Місто", "Валер’ян Підмогильний", "https://sluhay.com.ua/102:misto")))
        val adapter = object : SourceAdapter {
            override val sourceId = "sluhayua"
            override suspend fun search(query: String): List<SourceBook> = error("no search")
            override suspend fun fetchNew(limit: Int): List<SourceBook> = error("no feed")
            override suspend fun fetchBookPage(url: String): SourceBookDetail {
                events += "fetch"
                fetchFailure?.let { throw it }
                return received
            }
        }
        val candidate = directCard.copy(sources = listOf(GlobalSearchSource("sluhayua", "Слухай UA", url)))
        return LibrarySeeder(candidates = { listOf(candidate) }, adapterFor = { adapter }, known = { known },
            streamProbe = { events += "probe"; playable }, import = { _, _ -> events += "import" },
            onSourceDetailObserved = { sourceId, detail ->
                assertEquals("sluhayua", sourceId)
                assertSame(received, detail)
                events += "observe"
                observed()
            })
    }

    @Test
    fun `an unreachable stream still observes the received detail before its one probe`() = runBlocking {
        val events = mutableListOf<String>()
        val result = observationPass(events, playable = false).seedOnce()
        assertEquals(listOf("fetch", "observe", "probe"), events)
        assertEquals(LibrarySeeder.SeedResult(0, 0, 1, 0), result)
    }

    @Test
    fun `a playable seed observes once before its one probe and import`() = runBlocking {
        val events = mutableListOf<String>()
        val result = observationPass(events).seedOnce()
        assertEquals(listOf("fetch", "observe", "probe", "import"), events)
        assertEquals(LibrarySeeder.SeedResult(1, 1, 0, 0), result)
    }

    @Test
    fun `a known seed does not masquerade as an observed source detail`() = runBlocking {
        val events = mutableListOf<String>()
        val result = observationPass(events, known = true).seedOnce()
        assertTrue(events.isEmpty())
        assertEquals(LibrarySeeder.SeedResult(0, 0, 0, 1), result)
    }

    @Test
    fun `a failed source resolve never invokes the observer or probe`() = runBlocking {
        val events = mutableListOf<String>()
        val result = observationPass(events, fetchFailure = IllegalStateException("source down")).seedOnce()
        assertEquals(listOf("fetch"), events)
        assertEquals(LibrarySeeder.SeedResult(0, 0, 1, 0), result)
    }

    @Test
    fun `an ordinary observer failure cannot reject a playable seed`() = runBlocking {
        val events = mutableListOf<String>()
        val result = observationPass(events, observed = { throw IllegalStateException("metadata down") }).seedOnce()
        assertEquals(listOf("fetch", "observe", "probe", "import"), events)
        assertEquals(LibrarySeeder.SeedResult(1, 1, 0, 0), result)
    }

    @Test
    fun `observer cancellation propagates before any probe or import`() {
        val events = mutableListOf<String>()
        val cancelled = CancellationException("metadata cancelled")
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { observationPass(events, observed = { throw cancelled }).seedOnce() }
        }
        assertSame(cancelled, thrown)
        assertEquals(listOf("fetch", "observe"), events)
    }

    @Test
    fun `source cancellation is not converted to a failed seed`() {
        val events = mutableListOf<String>()
        val cancelled = CancellationException("source cancelled")
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { observationPass(events, fetchFailure = cancelled).seedOnce() }
        }
        assertSame(cancelled, thrown)
        assertEquals(listOf("fetch"), events)
    }

    @Test
    fun `external cancellation swallowed by the observer still prevents probe and import`() {
        val events = mutableListOf<String>()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                observationPass(events, observed = { currentCoroutineContext().cancel() }).seedOnce()
            }
        }
        assertEquals(listOf("fetch", "observe"), events)
    }
}
