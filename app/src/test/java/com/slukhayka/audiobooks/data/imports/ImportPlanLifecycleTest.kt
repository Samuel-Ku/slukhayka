package com.slukhayka.audiobooks.data.imports

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1242 — the import-plan lifecycle, pinned through its own interface.
 *
 * The rules under test are the ones the screen and the shared ViewModel used to
 * keep as ad-hoc flags: one confirmed apply per session, a frozen confirmed
 * snapshot, a dismiss that writes nothing, an honest failed/retry state and a
 * cancellation that is not an import error. The write door is a controlled
 * adapter, so no Room, no filesystem and no sleeps are involved.
 */
class ImportPlanLifecycleTest {

    private fun plan(treeUri: String = "content://tree-a") = ImportPlan(
        source = SourceRef.Folder(treeUri = treeUri),
        books = emptyList()
    )

    private fun result(books: Int = 1) = LocalImportResult(
        booksImported = books,
        filesImported = books,
        skippedFiles = 0
    )

    @Test
    fun `one confirmation starts exactly one apply`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val lifecycle = ImportPlanLifecycle { _, _ ->
            calls++
            gate.await()
            result()
        }
        lifecycle.begin(plan(), "content://tree-a")
        val request = lifecycle.beginApply()
        assertTrue(request != null)

        // A double tap, or a second programmatic call, cannot start another one.
        assertNull(lifecycle.beginApply())
        assertFalse("the confirmed plan is not editable", lifecycle.edit { it })
        assertFalse("an in-flight write is not a cancel", lifecycle.dismiss())
        assertFalse("a late scan cannot replace the in-flight plan", lifecycle.begin(plan(), "content://tree-b"))

        val applying = launch { lifecycle.apply(request!!) }
        runCurrent()
        assertEquals(1, calls)
        gate.complete(Unit)
        applying.join()
        assertEquals("still exactly one write", 1, calls)
        assertEquals(ImportPlanLifecycle.State.Idle, lifecycle.state.value)
    }

    @Test
    fun `the frozen confirmed snapshot is what gets written`() = runTest {
        var written: ImportPlan? = null
        val lifecycle = ImportPlanLifecycle { plan, _ ->
            written = plan
            result()
        }
        lifecycle.begin(plan("content://tree-a"), "content://tree-a")
        val request = lifecycle.beginApply()!!
        assertFalse("a confirmed plan is frozen", lifecycle.edit { it.copy(source = SourceRef.Rescan("content://other")) })

        lifecycle.apply(request)

        assertSame(request.plan, written)
        assertEquals("content://tree-a", (written?.source as SourceRef.Folder).treeUri)
    }

    @Test
    fun `dismiss before confirmation writes nothing`() = runTest {
        var calls = 0
        val lifecycle = ImportPlanLifecycle { _, _ ->
            calls++
            result()
        }
        lifecycle.begin(plan(), "content://tree-a")
        assertTrue(lifecycle.dismiss())
        assertEquals(ImportPlanLifecycle.State.Idle, lifecycle.state.value)
        assertNull(lifecycle.beginApply())
        assertEquals(0, calls)
    }

    @Test
    fun `a failed apply keeps the plan for an explicit retry`() = runTest {
        var fail = true
        var calls = 0
        val lifecycle = ImportPlanLifecycle { _, _ ->
            calls++
            if (fail) throw IllegalStateException("disk full") else result(books = 2)
        }
        lifecycle.begin(plan(), "content://tree-a")

        assertNull(lifecycle.apply(lifecycle.beginApply()!!))
        val failed = lifecycle.state.value as ImportPlanLifecycle.State.Failed
        assertEquals("content://tree-a", failed.treeUri)
        assertEquals("disk full", failed.reason)

        fail = false
        val retry = lifecycle.beginApply()!!
        val retried = lifecycle.apply(retry)
        assertEquals(2, retried?.booksImported)
        assertEquals(2, calls)
        assertEquals(ImportPlanLifecycle.State.Idle, lifecycle.state.value)
    }

    @Test
    fun `cancellation is rethrown and never becomes an import error`() = runTest {
        val lifecycle = ImportPlanLifecycle { _, _ -> throw CancellationException("cancelled") }
        lifecycle.begin(plan(), "content://tree-a")
        val request = lifecycle.beginApply()!!

        val thrown = runCatching { lifecycle.apply(request) }.exceptionOrNull()

        assertTrue("cancellation is not swallowed", thrown is CancellationException)
        assertTrue(
            "the state is not turned into a retryable failure",
            lifecycle.state.value is ImportPlanLifecycle.State.Applying
        )
    }

    @Test
    fun `a slow scan cannot replace a newer preview`() = runTest {
        val lifecycle = ImportPlanLifecycle { _, _ -> result() }
        val slow = lifecycle.beginScan()
        val fast = lifecycle.beginScan()

        assertFalse("the superseded scan is dropped", lifecycle.completeScan(slow, plan("content://a"), "content://a"))
        assertTrue(lifecycle.completeScan(fast, plan("content://b"), "content://b"))

        val editing = lifecycle.state.value as ImportPlanLifecycle.State.Editing
        assertEquals("content://b", editing.treeUri)
    }

    @Test
    fun `a dismissed scan does not reopen its preview`() = runTest {
        val lifecycle = ImportPlanLifecycle { _, _ -> result() }
        val scan = lifecycle.beginScan()
        assertFalse("nothing visible to dismiss", lifecycle.dismiss())

        assertFalse("the finished scan belongs to a closed session", lifecycle.completeScan(scan, plan(), "content://tree-a"))
        assertEquals(ImportPlanLifecycle.State.Idle, lifecycle.state.value)
    }

    @Test
    fun `a late apply result does not clear a newer preview`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val lifecycle = ImportPlanLifecycle { _, _ ->
            gate.await()
            result(books = 3)
        }
        lifecycle.begin(plan("content://old"), "content://old")
        val request = lifecycle.beginApply()!!

        // The listener moved on to a newer scan while the old write was running.
        val newer = lifecycle.beginScan()
        assertTrue(lifecycle.completeScan(newer, plan("content://new"), "content://new"))
        val applying = launch { lifecycle.apply(request) }
        runCurrent()
        gate.complete(Unit)
        applying.join()

        val editing = lifecycle.state.value as ImportPlanLifecycle.State.Editing
        assertEquals("the newer preview survives the late result", "content://new", editing.treeUri)
        assertFalse("the late result is not the visible session's", lifecycle.isCurrent(request))
    }

    @Test
    fun `a late apply failure does not turn the newer preview into a retry`() = runTest {
        val lifecycle = ImportPlanLifecycle { _, _ -> throw IllegalStateException("disk full") }
        lifecycle.begin(plan("content://old"), "content://old")
        val request = lifecycle.beginApply()!!
        val newer = lifecycle.beginScan()
        assertTrue(lifecycle.completeScan(newer, plan("content://new"), "content://new"))

        assertNull(lifecycle.apply(request))

        assertTrue(
            "the newer preview is not replaced by the old failure",
            lifecycle.state.value is ImportPlanLifecycle.State.Editing
        )
    }

    @Test
    fun `a new scan cannot start a second write while one is in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val lifecycle = ImportPlanLifecycle { _, _ ->
            gate.await()
            result()
        }
        lifecycle.begin(plan(), "content://tree-a")
        val first = lifecycle.beginApply()!!
        val applying = launch { lifecycle.apply(first) }
        runCurrent()

        assertNull("one write at a time across sessions", lifecycle.beginApply())

        gate.complete(Unit)
        applying.join()
        assertTrue(lifecycle.state.value is ImportPlanLifecycle.State.Idle)
    }

    @Test
    fun `a result for an already finished session writes nothing`() = runTest {
        var calls = 0
        val lifecycle = ImportPlanLifecycle { _, _ ->
            calls++
            result()
        }
        lifecycle.begin(plan(), "content://tree-a")
        val request = lifecycle.beginApply()!!
        lifecycle.apply(request)
        assertEquals(ImportPlanLifecycle.State.Idle, lifecycle.state.value)

        assertNull(lifecycle.apply(request))
        assertEquals("no second write for the same request", 1, calls)
    }
}
