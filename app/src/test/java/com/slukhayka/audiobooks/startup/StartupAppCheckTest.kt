package com.slukhayka.audiobooks.startup

import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1741 — the regression witness for the cold-start profiler finding: the App
 * Check install used to initialise Firebase synchronously in `App.onCreate`
 * (two calls on main in the first ~110 ms). [StartupAppCheck] moves the
 * install to IO, and the Firestore-touching startup blocks wait for it.
 *
 * The installer is injected, so this is a THREAD assertion and an ORDER
 * assertion, not a proxy for Firebase: the fake records the thread it ran on
 * and the moment the Firestore block was released.
 */
class StartupAppCheckTest {

    @Test
    fun `the install runs off the caller thread`() = runBlocking {
        val caller = Thread.currentThread()
        val installThread = AtomicReference<Thread?>(null)
        val gate = StartupAppCheck(
            install = { installThread.set(Thread.currentThread()) },
            io = Dispatchers.IO,
        )

        gate.start(this, ready())
        gate.awaitInstalled()

        assertNotSame(
            "the Firebase/App Check initialisation must not run on the caller (main) thread",
            caller,
            installThread.get(),
        )
    }

    @Test
    fun `firestore touching work starts only after the install`() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val installStarted = CompletableDeferred<Unit>()
        val finishInstall = CompletableDeferred<Unit>()
        val gate = StartupAppCheck(
            install = {
                order += "install-start"
                installStarted.complete(Unit)
                finishInstall.await()
                order += "install-done"
            },
            io = Dispatchers.IO,
        )
        val ready = ready()

        gate.start(this, ready)
        installStarted.await()
        val firestoreWork = gate.launchAfter(this, ready) { order += "firestore" }

        // Give the Firestore block every chance to jump the gate.
        delay(100)
        assertEquals(
            "Firestore work must not start while App Check is still installing",
            listOf("install-start"),
            order.toList(),
        )

        finishInstall.complete(Unit)
        firestoreWork.join()
        assertEquals(
            listOf("install-start", "install-done", "firestore"),
            order.toList(),
        )
    }

    @Test
    fun `a failed install still releases the Firestore gate`() = runBlocking {
        val failureSeen = CompletableDeferred<Throwable>()
        val supervisor = CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, failure -> failureSeen.complete(failure) }
        )
        val gate = StartupAppCheck(install = { error("Firebase is not configured") })

        gate.start(supervisor, ready())
        gate.awaitInstalled()

        var ran = false
        gate.launchAfter(supervisor, ready()) { ran = true }.join()

        assertTrue(
            "Firestore startup work must not wait forever after a failed install",
            ran,
        )
        assertTrue(
            "the install failure must reach the coroutine handler, not vanish",
            failureSeen.await() is IllegalStateException,
        )
    }

    private fun ready(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().apply { complete(Unit) }
}
