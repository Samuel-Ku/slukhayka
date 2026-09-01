package com.slukhayka.audiobooks.ui.screens

import android.webkit.CookieManager
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.source.SourceAccessMode
import com.slukhayka.audiobooks.data.source.SourceSelectionCoordinator
import com.slukhayka.audiobooks.ui.catalog.catalogSessionCandidates
import com.slukhayka.audiobooks.ui.catalog.hasUsableSourceSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SourceWebViewSessionIntegrationTest {
    private val cookies get() = CookieManager.getInstance()

    @Before
    fun resetCookies() {
        cookies.removeAllCookies(null)
        cookies.flush()
    }

    @After
    fun cleanCookies() {
        cookies.removeAllCookies(null)
        cookies.flush()
    }

    @Test
    fun `one CookieManager session serves two books and clearing stays source scoped`() {
        cookies.setCookie(
            "https://www.sluhay.com/books/first",
            "session=sluhay-ok; Path=/books/; Domain=sluhay.com; Secure"
        )
        cookies.setCookie("https://4read.org/", "session=fourread-ok; Path=/; Secure")
        cookies.flush()

        val first = source("first", "https://www.sluhay.com/books/first")
        val second = source("second", "https://sluhay.com/books/second")
        assertEquals(sessionThenBrowser, candidates(first))
        assertEquals(sessionThenBrowser, candidates(second))

        SourceWebViewSession.rememberVisitedUrl("sluhay", first.url)
        SourceWebViewSession.clear("sluhay")

        assertFalse(cookies.getCookie(first.url).orEmpty().contains("session=sluhay-ok"))
        assertTrue(cookies.getCookie("https://4read.org/book").orEmpty().contains("session=fourread-ok"))
        assertEquals(browserOnly, candidates(second))
    }

    @Test
    fun `CookieManager expiry returns the book to the repeated browser challenge`() {
        val book = source("expired", "https://sluhay.com/books/expired")
        cookies.setCookie(book.url, "challenge=ready; Path=/books; Secure")
        cookies.flush()
        assertEquals(sessionThenBrowser, candidates(book))

        cookies.setCookie(book.url, "challenge=; Max-Age=0; Path=/books; Secure")
        cookies.flush()

        assertEquals(browserOnly, candidates(book))
    }

    private fun candidates(source: SourceEntity) = catalogSessionCandidates(
        source = source,
        mode = SourceAccessMode.BROWSER,
        hasFirstPartySession = hasUsableSourceSession(cookies.getCookie(source.url).orEmpty())
    ).map { it.category }

    private fun source(id: String, url: String) = SourceEntity(
        id = id,
        bookId = "work-$id",
        editionId = "edition-$id",
        type = "sluhay",
        url = url
    )

    private val sessionThenBrowser = listOf(
        SourceSelectionCoordinator.SourceCategory.UNKNOWN,
        SourceSelectionCoordinator.SourceCategory.BROWSER
    )
    private val browserOnly = listOf(SourceSelectionCoordinator.SourceCategory.BROWSER)
}
