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
    // #948 — Robolectric's CookieManager is NOT a stable singleton across calls.
    // ShadowCookieManager keeps its store behind a static, and its @Resetter
    // nulls that static; a later `getInstance()` therefore builds a DIFFERENT,
    // empty RoboCookieManager. Measured on this class: ~1 run in 25, mid-method,
    // exactly between a `setCookie` and the next `getInstance()` read — the write
    // landed in `setStore=sluhay.com/challenge` while the read got `getStore=` on
    // another instance hash. A getter that calls `getInstance()` per use (the
    // old shape) therefore races its own fixture: the cookie is written into a
    // store the assertion's read never sees, and the test fails at the first
    // assertion with "browserOnly" — on PRs that do not touch cookies at all.
    //
    // Pin ONE manager per test instance so the write, the callback-free `flush`
    // and every read below share one store. This is a fixture, not a weakened
    // check: every assertion still runs, and the precondition is asserted
    // explicitly instead of assumed.
    private val cookies: CookieManager by lazy { CookieManager.getInstance() }

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
        // The write is a real state change, so prove the fixture's post-state
        // before asserting on the policy built from it: a failure here means the
        // cookie never became visible, not that the policy is wrong.
        assertTrue(
            "CookieManager не бачить щойно записаний кукі — фікстура не готова",
            hasUsableSourceSession(cookies.getCookie(book.url).orEmpty())
        )
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
