package com.slukhayka.audiobooks.data.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #527 — the per-source Referer allowlist: the site referer reaches the source
 * and its own media CDN only, never an unrelated third party, and an adapter
 * that names no hosts keeps the legacy any-host rule.
 */
class RefererPolicyTest {

    /** The audiobookmp3 allowlist: the site + its redirectto.cc media CDN. */
    private val audiobookMp3Hosts = setOf("audiobook-mp3.com", "redirectto.cc")

    @Test
    fun `the referer travels to the source itself and its subdomains`() {
        assertTrue(refererAllowedFor("https://audiobook-mp3.com/uk-audio-6163-knyga", audiobookMp3Hosts))
        assertTrue(refererAllowedFor("https://cdn.audiobook-mp3.com/audiobooks/uk/1/2/cover.webp", audiobookMp3Hosts))
    }

    @Test
    fun `the referer travels to the media cdn`() {
        assertTrue(
            refererAllowedFor(
                "https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/26720.pl.txt",
                audiobookMp3Hosts
            )
        )
        assertTrue(
            refererAllowedFor(
                "https://9giiu0g54k8c.redirectto.cc/s05/2/6/7/2/0/track-0.mp3",
                audiobookMp3Hosts
            )
        )
    }

    @Test
    fun `the referer never leaks to an unrelated host`() {
        assertFalse(refererAllowedFor("https://evil.example/track-0.mp3", audiobookMp3Hosts))
        assertFalse(refererAllowedFor("https://notaudiobook-mp3.com/x", audiobookMp3Hosts))
        // A host that merely CONTAINS an allowed name is not a subdomain of it.
        assertFalse(refererAllowedFor("https://audiobook-mp3.com.evil.example/x", audiobookMp3Hosts))
    }

    @Test
    fun `an unparseable url never receives the referer`() {
        assertFalse(refererAllowedFor("not a url", audiobookMp3Hosts))
        assertFalse(refererAllowedFor("", audiobookMp3Hosts))
    }

    @Test
    fun `an empty allowlist keeps the legacy any-host rule`() {
        assertTrue(refererAllowedFor("https://anything.example/x", emptySet()))
    }
}
