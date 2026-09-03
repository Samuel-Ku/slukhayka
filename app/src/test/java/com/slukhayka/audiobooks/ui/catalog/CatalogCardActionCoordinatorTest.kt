package com.slukhayka.audiobooks.ui.catalog

import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.source.SourceSelectionCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogCardActionCoordinatorTest {

    private val successfulProbe = SourceSelectionCoordinator.SourceProbe { _, _ ->
        SourceSelectionCoordinator.ProbeResult.Success
    }

    private fun directSource(id: String = "soundbooks-ed-1") =
        SourceSelectionCoordinator.SourceCandidate(
            source = SourceEntity(
                id = id,
                bookId = "work-1",
                editionId = "edition-1",
                type = "soundbooks",
                url = "https://sound-books.net/book-1",
                streamOnly = false,
                addedAt = 1L
            ),
            category = SourceSelectionCoordinator.SourceCategory.DIRECT
        )

    /** The only candidate of a 4read-only card: the browser door (#469). */
    private fun browserSource(id: String = "4read-ed-1") =
        SourceSelectionCoordinator.SourceCandidate(
            source = SourceEntity(
                id = id,
                bookId = "work-1",
                editionId = "edition-1",
                type = "4read",
                url = "https://4read.org/book-1",
                streamOnly = true,
                addedAt = 1L
            ),
            category = SourceSelectionCoordinator.SourceCategory.BROWSER
        )

    /** The direct sluhayua counterpart a successful cross-resolve returns. */
    private fun sluhayuaCrossSource() = SourceEntity(
        id = "sluhayua-cross",
        bookId = "",
        type = "sluhayua",
        url = "https://sluhay.com.ua/42:knyzhka",
        streamOnly = false,
        addedAt = 1L
    )

    @Test
    fun `only explicitly matching Edition Sources are eligible for automatic fallback`() {
        val sources = listOf(
            CatalogCardSource("soundbooks", "https://sound-books.net/a", editionId = "edition-a"),
            CatalogCardSource("audiobookmp3", "https://audiobook-mp3.com/a", editionId = "edition-a"),
            CatalogCardSource("lihtar", "https://lihtar.in.ua/b", editionId = "edition-b")
        )

        assertEquals(
            listOf("soundbooks", "audiobookmp3"),
            editionScopedCatalogSources("edition-a", sources).map { it.sourceId }
        )
    }

    @Test
    fun `unasserted browse Sources never imply a cross narration fallback`() {
        val sources = listOf(
            CatalogCardSource("soundbooks", "https://sound-books.net/a"),
            CatalogCardSource("lihtar", "https://lihtar.in.ua/a")
        )

        assertEquals(listOf("soundbooks"), editionScopedCatalogSources(null, sources).map { it.sourceId })
    }

    @Test
    fun `open resolves a Source and opens details without starting playback`() = runTest {
        val effects = mutableListOf<String>()
        val gateway = object : CatalogCardActionGateway<String> {
            override suspend fun savedBook(target: CatalogCardTarget): String? = null
            override suspend fun sourceCandidates(target: CatalogCardTarget) = listOf(directSource())
            override suspend fun import(target: CatalogCardTarget, source: SourceEntity): String? = "book-1"
            override suspend fun open(book: String): Boolean {
                effects += "open:$book"
                return true
            }
            override suspend fun play(book: String, source: SourceEntity?): Boolean {
                effects += "play:$book"
                return true
            }
        }
        val coordinator = CatalogCardActionCoordinator(
            scope = this,
            gateway = gateway,
            sourceProbe = successfulProbe
        )

        coordinator.start(
            target = CatalogCardTarget(workId = "work-1", title = "Книга", author = "Автор"),
            action = CatalogCardAction.OPEN
        )

        assertTrue(coordinator.state.value is CatalogCardActionState.Checking)
        advanceUntilIdle()
        assertEquals(listOf("open:book-1"), effects)
        assertTrue(coordinator.state.value is CatalogCardActionState.Completed)
    }

    @Test
    fun `play resumes the saved Edition without resolving another Source`() = runTest {
        val effects = mutableListOf<String>()
        val gateway = object : CatalogCardActionGateway<String> {
            override suspend fun savedBook(target: CatalogCardTarget): String = "saved-edition"
            override suspend fun sourceCandidates(target: CatalogCardTarget): List<SourceSelectionCoordinator.SourceCandidate> =
                error("saved Listening State must win")
            override suspend fun import(target: CatalogCardTarget, source: SourceEntity): String? =
                error("saved Listening State must not import")
            override suspend fun open(book: String) = error("Play must not navigate")
            override suspend fun play(book: String, source: SourceEntity?): Boolean {
                effects += "play:$book"
                return true
            }
        }
        val coordinator = CatalogCardActionCoordinator(this, gateway, successfulProbe)

        coordinator.start(CatalogCardTarget("work-1", "Книга"), CatalogCardAction.PLAY)
        advanceUntilIdle()

        assertEquals(listOf("play:saved-edition"), effects)
        assertTrue(coordinator.state.value is CatalogCardActionState.Completed)
    }

    @Test
    fun `empty Source list is an explicit terminal failure`() = runTest {
        val gateway = object : CatalogCardActionGateway<String> {
            override suspend fun savedBook(target: CatalogCardTarget): String? = null
            override suspend fun sourceCandidates(target: CatalogCardTarget) = emptyList<SourceSelectionCoordinator.SourceCandidate>()
            override suspend fun import(target: CatalogCardTarget, source: SourceEntity): String? = null
            override suspend fun open(book: String) = true
            override suspend fun play(book: String, source: SourceEntity?) = true
        }
        val coordinator = CatalogCardActionCoordinator(this, gateway, successfulProbe)

        coordinator.start(CatalogCardTarget("work-1", "Книга"), CatalogCardAction.OPEN)
        advanceUntilIdle()

        assertEquals(
            CatalogCardFailure.EMPTY_SOURCES,
            (coordinator.state.value as CatalogCardActionState.Failed).reason
        )
    }

    @Test
    fun `late result after cancel cannot navigate or start Player`() = runTest {
        val importGate = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()
        val gateway = object : CatalogCardActionGateway<String> {
            override suspend fun savedBook(target: CatalogCardTarget): String? = null
            override suspend fun sourceCandidates(target: CatalogCardTarget) = listOf(directSource())
            override suspend fun import(target: CatalogCardTarget, source: SourceEntity): String =
                withContext(NonCancellable) {
                    importGate.await()
                    "late-book"
                }
            override suspend fun open(book: String): Boolean {
                effects += "open"
                return true
            }
            override suspend fun play(book: String, source: SourceEntity?): Boolean {
                effects += "play"
                return true
            }
        }
        val coordinator = CatalogCardActionCoordinator(this, gateway, successfulProbe)

        coordinator.start(CatalogCardTarget("work-1", "Книга"), CatalogCardAction.PLAY)
        runCurrent()
        coordinator.cancel()
        importGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
        assertTrue(coordinator.state.value is CatalogCardActionState.Cancelled)
    }

    @Test
    fun `same Edition prepares two Sources and second reaches Player after first times out`() = runTest {
        val attempted = mutableListOf<String>()
        val availability = mutableListOf<Boolean>()
        var activePreparations = 0
        var maxActivePreparations = 0
        val first = directSource("a-first")
        val second = directSource("b-second").copy(
            source = directSource("b-second").source.copy(
                url = "https://sound-books.net/book-1-backup"
            )
        )
        val gateway = object : CatalogCardActionGateway<String> {
            override suspend fun savedBook(target: CatalogCardTarget): String? = null
            override suspend fun sourceCandidates(target: CatalogCardTarget) = listOf(first, second)
            override suspend fun import(target: CatalogCardTarget, source: SourceEntity): String = source.id
            override suspend fun open(book: String): Boolean = true
            override suspend fun prepare(book: String, source: SourceEntity?): Boolean {
                activePreparations += 1
                maxActivePreparations = maxOf(maxActivePreparations, activePreparations)
                delay(if (source?.id == "a-first") 1 else 2)
                activePreparations -= 1
                return true
            }
            override suspend fun play(book: String, source: SourceEntity?): Boolean {
                attempted += source?.id.orEmpty()
                if (source?.id == "a-first") delay(2_000)
                return source?.id == "b-second"
            }
            override suspend fun recordAvailability(book: String, available: Boolean) {
                availability += available
            }
        }
        val coordinator = CatalogCardActionCoordinator(
            this,
            gateway,
            successfulProbe,
            budgetMs = 1_000
        )

        coordinator.start(CatalogCardTarget("work-1", "Книга"), CatalogCardAction.PLAY)
        advanceUntilIdle()

        assertEquals(listOf("a-first", "b-second"), attempted)
        assertEquals(2, maxActivePreparations)
        assertEquals(listOf(true), availability)
        assertTrue(coordinator.state.value is CatalogCardActionState.Completed)
    }

    @Test
    fun `rejected durable session ends at the same Source browser door`() = runTest {
        val source = directSource().source.copy(type = "4read", url = "https://4read.org/book")
        val candidates = catalogSessionCandidates(
            source,
            com.slukhayka.audiobooks.data.source.SourceAccessMode.BROWSER,
            hasFirstPartySession = true
        )
        val gateway = object : CatalogCardActionGateway<String> {
            override suspend fun savedBook(target: CatalogCardTarget): String? = null
            override suspend fun sourceCandidates(target: CatalogCardTarget) = candidates
            override suspend fun import(target: CatalogCardTarget, source: SourceEntity): String? = null
            override suspend fun open(book: String): Boolean = true
            override suspend fun play(book: String, source: SourceEntity?): Boolean = false
        }
        val coordinator = CatalogCardActionCoordinator(this, gateway, successfulProbe)

        coordinator.start(CatalogCardTarget("work-1", "Книга"), CatalogCardAction.PLAY)
        advanceUntilIdle()

        val state = coordinator.state.value as CatalogCardActionState.BrowserRequired
        assertEquals("4read", state.source.type)
        assertEquals("edition-1", state.source.editionId)
    }

    @Test
    fun `negative availability is recorded only after all compatible Sources fail`() = runTest {
        val availability = mutableListOf<Boolean>()
        val gateway = object : CatalogCardActionGateway<String> {
            override suspend fun savedBook(target: CatalogCardTarget): String? = null
            override suspend fun sourceCandidates(target: CatalogCardTarget) =
                listOf(directSource("a"), directSource("b"))
            override suspend fun import(target: CatalogCardTarget, source: SourceEntity): String = source.id
            override suspend fun open(book: String): Boolean = true
            override suspend fun play(book: String, source: SourceEntity?): Boolean = false
            override suspend fun recordAvailability(book: String, available: Boolean) {
                availability += available
            }
        }
        val coordinator = CatalogCardActionCoordinator(this, gateway, successfulProbe)

        coordinator.start(CatalogCardTarget("work-1", "Книга"), CatalogCardAction.PLAY)
        advanceUntilIdle()

        assertEquals(listOf(false), availability)
        assertTrue(coordinator.state.value is CatalogCardActionState.Failed)
    }

    // --- #469: tap-time sluhayua cross-resolve before the browser door -----

    @Test
    fun `4read-only card with a sluhayua MergeKey match plays from the direct source without the browser`() = runTest {
        var searchCalls = 0
        val effects = mutableListOf<String>()
        val gateway = object : CatalogCardActionGateway<String> {
            override suspend fun savedBook(target: CatalogCardTarget): String? = null
            override suspend fun sourceCandidates(target: CatalogCardTarget) = listOf(browserSource())
            override suspend fun crossResolveDirectSource(target: CatalogCardTarget): SourceEntity? {
                searchCalls += 1
                return sluhayuaCrossSource()
            }
            override suspend fun import(target: CatalogCardTarget, source: SourceEntity): String = source.id
            override suspend fun open(book: String): Boolean = true
            override suspend fun play(book: String, source: SourceEntity?): Boolean {
                effects += "play:$book:${source?.type}"
                return true
            }
        }
        val coordinator = CatalogCardActionCoordinator(this, gateway, successfulProbe)

        coordinator.start(
            CatalogCardTarget("work-1", "Книга", mergeKey = "книга|автор"),
            CatalogCardAction.PLAY
        )
        advanceUntilIdle()

        // Exactly ONE search request, played from the direct source, and the
        // browser door never surfaced.
        assertEquals(1, searchCalls)
        assertEquals(listOf("play:sluhayua-cross:sluhayua"), effects)
        assertTrue(coordinator.state.value is CatalogCardActionState.Completed)
    }

    @Test
    fun `4read-only card without a sluhayua match ends at the browser door with one search call`() = runTest {
        var searchCalls = 0
        val gateway = object : CatalogCardActionGateway<String> {
            override suspend fun savedBook(target: CatalogCardTarget): String? = null
            override suspend fun sourceCandidates(target: CatalogCardTarget) = listOf(browserSource())
            override suspend fun crossResolveDirectSource(target: CatalogCardTarget): SourceEntity? {
                searchCalls += 1
                return null
            }
            override suspend fun import(target: CatalogCardTarget, source: SourceEntity): String? =
                error("no match must not import")
            override suspend fun open(book: String): Boolean = true
            override suspend fun play(book: String, source: SourceEntity?): Boolean = true
        }
        val coordinator = CatalogCardActionCoordinator(this, gateway, successfulProbe)

        coordinator.start(
            CatalogCardTarget("work-1", "Книга", mergeKey = "книга|автор"),
            CatalogCardAction.PLAY
        )
        advanceUntilIdle()

        val state = coordinator.state.value as CatalogCardActionState.BrowserRequired
        assertEquals("4read", state.source.type)
        assertEquals(1, searchCalls)
    }

    @Test
    fun `repeated tap serves the cached cross-resolve without a second search call`() = runTest {
        var searchCalls = 0
        val cachedVerdicts = HashMap<String, SourceEntity?>()
        val gateway = object : CatalogCardActionGateway<String> {
            override suspend fun savedBook(target: CatalogCardTarget): String? = null
            override suspend fun sourceCandidates(target: CatalogCardTarget) = listOf(browserSource())
            override suspend fun crossResolveDirectSource(target: CatalogCardTarget): SourceEntity? {
                if (cachedVerdicts.containsKey(target.mergeKey)) {
                    return cachedVerdicts.getValue(target.mergeKey)
                }
                searchCalls += 1
                return sluhayuaCrossSource().also { cachedVerdicts[target.mergeKey] = it }
            }
            override suspend fun import(target: CatalogCardTarget, source: SourceEntity): String = source.id
            override suspend fun open(book: String): Boolean = true
            override suspend fun play(book: String, source: SourceEntity?): Boolean = true
        }
        val coordinator = CatalogCardActionCoordinator(this, gateway, successfulProbe)
        val target = CatalogCardTarget("work-1", "Книга", mergeKey = "книга|автор")

        coordinator.start(target, CatalogCardAction.PLAY)
        advanceUntilIdle()
        assertTrue(coordinator.state.value is CatalogCardActionState.Completed)

        coordinator.clearTerminalState()
        coordinator.start(target, CatalogCardAction.PLAY)
        advanceUntilIdle()

        assertEquals(1, searchCalls)
        assertTrue(coordinator.state.value is CatalogCardActionState.Completed)
    }

    @Test
    fun `4read-only open with a sluhayua match opens the imported edition instead of the browser`() = runTest {
        var searchCalls = 0
        val effects = mutableListOf<String>()
        val gateway = object : CatalogCardActionGateway<String> {
            override suspend fun savedBook(target: CatalogCardTarget): String? = null
            override suspend fun sourceCandidates(target: CatalogCardTarget) = listOf(browserSource())
            override suspend fun crossResolveDirectSource(target: CatalogCardTarget): SourceEntity? {
                searchCalls += 1
                return sluhayuaCrossSource()
            }
            override suspend fun import(target: CatalogCardTarget, source: SourceEntity): String = source.id
            override suspend fun open(book: String): Boolean {
                effects += "open:$book"
                return true
            }
            override suspend fun play(book: String, source: SourceEntity?): Boolean =
                error("Open must not start playback")
        }
        val coordinator = CatalogCardActionCoordinator(this, gateway, successfulProbe)

        coordinator.start(
            CatalogCardTarget("work-1", "Книга", mergeKey = "книга|автор"),
            CatalogCardAction.OPEN
        )
        advanceUntilIdle()

        assertEquals(1, searchCalls)
        assertEquals(listOf("open:sluhayua-cross"), effects)
        assertTrue(coordinator.state.value is CatalogCardActionState.Completed)
    }

    // --- #477: best-effort direct page fetch before the browser door -----

    @Test
    fun `4read-only play with a directly fetchable page plays without the browser`() = runTest {
        var directCalls = 0
        val effects = mutableListOf<String>()
        val gateway = object : CatalogCardActionGateway<String> {
            override suspend fun savedBook(target: CatalogCardTarget): String? = null
            override suspend fun sourceCandidates(target: CatalogCardTarget) = listOf(browserSource())
            override suspend fun crossResolveDirectSource(target: CatalogCardTarget): SourceEntity? = null
            override suspend fun importBrowserSourceDirect(
                target: CatalogCardTarget,
                source: SourceEntity
            ): String? {
                directCalls += 1
                assertEquals("4read", source.type)
                return "direct-book"
            }
            override suspend fun import(target: CatalogCardTarget, source: SourceEntity): String? =
                error("the direct door owns this import")
            override suspend fun open(book: String): Boolean = error("Play must not navigate")
            override suspend fun play(book: String, source: SourceEntity?): Boolean {
                effects += "play:$book:${source?.type}"
                return true
            }
        }
        val coordinator = CatalogCardActionCoordinator(this, gateway, successfulProbe)

        coordinator.start(
            CatalogCardTarget("work-1", "Книга", mergeKey = "книга|автор"),
            CatalogCardAction.PLAY
        )
        advanceUntilIdle()

        assertEquals(1, directCalls)
        assertEquals(listOf("play:direct-book:4read"), effects)
        assertTrue(coordinator.state.value is CatalogCardActionState.Completed)
    }

    @Test
    fun `4read-only open with a directly fetchable page opens without the browser`() = runTest {
        var directCalls = 0
        val effects = mutableListOf<String>()
        val gateway = object : CatalogCardActionGateway<String> {
            override suspend fun savedBook(target: CatalogCardTarget): String? = null
            override suspend fun sourceCandidates(target: CatalogCardTarget) = listOf(browserSource())
            override suspend fun crossResolveDirectSource(target: CatalogCardTarget): SourceEntity? = null
            override suspend fun importBrowserSourceDirect(
                target: CatalogCardTarget,
                source: SourceEntity
            ): String? {
                directCalls += 1
                return "direct-book"
            }
            override suspend fun import(target: CatalogCardTarget, source: SourceEntity): String? =
                error("the direct door owns this import")
            override suspend fun open(book: String): Boolean {
                effects += "open:$book"
                return true
            }
            override suspend fun play(book: String, source: SourceEntity?): Boolean =
                error("Open must not start playback")
        }
        val coordinator = CatalogCardActionCoordinator(this, gateway, successfulProbe)

        coordinator.start(
            CatalogCardTarget("work-1", "Книга", mergeKey = "книга|автор"),
            CatalogCardAction.OPEN
        )
        advanceUntilIdle()

        assertEquals(1, directCalls)
        assertEquals(listOf("open:direct-book"), effects)
        assertTrue(coordinator.state.value is CatalogCardActionState.Completed)
    }

    @Test
    fun `challenged direct fetch keeps the honest browser door with one attempt`() = runTest {
        var directCalls = 0
        val gateway = object : CatalogCardActionGateway<String> {
            override suspend fun savedBook(target: CatalogCardTarget): String? = null
            override suspend fun sourceCandidates(target: CatalogCardTarget) = listOf(browserSource())
            override suspend fun crossResolveDirectSource(target: CatalogCardTarget): SourceEntity? = null
            override suspend fun importBrowserSourceDirect(
                target: CatalogCardTarget,
                source: SourceEntity
            ): String? {
                directCalls += 1
                return null
            }
            override suspend fun import(target: CatalogCardTarget, source: SourceEntity): String? =
                error("no direct page must not import")
            override suspend fun open(book: String): Boolean = true
            override suspend fun play(book: String, source: SourceEntity?): Boolean = true
        }
        val coordinator = CatalogCardActionCoordinator(this, gateway, successfulProbe)

        coordinator.start(
            CatalogCardTarget("work-1", "Книга", mergeKey = "книга|автор"),
            CatalogCardAction.PLAY
        )
        advanceUntilIdle()

        val state = coordinator.state.value as CatalogCardActionState.BrowserRequired
        assertEquals("4read", state.source.type)
        assertEquals(1, directCalls)
    }

    // --- #470: one tap puts the book in the library ------------------------

    /**
     * A mergeKey-keyed stand-in for the library's Work table behind the
     * gateway: `import` upserts (MergeKey semantics — the same Work never
     * duplicates), `savedBook` reads what the store holds.
     */
    private class MergeKeyedBookStore(private val candidate: SourceSelectionCoordinator.SourceCandidate) {
        val works = LinkedHashMap<String, String>() // mergeKey -> book id
        var importCalls = 0
        var openCalls = 0

        fun gateway(openEffect: MutableList<String>) = object : CatalogCardActionGateway<String> {
            override suspend fun savedBook(target: CatalogCardTarget): String? = works[target.mergeKey]
            override suspend fun sourceCandidates(target: CatalogCardTarget) = listOf(candidate)
            override suspend fun import(target: CatalogCardTarget, source: SourceEntity): String {
                importCalls += 1
                // MergeKey/upsert: an already-imported Work keeps its row.
                return works.getOrPut(target.mergeKey) { "book-${works.size + 1}" }
            }
            override suspend fun open(book: String): Boolean {
                openCalls += 1
                openEffect += "open:$book"
                return true
            }
            override suspend fun play(book: String, source: SourceEntity?): Boolean =
                error("Open must not start playback")
        }
    }

    @Test
    fun `open tap on a not-yet-imported card persists the Work and never duplicates it on the second tap`() = runTest {
        val store = MergeKeyedBookStore(directSource())
        val effects = mutableListOf<String>()
        val coordinator = CatalogCardActionCoordinator(this, store.gateway(effects), successfulProbe)
        val target = CatalogCardTarget("work-1", "Книга", mergeKey = "книга|автор")

        coordinator.start(target, CatalogCardAction.OPEN)
        advanceUntilIdle()

        // One tap — the Work is in the library and the book page opened.
        assertEquals(1, store.importCalls)
        assertEquals(1, store.works.size)
        assertEquals("book-1", store.works["книга|автор"])
        assertEquals(listOf("open:book-1"), effects)
        assertTrue(coordinator.state.value is CatalogCardActionState.Completed)

        coordinator.clearTerminalState()
        coordinator.start(target, CatalogCardAction.OPEN)
        advanceUntilIdle()

        // The second tap reuses the stored Work — still exactly one row.
        assertEquals(1, store.importCalls)
        assertEquals(1, store.works.size)
        assertEquals(listOf("open:book-1", "open:book-1"), effects)
        assertTrue(coordinator.state.value is CatalogCardActionState.Completed)
    }
}
