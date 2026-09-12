package com.slukhayka.audiobooks.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #527 — the live download-permission gate: a source that declares the live
 * rules fact downloads only under a FRESH ALLOWED verdict, and every other
 * state (no record, stale, DENIED, unreadable) keeps it OFF.
 */
class SourceDownloadPermissionTest {

    private fun store(): Pair<SourceDownloadPermissionStore, File> {
        val file = File.createTempFile("download-permissions", ".tsv").apply { delete() }
        return SourceDownloadPermissionStore(file) to file
    }

    // --- the pure robots policy -------------------------------------------

    @Test
    fun `a disallowed media path is denied and the rest is allowed`() {
        val robots = """
            User-agent: *
            Disallow: /uk-audio
            Disallow: /private
        """.trimIndent()

        assertEquals(DownloadPermissionVerdict.DENIED, RobotsDownloadRules.verdictFor(robots, "/uk-audio-6163-x"))
        assertEquals(DownloadPermissionVerdict.ALLOWED, RobotsDownloadRules.verdictFor(robots, "/uk"))
        assertFalse(RobotsDownloadRules.verdictFor(robots, "/uk") == DownloadPermissionVerdict.DENIED)
    }

    @Test
    fun `a longer Allow beats a shorter Disallow`() {
        val robots = """
            User-agent: *
            Disallow: /uk-audio
            Allow: /uk-audio-public
        """.trimIndent()

        assertEquals(DownloadPermissionVerdict.ALLOWED, RobotsDownloadRules.verdictFor(robots, "/uk-audio-public-1"))
        assertEquals(DownloadPermissionVerdict.DENIED, RobotsDownloadRules.verdictFor(robots, "/uk-audio-private-1"))
    }

    @Test
    fun `another agent's rules never decide for us`() {
        val robots = """
            User-agent: Yandex
            Disallow: /
        """.trimIndent()

        assertEquals(DownloadPermissionVerdict.UNKNOWN, RobotsDownloadRules.verdictFor(robots, "/uk"))
    }

    @Test
    fun `an empty or valueless document is unknown, never an allow`() {
        assertEquals(DownloadPermissionVerdict.UNKNOWN, RobotsDownloadRules.verdictFor("", "/uk"))
        assertEquals(DownloadPermissionVerdict.UNKNOWN, RobotsDownloadRules.verdictFor("   \n", "/uk"))
        assertEquals(
            DownloadPermissionVerdict.ALLOWED,
            RobotsDownloadRules.verdictFor("User-agent: *\nDisallow:", "/uk")
        )
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val robots = """
            # the site's rules
            User-agent: *   # everyone

            Disallow: /audio  # the media folder
        """.trimIndent()

        assertEquals(DownloadPermissionVerdict.DENIED, RobotsDownloadRules.verdictFor(robots, "/audio/x.mp3"))
    }

    // --- the persisted verdict --------------------------------------------

    @Test
    fun `a verdict is fresh only inside its week`() {
        val (store, file) = store()
        try {
            store.record(DownloadPermissionRecord(DownloadPermissionVerdict.ALLOWED, 1_000_000L), "audiobookmp3")

            assertEquals(
                DownloadPermissionVerdict.ALLOWED,
                store.freshVerdict("audiobookmp3", 1_000_000L + SourceDownloadPermissionStore.TTL_MS - 1)
            )
            assertEquals(
                "stale is unknown",
                DownloadPermissionVerdict.UNKNOWN,
                store.freshVerdict("audiobookmp3", 1_000_000L + SourceDownloadPermissionStore.TTL_MS)
            )
            assertEquals(
                "an absent source is unknown",
                DownloadPermissionVerdict.UNKNOWN,
                store.freshVerdict("soundbooks", 1_000_000L)
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a broken store file is empty, never a crash`() {
        val file = File.createTempFile("download-permissions", ".tsv")
        try {
            file.writeText("garbage\n");
            assertTrue(SourceDownloadPermissionStore(file).load().isEmpty())
        } finally {
            file.delete()
        }
    }

    // --- the gate ----------------------------------------------------------

    @Test
    fun `a flagged source downloads only with a fresh allow`() {
        val (store, file) = store()
        try {
            val now = 1_000_000L
            assertFalse("no record = OFF", downloadPermittedFor("audiobookmp3", store, now))

            store.record(DownloadPermissionRecord(DownloadPermissionVerdict.DENIED, now), "audiobookmp3")
            assertFalse("a denial = OFF", downloadPermittedFor("audiobookmp3", store, now))

            store.record(DownloadPermissionRecord(DownloadPermissionVerdict.ALLOWED, now), "audiobookmp3")
            assertTrue("a fresh allow = ON", downloadPermittedFor("audiobookmp3", store, now))

            val stale = now + SourceDownloadPermissionStore.TTL_MS
            assertFalse("a stale allow = OFF", downloadPermittedFor("audiobookmp3", store, stale))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `an unflagged source is unaffected and a refusal always wins`() {
        val (store, file) = store()
        try {
            assertTrue("soundbooks keeps its existing permission", downloadPermittedFor("soundbooks", store))
            assertFalse("lihtar is stream-only", downloadPermittedFor("lihtar", store))
            assertFalse("4read is a scam", downloadPermittedFor("4read", store))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a null store keeps a flagged source off`() {
        assertFalse(downloadPermittedFor("audiobookmp3", null))
    }

    // --- the live check ----------------------------------------------------

    @Test
    fun `the live check records a verdict only when robots answered`() {
        val file = File.createTempFile("download-permissions", ".tsv").apply { delete() }
        try {
            val store = SourceDownloadPermissionStore(file)
            val fetcher = com.slukhayka.audiobooks.testing.FakeFetcher(
                mapOf("https://audiobook-mp3.com/robots.txt" to "User-agent: *\nDisallow: /private")
            )
            val refresh = SourceDownloadPermissionRefresh(
                fetcher = fetcher,
                store = store,
                clock = { 1_000_000L }
            )

            val recorded = kotlinx.coroutines.runBlocking { refresh.refreshOnce() }

            assertEquals(1, recorded)
            assertEquals(
                DownloadPermissionVerdict.ALLOWED,
                store.freshVerdict("audiobookmp3", 1_000_000L)
            )
            assertEquals("one request per declared source", 1, fetcher.requestedUrls.size)
            assertTrue(fetcher.requestedUrls.single().endsWith("/robots.txt"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a blank robots answer records nothing`() {
        val file = File.createTempFile("download-permissions", ".tsv").apply { delete() }
        try {
            val store = SourceDownloadPermissionStore(file)
            val refresh = SourceDownloadPermissionRefresh(
                fetcher = com.slukhayka.audiobooks.testing.FakeFetcher(),
                store = store,
                clock = { 1_000_000L }
            )

            assertEquals(0, kotlinx.coroutines.runBlocking { refresh.refreshOnce() })
            assertEquals(DownloadPermissionVerdict.UNKNOWN, store.freshVerdict("audiobookmp3", 1_000_000L))
        } finally {
            file.delete()
        }
    }
}
