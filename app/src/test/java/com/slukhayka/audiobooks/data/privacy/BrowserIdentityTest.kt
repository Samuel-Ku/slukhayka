package com.slukhayka.audiobooks.data.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-38 T1 (#253) — the User-Agent strategy: the transport sends the real
 * system WebView UA of the device (reported and cached once per process);
 * until one is reported (JVM environment, cold start) a static generic
 * fallback answers. The fallback must NOT carry a concrete device model —
 * that fingerprint was the bug SEC-018 removed from the player.
 */
class BrowserIdentityTest {

    @Test
    fun `before any report the static fallback answers`() {
        BrowserIdentity.resetForTest()
        assertEquals(BrowserIdentity.FALLBACK_USER_AGENT, BrowserIdentity.currentUserAgent())
    }

    @Test
    fun `a reported system WebView UA wins`() {
        BrowserIdentity.resetForTest()
        BrowserIdentity.reportSystemUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile")
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile",
            BrowserIdentity.currentUserAgent()
        )
    }

    @Test
    fun `a blank report never overwrites`() {
        BrowserIdentity.resetForTest()
        BrowserIdentity.reportSystemUserAgent("   ")
        assertEquals(BrowserIdentity.FALLBACK_USER_AGENT, BrowserIdentity.currentUserAgent())
    }

    @Test
    fun `the fallback leaks no device model`() {
        // No concrete model token («SM-S918B» was the old leak) and no app name.
        assertFalse("SM-" in BrowserIdentity.FALLBACK_USER_AGENT)
        assertFalse("slukhay" in BrowserIdentity.FALLBACK_USER_AGENT.lowercase())
        assertFalse("4read" in BrowserIdentity.FALLBACK_USER_AGENT.lowercase())
    }

    @Test
    fun `the fallback looks like an ordinary Android browser`() {
        assertTrue(BrowserIdentity.FALLBACK_USER_AGENT.startsWith("Mozilla/5.0"))
        assertTrue("Chrome/" in BrowserIdentity.FALLBACK_USER_AGENT)
        assertTrue("Mobile Safari" in BrowserIdentity.FALLBACK_USER_AGENT)
    }
}
