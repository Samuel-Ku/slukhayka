package com.slukhayka.audiobooks.ui.catalog

import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.source.SourceSelectionCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
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
    fun `same Edition playback races two Sources and first playing cancels sibling`() = runTest {
        val attempted = mutableListOf<String>()
        val availability = mutableListOf<Boolean>()
        val bothStarted = CompletableDeferred<Unit>()
        val winnerMayFinish = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        var loserCancelled = false
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
            override suspend fun play(book: String, source: SourceEntity?): Boolean {
                attempted += source?.id.orEmpty()
                active += 1
                maxActive = maxOf(maxActive, active)
                if (active == 2) bothStarted.complete(Unit)
                return try {
                    bothStarted.await()
                    if (source?.id == "b-second") {
                        winnerMayFinish.await()
                        true
                    } else {
                        awaitCancellation()
                    }
                } finally {
                    active -= 1
                    if (source?.id == "a-first") loserCancelled = true
                }
            }
            override suspend fun recordAvailability(book: String, available: Boolean) {
                availability += available
            }
        }
        val coordinator = CatalogCardActionCoordinator(this, gateway, successfulProbe)

        coordinator.start(CatalogCardTarget("work-1", "Книга"), CatalogCardAction.PLAY)
        runCurrent()
        bothStarted.await()
        winnerMayFinish.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("a-first", "b-second"), attempted)
        assertEquals(2, maxActive)
        assertTrue(loserCancelled)
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
}
