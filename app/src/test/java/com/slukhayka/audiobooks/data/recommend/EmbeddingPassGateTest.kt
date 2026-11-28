package com.slukhayka.audiobooks.data.recommend

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class EmbeddingPassGateTest {
    @Test fun `failed start publication does not wedge the single flight`() {
        val gate = EmbeddingPassGate()
        assertThrows(IllegalStateException::class.java) { gate.begin { error("failed start") } }
        assertNotNull(gate.begin())
    }

    @Test fun `start publication and install invalidation share one ordering boundary`() {
        val gate = EmbeddingPassGate()
        val started = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val installed = CountDownLatch(1)
        val installAttempted = CountDownLatch(1)
        val published = AtomicReference("initial")
        val failure = AtomicReference<Throwable?>(null)
        val start = Thread {
            runCatching {
                gate.begin {
                    started.countDown()
                    check(releaseStart.await(2, TimeUnit.SECONDS))
                    published.set("old-start")
                }
            }.exceptionOrNull()?.let(failure::set)
        }
        val install = Thread {
            installAttempted.countDown()
            gate.invalidate { published.set("reset"); installed.countDown() }
        }
        try {
            start.start()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            install.start()
            assertTrue(installAttempted.await(2, TimeUnit.SECONDS))
            assertFalse(installed.await(150, TimeUnit.MILLISECONDS))
        } finally {
            releaseStart.countDown()
            start.join(2_000)
            install.join(2_000)
        }
        assertNull(failure.get())
        assertFalse(start.isAlive || install.isAlive)
        assertEquals("reset", published.get())
    }

    @Test fun `overlapping requests coalesce into one pending rerun`() {
        val gate = EmbeddingPassGate()
        val first = requireNotNull(gate.begin())
        assertNull(gate.begin())
        assertNull(gate.begin())
        gate.finish(first) {}
        assertTrue(gate.takePendingRerun())
        assertFalse(gate.takePendingRerun())
        assertNotNull(gate.begin())
    }

    @Test fun `installation rejects old ready and empty publication atomically`() {
        val gate = EmbeddingPassGate()
        var published = "old-model"
        val first = requireNotNull(gate.begin())
        gate.invalidate { published = "reset-not-ready" }
        gate.finish(first) { published = "old-model-ready-empty" }
        assertEquals("reset-not-ready", published)
        assertTrue(gate.takePendingRerun())
    }

    @Test fun `a new generation publishes after the obsolete pass releases`() {
        val gate = EmbeddingPassGate()
        var published = "initial"
        val old = requireNotNull(gate.begin())
        gate.invalidate { published = "reset" }
        gate.finish(old) { published = "old" }
        gate.takePendingRerun()
        val fresh = requireNotNull(gate.begin())
        gate.finish(fresh) { published = "new-model" }
        assertEquals("new-model", published)
        assertNotNull(gate.begin())
    }

    @Test fun `stale tickets cannot finish or release another active pass`() {
        val gate = EmbeddingPassGate()
        val old = requireNotNull(gate.begin())
        gate.finish(old) {}
        val fresh = requireNotNull(gate.begin())
        assertThrows(IllegalArgumentException::class.java) { gate.finish(old) {} }
        gate.finish(fresh) {}
        assertNotNull(gate.begin())
    }

    @Test fun `exceptional publication frees active work and preserves queued rerun`() {
        val gate = EmbeddingPassGate()
        val first = requireNotNull(gate.begin())
        gate.begin()
        assertThrows(IllegalStateException::class.java) { gate.finish(first) { error("failed publish") } }
        assertTrue(gate.takePendingRerun())
        assertNotNull(gate.begin())
    }
}
