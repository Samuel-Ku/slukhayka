package com.slukhayka.audiobooks.data.identity

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-40 #275 (t1) — the GENERATED permanent credentials: random
 * `<rand>@slukhayka.local` email + random password. Pure; a seeded Random
 * pins determinism.
 */
class GeneratedCredentialsTest {

    @Test
    fun `a seeded generator produces deterministic pairs`() {
        val a = GeneratedCredentials.generate(Random(7))
        val b = GeneratedCredentials.generate(Random(7))

        assertEquals(a.email, b.email)
        assertEquals(a.password, b.password)
    }

    @Test
    fun `the email rides the fake local domain`() {
        repeat(100) { seed ->
            val pair = GeneratedCredentials.generate(Random(seed))

            assertTrue(pair.email.endsWith("@${GeneratedCredentials.EMAIL_DOMAIN}"))
            assertTrue(pair.email.substringBefore('@').isNotEmpty())
        }
    }

    @Test
    fun `the password uses the lowercase alphanumeric alphabet only`() {
        val allowed = Regex("[a-z0-9]{${GeneratedCredentials.PASSWORD_LENGTH}}")
        repeat(50) { seed ->
            val pair = GeneratedCredentials.generate(Random(seed))

            assertTrue(pair.password.matches(allowed))
        }
    }

    @Test
    fun `two draws differ - the credentials are per-install random`() {
        val a = GeneratedCredentials.generate(Random(1))
        val b = GeneratedCredentials.generate(Random(2))

        assertNotEquals(a.email, b.email)
        assertNotEquals(a.password, b.password)
    }
}
