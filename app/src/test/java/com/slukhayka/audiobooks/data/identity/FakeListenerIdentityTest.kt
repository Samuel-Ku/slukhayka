package com.slukhayka.audiobooks.data.identity

import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec-40 #275 (t1) — the seam contract, exercised over the JVM fake:
 * ensure() is IDEMPOTENT (one identity per store lifetime), the nickname is
 * editable and visible through current(), and independent instances never
 * share state.
 */
class FakeListenerIdentityTest {

    @Test
    fun `ensure is idempotent - one identity for the whole lifetime`() = runBlocking {
        val identity = FakeListenerIdentity(Random(5))

        val first = identity.ensure()
        repeat(3) {
            val again = identity.ensure()

            assertEquals(first.uid, again.uid)
            assertEquals(first.nickname, again.nickname)
        }
    }

    @Test
    fun `current reflects the ensured profile`() = runBlocking {
        val identity = FakeListenerIdentity(Random(6))

        assertNull(identity.current())

        val profile = identity.ensure()

        assertEquals(profile, identity.current())
    }

    @Test
    fun `a set nickname replaces the default and survives re-ensure`() = runBlocking {
        val identity = FakeListenerIdentity(Random(7))
        val default = identity.ensure()

        identity.setNickname("  Мар'яно-Слухачка  ")

        assertEquals("Мар'яно-Слухачка", identity.ensure().nickname)
        assertEquals("Мар'яно-Слухачка", identity.current()?.nickname)
        assertNotEquals(default.nickname, identity.current()?.nickname)
    }

    @Test
    fun `a blank nickname is ignored - the profile stays intact`() = runBlocking {
        val identity = FakeListenerIdentity(Random(8))
        val profile = identity.ensure()

        identity.setNickname("   ")

        assertEquals(profile, identity.current())
    }

    @Test
    fun `independent identities are independent`() = runBlocking {
        val a = FakeListenerIdentity(Random(9))
        val b = FakeListenerIdentity(Random(10))

        val pa = a.ensure()
        b.setNickname("Інший")

        assertEquals(pa, a.current())
        assertNotEquals(pa.nickname, b.ensure().nickname)
    }
}
