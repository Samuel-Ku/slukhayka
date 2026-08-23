package com.slukhayka.audiobooks.data.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-40 #276 (t2) — the recovery-code codec: round-trip and honest
 * garbage → null (never a crash), per the ticket contract.
 */
class RecoveryCodecTest {

    @Test
    fun `a credential pair round-trips`() {
        val code = RecoveryCodec.encode("x7k2p9q1a8b3c4d5e6f7@slukhayka.local", "p4ssw0rd-with-symbols-!@#")

        assertEquals("x7k2p9q1a8b3c4d5e6f7@slukhayka.local" to "p4ssw0rd-with-symbols-!@#", RecoveryCodec.decode(code))
    }

    @Test
    fun `unicode survives the round-trip`() {
        val code = RecoveryCodec.encode("слухач@slukhayka.local", "пароль-123")

        assertEquals("слухач@slukhayka.local" to "пароль-123", RecoveryCodec.decode(code))
    }

    @Test
    fun `the generated credentials round-trip end to end`() {
        repeat(20) { seed ->
            val pair = GeneratedCredentials.generate(kotlin.random.Random(seed))
            val code = RecoveryCodec.encode(pair.email, pair.password)

            assertTrue(code.startsWith(RecoveryCodec.PREFIX + "."))
            assertEquals(pair.email to pair.password, RecoveryCodec.decode(code))
        }
    }

    @Test
    fun `the code carries no raw secret material in plain text`() {
        val code = RecoveryCodec.encode("secret-email@slukhayka.local", "secret-password")

        assertFalse(code.contains("secret-email"))
        assertFalse(code.contains("secret-password"))
    }

    @Test
    fun `garbage decodes to null`() {
        assertNull(RecoveryCodec.decode(""))
        assertNull(RecoveryCodec.decode("   "))
        assertNull(RecoveryCodec.decode("hello world this is not a code"))
        assertNull(RecoveryCodec.decode("SLK1.!!!not-base64!!!"))
        assertNull(RecoveryCodec.decode("SLK1.aGVsbG8")) // valid base64url, no separator inside
        assertNull(RecoveryCodec.decode("SLK1.")) // empty payload
    }

    @Test
    fun `a foreign prefix decodes to null`() {
        val code = RecoveryCodec.encode("a@slukhayka.local", "pw").replaceFirst("SLK1", "SLK2")

        assertNull(RecoveryCodec.decode(code))
    }

    @Test
    fun `a missing half decodes to null`() {
        val noEmail = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(":pw".toByteArray())
        val noPassword = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("user@slukhayka.local:".toByteArray())

        assertNull(RecoveryCodec.decode("SLK1.$noEmail"))
        assertNull(RecoveryCodec.decode("SLK1.$noPassword"))
    }

    @Test
    fun `implausible lengths decode to null`() {
        val overlongEmailCode = "SLK1." + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(("a".repeat(300) + ":pw").toByteArray())
        val overlongPasswordCode = "SLK1." + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(("a@slukhayka.local:" + "p".repeat(300)).toByteArray())

        assertNull(RecoveryCodec.decode(overlongEmailCode))
        assertNull(RecoveryCodec.decode(overlongPasswordCode))
    }
}
