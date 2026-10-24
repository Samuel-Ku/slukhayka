package com.slukhayka.audiobooks.data.imports

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
 *    of the current one (#1243): scans and applies carry the session that
 *    started them, and only that session's result may publish;
 *  - one write at a time across sessions: a new scan may supersede the preview
 *    while a write is still running, but it cannot start a second write.
 *
 * It owns no writer of its own: [applyPlan] is the injected door (the existing
 * `LibraryImport.applyImportPlan`, the only writer), and grouping/dedup/
 * transactions/rollback/rescan rules stay where they are. ADR-0008: the shared
 * ViewModel keeps the orchestration; this is a pure state module it reads.
 *
 * Callers are confined to the main thread today (UI callbacks and the shared
 * ViewModel's main-thread entry points). The two transitions that decide whether
 * a write may start — [beginApply] and [dismiss] — are compare-and-set loops, so
 * two concurrent callers cannot both win: the second one re-reads the state and
 * answers from it. The remaining transitions ([begin], [edit], the outcome
 * writes in [apply]) rely on that same main-thread confinement.
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
    data class ApplyRequest(
        val plan: ImportPlan,
        val treeUri: String,
        /** The scan session that produced this plan; a late result may not publish over another. */
        val session: Long
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The scan session that owns the current preview; bumped by every new scan/dismiss. */
    private val session = AtomicLong(0)

    /** One write at a time across sessions (#1242, #1243) — a new scan cannot start a second one. */
    private val writeInFlight = AtomicBoolean(false)

    /**
     * Starts a new scan session and clears any previous preview.
     *
     * The token must be passed to [completeScan]/[reportScanProblem]; a result
     * for a superseded token is dropped instead of replacing the newer preview
     * (#1243). A scan does NOT start a write, so it may supersede the preview
     * while a write is still in flight — the write's result simply no longer
     * belongs to the visible session.
     */
    fun beginScan(): Long {
        val token = session.incrementAndGet()
        _state.value = State.Idle
        return token
    }

    /** True when [token] still owns the visible session (the result may publish). */
    fun isCurrentScan(token: Long): Boolean = token == session.get()

    /** True when [request]'s session is still the visible one (its outcome may be shown). */
    fun isCurrent(request: ApplyRequest): Boolean = request.session == session.get()

    /**
     * Publishes the plan a scan produced, if [token] is still current.
     *
     * @return false when a newer scan/dismiss superseded this one: the plan is
     * dropped and the current preview stays as the listener left it.
     */
    fun completeScan(token: Long, plan: ImportPlan, treeUri: String): Boolean {
        while (true) {
            if (!isCurrentScan(token)) return false
            val current = _state.value
            if (_state.compareAndSet(current, State.Editing(plan, treeUri))) return true
        }
    }

    /**
     * A scan produced no plan or failed: only its own session may show that
     * message, a superseded scan cannot replace the current one's notice.
     */
    fun reportScanProblem(token: Long): Boolean = isCurrentScan(token)

    /**
     * A scan produced a plan synchronously (test/simple callers — production
     * scans go through [beginScan]/[completeScan]): a new session starts here too.
     *
     * @return false while a confirmed write is in flight — that plan is frozen.
     */
    fun begin(plan: ImportPlan, treeUri: String): Boolean {
        if (writeInFlight.get()) return false
        session.incrementAndGet()
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
    fun dismiss(): Boolean {
        while (true) {
            val current = _state.value
            if (current is State.Applying) return false
            // A dismissed scan must not reopen its preview when it finishes, so
            // the session moves even when there is nothing visible to clear.
            session.incrementAndGet()
            when (current) {
                is State.Editing, is State.Failed ->
                    if (_state.compareAndSet(current, State.Idle)) return true
                is State.Applying, State.Idle -> return false
            }
        }
    }

    /**
     * Atomically moves an editable or retryable plan to [State.Applying] and
     * returns the frozen snapshot the caller must pass to [apply].
     *
     * @return null when no apply may start (already applying, or no plan).
     */
    fun beginApply(): ApplyRequest? {
        while (true) {
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
            if (!writeInFlight.compareAndSet(false, true)) return null
            val owner = session.get()
            if (_state.compareAndSet(current, State.Applying(plan, treeUri))) {
                return ApplyRequest(plan, treeUri, owner)
            }
            // Another caller won the transition; release the write slot and answer from it.
            writeInFlight.set(false)
        }
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
        try {
            val current = _state.value as? State.Applying ?: return null
            if (current.plan !== request.plan || current.treeUri != request.treeUri) return null
            val result = try {
                applyPlan(request.plan, request.treeUri)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (storage: Exception) {
                publishOutcome(request) {
                    State.Failed(request.plan, request.treeUri, storage.message ?: REASON_APPLY_FAILED)
                }
                return null
            }
            publishOutcome(request) { State.Idle }
            return result
        } finally {
            // Released on every path, including a superseded pre-start guard:
            // otherwise a later import confirm would silently return null.
            writeInFlight.set(false)
        }
    }

    /**
     * Publishes an outcome only when the request still belongs to the visible
     * session; a late result of a superseded session must not clear the newer
     * preview (#1243), though its caller still receives the honest write result.
     */
    private fun publishOutcome(request: ApplyRequest, outcome: () -> State) {
        while (true) {
            if (request.session != session.get()) return
            val current = _state.value
            if (_state.compareAndSet(current, outcome())) return
        }
    }

    companion object {
        const val REASON_APPLY_FAILED = "apply-failed"
    }
}
