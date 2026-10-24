package com.slukhayka.audiobooks.data.imports

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * #1242 — the ONE lifecycle of an import plan: scan → edit → confirm → apply.
 *
 * The plan and its allowed transitions live here, not in the screen and not in
 * ad-hoc flags on the shared ViewModel. The rules this module owns:
 *
 *  - a plan is EDITABLE only before the listener confirms it; a confirmed plan
 *    is frozen into an [ApplyRequest] snapshot, so later edits cannot change
 *    what is being written;
 *  - the transition to [State.Applying] happens BEFORE the asynchronous write
 *    starts, so a double tap (or two programmatic calls) can start at most one
 *    apply for one session;
 *  - Cancel/dismiss before confirmation writes nothing; dismissing a plan that
 *    is already being applied is refused — closing the window must not pretend
 *    the finished writes will be rolled back;
 *  - a failed apply keeps the confirmed plan and returns to a retryable state;
 *    cancellation is not an ordinary import error and is never swallowed;
 *  - a result that belongs to a superseded session cannot overwrite the state
 *    of the current one.
 *
 * It owns no writer of its own: [applyPlan] is the injected door (the existing
 * `LibraryImport.applyImportPlan`, the only writer), and grouping/dedup/
 * transactions/rollback/rescan rules stay where they are. ADR-0008: the shared
 * ViewModel keeps the orchestration; this is a pure state module it reads.
 */
class ImportPlanLifecycle(
    private val applyPlan: suspend (plan: ImportPlan, treeUri: String) -> LocalImportResult
) {

    sealed interface State {
        /** No plan: nothing to preview and nothing in flight. */
        data object Idle : State

        /** The listener may edit and confirm this plan. */
        data class Editing(val plan: ImportPlan, val treeUri: String) : State

        /** One confirmed write is in flight; the snapshot below is what it writes. */
        data class Applying(val plan: ImportPlan, val treeUri: String) : State

        /** The write failed without a result; the plan is kept for an explicit retry. */
        data class Failed(
            val plan: ImportPlan,
            val treeUri: String,
            val reason: String
        ) : State
    }

    /**
     * The frozen snapshot a confirmed apply writes. Holding the exact [plan]
     * instance is deliberate: the module compares it by identity when the
     * asynchronous result comes back, so a result for another session can never
     * be applied to this one.
     */
    data class ApplyRequest(val plan: ImportPlan, val treeUri: String)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * A scan produced a plan: the listener edits it from now on.
     *
     * @return false while a confirmed write is in flight — a late scan cannot
     * replace the plan that is being written (#1243 builds on this).
     */
    fun begin(plan: ImportPlan, treeUri: String): Boolean {
        if (_state.value is State.Applying) return false
        _state.value = State.Editing(plan, treeUri)
        return true
    }

    /**
     * Applies [transform] to the editable plan.
     *
     * @return false when there is nothing editable (applying, failed or idle):
     * a programmatic call cannot edit a confirmed plan.
     */
    fun edit(transform: (ImportPlan) -> ImportPlan): Boolean {
        val current = _state.value as? State.Editing ?: return false
        _state.value = current.copy(plan = transform(current.plan))
        return true
    }

    /**
     * Dismisses an editable or failed plan without writing.
     *
     * @return false while a confirmed write is in flight: that is not a cancel.
     */
    fun dismiss(): Boolean = when (_state.value) {
        is State.Editing, is State.Failed -> {
            _state.value = State.Idle
            true
        }
        is State.Applying, State.Idle -> false
    }

    /**
     * Atomically moves an editable or retryable plan to [State.Applying] and
     * returns the frozen snapshot the caller must pass to [apply].
     *
     * @return null when no apply may start (already applying, or no plan).
     */
    fun beginApply(): ApplyRequest? {
        val current = _state.value
        val plan: ImportPlan
        val treeUri: String
        when (current) {
            is State.Editing -> {
                plan = current.plan
                treeUri = current.treeUri
            }
            is State.Failed -> {
                plan = current.plan
                treeUri = current.treeUri
            }
            is State.Applying, State.Idle -> return null
        }
        _state.value = State.Applying(plan, treeUri)
        return ApplyRequest(plan, treeUri)
    }

    /**
     * Runs the one in-flight write for [request] and publishes its outcome.
     *
     * A superseded request (the state is no longer the same [State.Applying]
     * snapshot) returns null without writing: the plan it belongs to is gone.
     * A storage failure returns to [State.Failed] with the confirmed plan kept
     * for an explicit retry; cancellation is rethrown and never turned into an
     * import error.
     *
     * @return the honest write result, or null when nothing was written.
     */
    suspend fun apply(request: ApplyRequest): LocalImportResult? {
        val current = _state.value as? State.Applying ?: return null
        if (current.plan !== request.plan || current.treeUri != request.treeUri) return null
        val result = try {
            applyPlan(request.plan, request.treeUri)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (storage: Exception) {
            _state.value = State.Failed(
                plan = request.plan,
                treeUri = request.treeUri,
                reason = storage.message ?: REASON_APPLY_FAILED
            )
            return null
        }
        _state.value = State.Idle
        return result
    }

    companion object {
        const val REASON_APPLY_FAILED = "apply-failed"
    }
}
