package com.slukhayka.audiobooks.startup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * #1741 — App Check must be installed before the first Firestore request, but
 * the install itself initialises Firebase and used to run synchronously in
 * `Application.onCreate`; the profiler saw it on main in the first ~110 ms of
 * cold start.
 *
 * [start] moves the install to [io] and completes [installed] when the
 * installer returns. [launchAfter] is the Firestore door: the block runs only
 * after the caller's [prerequisite] (the persisted privacy route) and the
 * install have both completed — moving the call off main changes the thread,
 * not the order. A failing installer still completes the gate, because the
 * Firestore blocks must degrade like an unconfigured build instead of waiting
 * forever on a gate that can never complete.
 *
 * [install] is a seam: the regression witness records the thread and the order
 * without Firebase, exactly like [StartupReads] does for prefs and assets.
 */
class StartupAppCheck(
    private val install: suspend () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val installed = CompletableDeferred<Unit>()

    /**
     * Starts the install on [io] after [prerequisite]. Called once from the
     * composition root; a second call would install twice and is not expected.
     */
    fun start(scope: CoroutineScope, prerequisite: Deferred<Unit>): Job =
        scope.launch(io) {
            prerequisite.await()
            try {
                install()
            } finally {
                installed.complete(Unit)
            }
        }

    /** Runs [block] only after [prerequisite] and the App Check install. */
    fun launchAfter(
        scope: CoroutineScope,
        prerequisite: Deferred<Unit>,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = scope.launch {
        prerequisite.await()
        installed.await()
        block()
    }

    /**
     * Waits for the install from a coroutine that is already running (the
     * startup batch re-matches the curated catalogue by constructing modules
     * that open Firestore).
     */
    suspend fun awaitInstalled() {
        installed.await()
    }
}
