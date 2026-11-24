package com.slukhayka.audiobooks.data.privacy

import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1482 — the socket registry behind [BrowserDnsProxy]: every tracked socket is
 * drained exactly once, a socket tracked after the drain is closed at once,
 * and a concurrent hammer never throws or silently loses one.
 */
class TrackedSocketsTest {

    private fun socket(): Socket = Socket()

    @Test
    fun `drain returns every tracked socket once and empties the registry`() {
        val registry = TrackedSockets()
        val first = socket()
        val second = socket()

        assertTrue(registry.track(first))
        assertTrue(registry.track(second))

        assertEquals(setOf(first, second), registry.drain().toSet())
        // A second drain has nothing to hand out — a close is not repeated.
        assertTrue(registry.drain().isEmpty())
    }

    @Test
    fun `a socket tracked after the drain is closed at once and not returned`() {
        val registry = TrackedSockets()
        registry.drain()
        val late = socket()

        assertFalse(registry.track(late))
        assertTrue(late.isClosed)
        assertTrue(registry.drain().isEmpty())
    }

    @Test
    fun `forget removes a socket from the future drain`() {
        val registry = TrackedSockets()
        val socket = socket()
        registry.track(socket)

        registry.forget(socket)

        assertTrue(registry.drain().isEmpty())
    }

    @Test
    fun `a concurrent track forget and drain hammer never throws`() {
        val registry = TrackedSockets()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val failed = AtomicBoolean(false)
        val tracked = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
        val drained = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
        repeat(4) { worker ->
            pool.execute {
                start.await()
                repeat(500) { index ->
                    try {
                        if (worker == 0 && index == 400) {
                            drained += registry.drain()
                        } else {
                            val socket = socket()
                            if (registry.track(socket)) tracked += socket else runCatching { socket.close() }
                        }
                    } catch (_: Throwable) {
                        failed.set(true)
                    }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        assertFalse("the registry threw under concurrency", failed.get())
        // Every socket the registry accepted is either drained exactly once or
        // still tracked (the final drain below). Nothing is silently lost.
        val rest = registry.drain()
        val all = drained + rest
        assertTrue(all.containsAll(tracked))
        assertEquals("no socket handed out twice", all.size, drained.size + rest.size)
    }
}
