package com.slukhayka.audiobooks.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-42 #427 — source-scoped browser boundary, pure JVM.
 *
 * Upper navigation (in-page links, address field, programmatic loads) accepts
 * only http(s) URLs from the allowlist of the current source; the address
 * field cannot bypass it. The allowlist is host-aware and subdomain-aware.
 */
class SourceBrowserPolicyTest {

    @Test
    fun `sluhay allowlist accepts its own http and https urls`() {
        assertTrue(SourceBrowserPolicy.isUrlAllowed("https://sluhay.com/", "sluhay"))
        assertTrue(SourceBrowserPolicy.isUrlAllowed("http://sluhay.com/book.html", "sluhay"))
        assertTrue(SourceBrowserPolicy.isUrlAllowed("https://sluhay.com/svitova-literatura/6150.html", "sluhay"))
        assertTrue(SourceBrowserPolicy.isUrlAllowed("https://www.sluhay.com/page", "sluhay"))
        assertTrue(SourceBrowserPolicy.isUrlAllowed("https://sub.sluhay.com/extra", "sluhay"))
    }

    @Test
    fun `sluhay allowlist rejects external hosts`() {
        assertFalse(SourceBrowserPolicy.isUrlAllowed("https://4read.org/book.html", "sluhay"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("https://sluhay.com.evil.com/", "sluhay"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("https://evil.com/", "sluhay"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("https://sluhay.com.evil.com/path", "sluhay"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("https://redirectto.cc/s05/track.mp3", "sluhay"))
    }

    @Test
    fun `sluhayknigi allowlist is independent from sluhay`() {
        assertTrue(SourceBrowserPolicy.isUrlAllowed("https://sluhayknigi.com/page", "sluhayknigi"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("https://sluhay.com/page", "sluhayknigi"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("https://sluhayknigi.com/page", "sluhay"))
    }

    @Test
    fun `4read allowlist accepts main and audio hosts as separate entries`() {
        assertTrue(SourceBrowserPolicy.isUrlAllowed("https://4read.org/book.html", "4read"))
        assertTrue(SourceBrowserPolicy.isUrlAllowed("https://www.4read.org/search", "4read"))
        assertTrue(SourceBrowserPolicy.isUrlAllowed("https://sub.4read.org/page", "4read"))
        assertTrue(SourceBrowserPolicy.isUrlAllowed("https://s1.reasd.org/5370/01.mp3", "4read"))
        assertTrue(SourceBrowserPolicy.isUrlAllowed("https://reasd.org/audio.mp3", "4read"))
        assertTrue(SourceBrowserPolicy.isUrlAllowed("https://sub.reasd.org/track", "4read"))
    }

    @Test
    fun `4read allowlist rejects third-party hosts`() {
        assertFalse(SourceBrowserPolicy.isUrlAllowed("https://sluhay.com/page", "4read"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("https://evil.com/", "4read"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("https://4read.org.evil.com/", "4read"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("https://google.com/", "4read"))
    }

    @Test
    fun `4read audio hosts cover the live manifest and track shapes`() {
        // Live September 2026 forms (verified against the captured DOM):
        // the manifest on 4read.org itself, tracks on s1.reasd.org.
        assertTrue(SourceBrowserPolicy.allowsAudioHost("4read", "4read.org", "4read.org"))
        assertTrue(SourceBrowserPolicy.allowsAudioHost("4read", "s1.reasd.org", "4read.org"))
        assertFalse(SourceBrowserPolicy.allowsAudioHost("4read", "evil.com", "4read.org"))
        assertFalse(SourceBrowserPolicy.allowsAudioHost("4read", null, "4read.org"))
    }

    @Test
    fun `only http and https are ever allowed`() {
        assertFalse(SourceBrowserPolicy.isUrlAllowed("ftp://sluhay.com/file", "sluhay"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("file:///sdcard/book.mp3", "sluhay"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("javascript:alert(1)", "sluhay"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("content://media/audio", "sluhay"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("data:text/html,hi", "sluhay"))
        assertTrue(SourceBrowserPolicy.isUrlAllowed("http://sluhay.com/", "sluhay"))
        assertTrue(SourceBrowserPolicy.isUrlAllowed("https://sluhay.com/", "sluhay"))
    }

    @Test
    fun `unknown source has empty allowlist and rejects everything`() {
        assertFalse(SourceBrowserPolicy.isUrlAllowed("https://sluhay.com/", "unknown"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("https://4read.org/", "unknown"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("https://4read.org/", ""))
        assertEquals(emptySet<String>(), SourceBrowserPolicy.allowedHostsFor("unknown"))
    }

    @Test
    fun `malformed and blank urls are not allowed`() {
        assertFalse(SourceBrowserPolicy.isUrlAllowed("", "sluhay"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("   ", "sluhay"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("not a url", "sluhay"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("https://", "sluhay"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed("https:///path", "sluhay"))
    }

    @Test
    fun `host matching is case-insensitive and strips www`() {
        assertTrue(SourceBrowserPolicy.isUrlAllowed("https://WWW.SLUHAY.COM/Page", "sluhay"))
        assertTrue(SourceBrowserPolicy.isUrlAllowed("https://SLUHAY.COM/page", "sluhay"))
        assertTrue(SourceBrowserPolicy.isUrlAllowed("https://WWW.4READ.ORG/page", "4read"))
    }

    @Test
    fun `address field cannot bypass - same gate as navigation`() {
        // The address field builds https://<input> when no scheme is present;
        // the gate must still reject off-allowlist hosts.
        val sluhayInput = "sluhay.com/svitova-literatura/1.html"
        val sluhayTarget = "https://$sluhayInput"
        val evilInput = "evil.com/steal"
        val evilTarget = "https://$evilInput"
        assertTrue(SourceBrowserPolicy.isUrlAllowed(sluhayTarget, "sluhay"))
        assertFalse(SourceBrowserPolicy.isUrlAllowed(evilTarget, "sluhay"))
        // For 4read source, sluhay target must be blocked even though it's valid https
        assertFalse(SourceBrowserPolicy.isUrlAllowed(sluhayTarget, "4read"))
    }

    @Test
    fun `isCookieHostAllowed mirrors navigation allowlist`() {
        assertTrue(SourceBrowserPolicy.isCookieHostAllowed("https://sluhay.com/page", "sluhay"))
        assertFalse(SourceBrowserPolicy.isCookieHostAllowed("https://4read.org/page", "sluhay"))
        assertTrue(SourceBrowserPolicy.isCookieHostAllowed("https://s1.reasd.org/audio.mp3", "4read"))
    }

    @Test
    fun `extractHost normalises www and lowercases`() {
        assertEquals("sluhay.com", SourceBrowserPolicy.extractHost("https://www.sluhay.com/page"))
        assertEquals("4read.org", SourceBrowserPolicy.extractHost("https://4READ.ORG/BOOK"))
        assertEquals(null, SourceBrowserPolicy.extractHost("not a url"))
        assertEquals(null, SourceBrowserPolicy.extractHost(""))
    }
}
