package com.slukhayka.audiobooks.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #526 — the persisted sitemap validators: a round-trip keeps both validator
 * kinds, and a missing or damaged file is an empty map, never a crash.
 */
class SitemapValidatorStoreTest {

    @Test
    fun `validators round-trip through the file`() {
        val file = File.createTempFile("validators", ".tsv").apply { delete() }
        try {
            val store = SitemapValidatorStore(file)
            store.save(
                mapOf(
                    "https://audiobook.co.ua/post-sitemap.xml" to SitemapValidator("\"v1\"", "Wed, 10 Sep 2026 10:00:00 GMT"),
                    "https://chytaylo.com.ua/sitemap.xml" to SitemapValidator(null, "Tue, 09 Sep 2026 10:00:00 GMT")
                )
            )

            val loaded = store.load()

            assertEquals("\"v1\"", loaded.getValue("https://audiobook.co.ua/post-sitemap.xml").etag)
            assertEquals(
                "Wed, 10 Sep 2026 10:00:00 GMT",
                loaded.getValue("https://audiobook.co.ua/post-sitemap.xml").lastModified
            )
            assertEquals(null, loaded.getValue("https://chytaylo.com.ua/sitemap.xml").etag)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a missing or damaged file is an empty map`() {
        val missing = File.createTempFile("validators", ".tsv").apply { delete() }
        assertTrue(SitemapValidatorStore(missing).load().isEmpty())

        val damaged = File.createTempFile("validators", ".tsv")
        try {
            damaged.writeText("garbage without tabs")
            assertTrue(SitemapValidatorStore(damaged).load().isEmpty())
        } finally {
            damaged.delete()
        }
    }
}
