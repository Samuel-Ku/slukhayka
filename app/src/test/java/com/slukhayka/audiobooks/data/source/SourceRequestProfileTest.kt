package com.slukhayka.audiobooks.data.source

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ADR-0040 — the adapter declares its politeness profile per endpoint; the
 * seam reads the declaration, features never classify. The default numbers
 * are the ADR's starting settings (spec #681), and an adapter whose reality
 * differs overrides one endpoint — the seam reads the override.
 */
class SourceRequestProfileTest {

    private val adapter = object : SourceAdapter {
        override val sourceId: String = "profile-test"
        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            SourceBookDetail("", "", url = url, chapters = emptyList())
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
    }

    @Test
    fun `search is a listener action cached for 24 hours`() {
        val profile = adapter.requestProfile(SourceEndpoint.SEARCH)
        assertEquals(SourceRequestClass.LISTENER_ACTION, profile.requestClass)
        assertEquals(24 * 60 * 60 * 1000L, profile.cacheTtlMillis)
    }

    @Test
    fun `enumeration rides the ttl refresh lane with the adr ttl numbers`() {
        val newFeed = adapter.requestProfile(SourceEndpoint.NEW_FEED)
        assertEquals(SourceRequestClass.TTL_REFRESH, newFeed.requestClass)
        assertEquals(6 * 60 * 60 * 1000L, newFeed.cacheTtlMillis)

        val catalog = adapter.requestProfile(SourceEndpoint.CATALOG)
        assertEquals(SourceRequestClass.TTL_REFRESH, catalog.requestClass)
        assertEquals(24 * 60 * 60 * 1000L, catalog.cacheTtlMillis)
    }

    @Test
    fun `a book page is a listener resolve with no cache and covers are the lowest class`() {
        val page = adapter.requestProfile(SourceEndpoint.BOOK_PAGE)
        assertEquals(SourceRequestClass.LISTENER_ACTION, page.requestClass)
        assertEquals(0L, page.cacheTtlMillis)

        val cover = adapter.requestProfile(SourceEndpoint.COVER)
        assertEquals(SourceRequestClass.COVER, cover.requestClass)
        assertEquals(0L, cover.cacheTtlMillis)
    }

    @Test
    fun `an adapter override is respected by the seam`() {
        val overriding = object : SourceAdapter {
            override val sourceId: String = "profile-test"
            override suspend fun search(query: String): List<SourceBook> = emptyList()
            override suspend fun fetchBookPage(url: String): SourceBookDetail =
                SourceBookDetail("", "", url = url, chapters = emptyList())
            override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()

            override fun requestProfile(endpoint: SourceEndpoint): SourceRequestProfile =
                if (endpoint == SourceEndpoint.NEW_FEED) {
                    SourceRequestProfile(SourceRequestClass.TTL_REFRESH, 0L)
                } else {
                    super.requestProfile(endpoint)
                }
        }

        assertEquals(0L, overriding.requestProfile(SourceEndpoint.NEW_FEED).cacheTtlMillis)
        assertEquals(
            24 * 60 * 60 * 1000L,
            overriding.requestProfile(SourceEndpoint.SEARCH).cacheTtlMillis
        )
    }
}