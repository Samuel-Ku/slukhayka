package com.slukhayka.audiobooks.ui.catalog

import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.source.SourceSelectionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

enum class CatalogCardAction { OPEN, PLAY }

data class CatalogCardTarget(
    val workId: String,
    val title: String,
    val author: String = "",
    val narrator: String = "",
    val coverImageUrl: String? = null,
    val preferredEditionId: String? = null,
    val mergeKey: String = "",
    val sources: List<CatalogCardSource> = emptyList(),
    val cardKey: String = workId
)

data class CatalogCardSource(
    val sourceId: String,
    val url: String,
    val editionId: String? = null,
    val streamOnly: Boolean = false
)

/**
 * Keeps automatic Source fallback inside an explicitly known Edition.
 * Browse-layer `work_sources` predates Edition ownership and therefore has no
 * narrator/Edition key; treating all of its rows as interchangeable would
 * let a Play tap silently change the listener's narration. Until a source
 * carries an explicit `editionId`, only the capability-first source is safe.
 */
fun editionScopedCatalogSources(
    preferredEditionId: String?,
    sources: List<CatalogCardSource>
): List<CatalogCardSource> {
    if (sources.isEmpty()) return emptyList()
    val selected = preferredEditionId?.takeIf { it.isNotBlank() }
    if (selected != null) {
        sources.filter { it.editionId == selected }.takeIf { it.isNotEmpty() }?.let { return it }
    }
    val assertedEditionIds = sources.mapNotNull { it.editionId?.takeIf(String::isNotBlank) }.distinct()
    return if (assertedEditionIds.size == 1) {
        sources.filter { it.editionId == assertedEditionIds.single() }
    } else {
        listOf(sources.first())
    }
}

enum class CatalogCardFailure {
    EMPTY_SOURCES,
    RESOLVE_FAILED,
    IMPORT_FAILED,
    ACTION_FAILED
}

sealed interface CatalogCardActionState {
    data object Idle : CatalogCardActionState
    data class Checking(val target: CatalogCardTarget, val action: CatalogCardAction) : CatalogCardActionState
    data class Completed(val target: CatalogCardTarget, val action: CatalogCardAction) : CatalogCardActionState
    data class BrowserRequired(
        val target: CatalogCardTarget,
        val action: CatalogCardAction,
        val source: SourceEntity
    ) : CatalogCardActionState
    data class Failed(
        val target: CatalogCardTarget,
        val action: CatalogCardAction,
        val reason: CatalogCardFailure
    ) : CatalogCardActionState
    data class Cancelled(val target: CatalogCardTarget, val action: CatalogCardAction) : CatalogCardActionState
}

/**
 * The application boundary used by [CatalogCardActionCoordinator]. Source
 * ordering stays in [SourceSelectionCoordinator]; this boundary only exposes
 * the side effects that differ between Android production and JVM tests.
 */
interface CatalogCardActionGateway<Book> {
    /** The imported rendition carrying the most relevant Listening State, when present. */
    suspend fun savedBook(target: CatalogCardTarget): Book?
    suspend fun sourceCandidates(target: CatalogCardTarget): List<SourceSelectionCoordinator.SourceCandidate>
    suspend fun import(target: CatalogCardTarget, source: SourceEntity): Book?
    suspend fun open(book: Book): Boolean
    suspend fun play(book: Book): Boolean
}

/**
 * One cancellable Open/Play contract for every catalogue Work card (#453).
 * A generation guard makes even cancellation-ignoring gateways harmless:
 * late results cannot navigate or reach the Player.
 */
class CatalogCardActionCoordinator<Book>(
    private val scope: CoroutineScope,
    private val gateway: CatalogCardActionGateway<Book>,
    private val sourceProbe: SourceSelectionCoordinator.SourceProbe,
    private val clock: SourceSelectionCoordinator.Clock = SourceSelectionCoordinator.DefaultClock,
    private val budgetMs: Long = SourceSelectionCoordinator.DEFAULT_BUDGET_MS
) {
    private val _state = MutableStateFlow<CatalogCardActionState>(CatalogCardActionState.Idle)
    val state: StateFlow<CatalogCardActionState> = _state.asStateFlow()

    private val generation = AtomicLong(0L)
    private var job: Job? = null

    fun start(target: CatalogCardTarget, action: CatalogCardAction) {
        val requestGeneration = generation.incrementAndGet()
        job?.cancel()
        _state.value = CatalogCardActionState.Checking(target, action)
        job = scope.launch {
            try {
                val saved = gateway.savedBook(target)
                if (!isCurrent(requestGeneration)) return@launch
                if (saved != null) {
                    finishAction(requestGeneration, target, action, saved)
                    return@launch
                }

                val candidates = try {
                    gateway.sourceCandidates(target)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failIfCurrent(requestGeneration, target, action, CatalogCardFailure.RESOLVE_FAILED)
                    return@launch
                }
                if (!isCurrent(requestGeneration)) return@launch
                if (candidates.isEmpty()) {
                    failIfCurrent(requestGeneration, target, action, CatalogCardFailure.EMPTY_SOURCES)
                    return@launch
                }

                when (val selection = SourceSelectionCoordinator.select(
                    operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
                    candidates = candidates,
                    probe = sourceProbe,
                    clock = clock,
                    budgetMs = budgetMs
                )) {
                    is SourceSelectionCoordinator.SelectionResult.Selected -> {
                        if (!isCurrent(requestGeneration)) return@launch
                        val imported = try {
                            gateway.import(target, selection.candidate.source)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        if (!isCurrent(requestGeneration)) return@launch
                        if (imported == null) {
                            failIfCurrent(requestGeneration, target, action, CatalogCardFailure.IMPORT_FAILED)
                        } else {
                            finishAction(requestGeneration, target, action, imported)
                        }
                    }
                    is SourceSelectionCoordinator.SelectionResult.BrowserRequired -> {
                        if (isCurrent(requestGeneration)) {
                            _state.value = CatalogCardActionState.BrowserRequired(
                                target,
                                action,
                                selection.candidate.source
                            )
                        }
                    }
                    SourceSelectionCoordinator.SelectionResult.Unavailable ->
                        failIfCurrent(requestGeneration, target, action, CatalogCardFailure.RESOLVE_FAILED)
                }
            } catch (cancelled: CancellationException) {
                // cancel() owns the visible terminal state. A replaced action
                // has already published its own Checking state.
                if (isCurrent(requestGeneration)) {
                    _state.value = CatalogCardActionState.Cancelled(target, action)
                }
            } catch (_: Exception) {
                failIfCurrent(requestGeneration, target, action, CatalogCardFailure.ACTION_FAILED)
            }
        }
    }

    fun cancel() {
        val current = _state.value
        generation.incrementAndGet()
        job?.cancel()
        job = null
        _state.value = when (current) {
            is CatalogCardActionState.Checking -> CatalogCardActionState.Cancelled(current.target, current.action)
            else -> current
        }
    }

    fun clearTerminalState() {
        if (_state.value !is CatalogCardActionState.Checking) _state.value = CatalogCardActionState.Idle
    }

    private suspend fun finishAction(
        requestGeneration: Long,
        target: CatalogCardTarget,
        action: CatalogCardAction,
        book: Book
    ) {
        if (!isCurrent(requestGeneration)) return
        val succeeded = try {
            when (action) {
                CatalogCardAction.OPEN -> gateway.open(book)
                CatalogCardAction.PLAY -> gateway.play(book)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!isCurrent(requestGeneration)) return
        _state.value = if (succeeded) {
            CatalogCardActionState.Completed(target, action)
        } else {
            CatalogCardActionState.Failed(target, action, CatalogCardFailure.ACTION_FAILED)
        }
    }

    private fun failIfCurrent(
        requestGeneration: Long,
        target: CatalogCardTarget,
        action: CatalogCardAction,
        failure: CatalogCardFailure
    ) {
        if (isCurrent(requestGeneration)) {
            _state.value = CatalogCardActionState.Failed(target, action, failure)
        }
    }

    private fun isCurrent(requestGeneration: Long): Boolean = generation.get() == requestGeneration
}
