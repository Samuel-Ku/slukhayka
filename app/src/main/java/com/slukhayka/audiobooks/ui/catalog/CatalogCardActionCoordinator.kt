package com.slukhayka.audiobooks.ui.catalog

import com.slukhayka.audiobooks.data.catalog.CatalogAvailabilityPolicy
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.source.SourceSelectionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
    suspend fun sourceCandidates(
        target: CatalogCardTarget,
        savedBook: Book?
    ): List<SourceSelectionCoordinator.SourceCandidate> = sourceCandidates(target)
    suspend fun import(target: CatalogCardTarget, source: SourceEntity): Book?
    suspend fun open(book: Book): Boolean
    suspend fun prepare(book: Book, source: SourceEntity?): Boolean = true
    suspend fun play(book: Book, source: SourceEntity?): Boolean
    /** Local Edition verdict; implementations must keep this best-effort. */
    suspend fun recordAvailability(book: Book, available: Boolean) = Unit
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
    private val budgetMs: Long = CatalogAvailabilityPolicy.SOURCE_BUDGET_MS
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
                var lastPlayableBook = saved
                if (!isCurrent(requestGeneration)) return@launch
                if (saved != null) {
                    if (finishAction(requestGeneration, target, action, saved, null)) return@launch
                    if (!isCurrent(requestGeneration)) return@launch
                }

                val candidates = try {
                    gateway.sourceCandidates(target, saved)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failIfCurrent(requestGeneration, target, action, CatalogCardFailure.RESOLVE_FAILED)
                    return@launch
                }
                if (!isCurrent(requestGeneration)) return@launch
                if (candidates.isEmpty()) {
                    if (action == CatalogCardAction.PLAY && lastPlayableBook != null) {
                        runCatching { gateway.recordAvailability(lastPlayableBook, false) }
                    }
                    failIfCurrent(requestGeneration, target, action, CatalogCardFailure.EMPTY_SOURCES)
                    return@launch
                }
                var lastFailure = CatalogCardFailure.RESOLVE_FAILED

                if (action == CatalogCardAction.PLAY) {
                    val browserFallback = candidates.firstOrNull {
                        it.category == SourceSelectionCoordinator.SourceCategory.BROWSER
                    }
                    val automatic = candidates.filterNot {
                        it.category == SourceSelectionCoordinator.SourceCategory.BROWSER
                    }
                    val preferredEdition = target.preferredEditionId
                        ?.takeIf(String::isNotBlank)
                        ?.takeIf { edition -> automatic.any { it.source.editionId == edition } }
                    val assertedEdition = preferredEdition ?: automatic
                        .mapNotNull { it.source.editionId?.takeIf(String::isNotBlank) }
                        .firstOrNull()
                    val eligible = if (assertedEdition != null) {
                        automatic.filter { it.source.editionId == assertedEdition }
                    } else {
                        // Browse-layer work_sources carry Work provenance only.
                        // Without an asserted Edition, trying a second row could
                        // silently switch narration, so only the ordered leader
                        // participates in this action.
                        automatic.take(1)
                    }.take(CatalogAvailabilityPolicy.MAX_PARALLEL_SOURCES)

                    if (eligible.isNotEmpty()) {
                        val raceEdition = assertedEdition ?: "catalog-single:${eligible.first().source.id}"
                        val byId = eligible.associateBy { it.source.id }
                        val importedBooks = ConcurrentHashMap<String, Book>()
                        val sawImportedBook = AtomicBoolean(false)
                        val raceResult = BoundedEditionPlaybackRace(budgetMs).racePrepared(
                            selectedEditionId = raceEdition,
                            candidates = eligible.map { candidate ->
                                EditionSourceCandidate(
                                    sourceId = candidate.source.id,
                                    editionId = candidate.source.editionId ?: raceEdition,
                                    url = candidate.source.url
                                )
                            },
                            prepare = { raceCandidate ->
                                val candidate = byId.getValue(raceCandidate.sourceId)
                                val imported = try {
                                    gateway.import(target, candidate.source)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    null
                                } ?: return@racePrepared SourceAttemptVerdict.TEMPORARY_FAILURE
                                if (!isCurrent(requestGeneration)) {
                                    return@racePrepared SourceAttemptVerdict.TEMPORARY_FAILURE
                                }
                                importedBooks[raceCandidate.sourceId] = imported
                                sawImportedBook.set(true)
                                if (gateway.prepare(imported, candidate.source)) {
                                    SourceAttemptVerdict.READY
                                } else {
                                    SourceAttemptVerdict.TEMPORARY_FAILURE
                                }
                            },
                            play = { raceCandidate ->
                                val candidate = byId.getValue(raceCandidate.sourceId)
                                val imported = importedBooks.getValue(raceCandidate.sourceId)
                                if (gateway.play(imported, candidate.source)) {
                                    SourceAttemptVerdict.PLAYING
                                } else {
                                    SourceAttemptVerdict.TEMPORARY_FAILURE
                                }
                            }
                        )
                        if (!isCurrent(requestGeneration)) return@launch
                        if (raceResult.verdict == SourceAttemptVerdict.PLAYING) {
                            val winningBook = raceResult.source?.sourceId?.let(importedBooks::get)
                            if (winningBook != null) {
                                runCatching { gateway.recordAvailability(winningBook, true) }
                            }
                            _state.value = CatalogCardActionState.Completed(target, action)
                            return@launch
                        }
                        lastPlayableBook = importedBooks.values.firstOrNull() ?: lastPlayableBook
                        lastFailure = if (sawImportedBook.get()) {
                            CatalogCardFailure.ACTION_FAILED
                        } else {
                            CatalogCardFailure.IMPORT_FAILED
                        }
                    }
                    if (browserFallback != null && isCurrent(requestGeneration)) {
                        _state.value = CatalogCardActionState.BrowserRequired(
                            target,
                            action,
                            browserFallback.source
                        )
                        return@launch
                    }
                    if (lastPlayableBook != null) {
                        runCatching { gateway.recordAvailability(lastPlayableBook, false) }
                    }
                    failIfCurrent(requestGeneration, target, action, lastFailure)
                    return@launch
                }

                val remaining = candidates.toMutableList()
                while (remaining.isNotEmpty() && isCurrent(requestGeneration)) {
                    when (val selection = SourceSelectionCoordinator.select(
                        operation = SourceSelectionCoordinator.OperationKind.PLAYBACK,
                        candidates = remaining,
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
                            lastFailure = CatalogCardFailure.IMPORT_FAILED
                            remaining.remove(selection.candidate)
                        } else {
                            lastPlayableBook = imported
                            val succeeded = finishAction(
                                requestGeneration,
                                target,
                                action,
                                imported,
                                selection.candidate.source
                            )
                            if (succeeded || !isCurrent(requestGeneration)) return@launch
                            lastFailure = CatalogCardFailure.ACTION_FAILED
                            remaining.remove(selection.candidate)
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
                        return@launch
                    }
                    SourceSelectionCoordinator.SelectionResult.Unavailable -> {
                        remaining.clear()
                    }
                    }
                }
                if (action == CatalogCardAction.PLAY && lastPlayableBook != null) {
                    runCatching { gateway.recordAvailability(lastPlayableBook, false) }
                }
                failIfCurrent(requestGeneration, target, action, lastFailure)
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
        book: Book,
        source: SourceEntity?
    ): Boolean {
        if (!isCurrent(requestGeneration)) return false
        val succeeded = try {
            when (action) {
                CatalogCardAction.OPEN -> gateway.open(book)
                CatalogCardAction.PLAY -> gateway.prepare(book, source) && gateway.play(book, source)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!isCurrent(requestGeneration)) return false
        if (succeeded) {
            if (action == CatalogCardAction.PLAY) {
                runCatching { gateway.recordAvailability(book, true) }
            }
            _state.value = CatalogCardActionState.Completed(target, action)
        }
        return succeeded
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
