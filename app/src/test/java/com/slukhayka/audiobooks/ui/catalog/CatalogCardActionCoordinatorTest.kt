package com.slukhayka.audiobooks.ui.catalog

import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.source.SourceSelectionCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
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
            override suspend fun play(book: String): Boolean {
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
            override suspend fun play(book: String): Boolean {
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
            override suspend fun play(book: String) = true
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
            override suspend fun play(book: String): Boolean {
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
}
