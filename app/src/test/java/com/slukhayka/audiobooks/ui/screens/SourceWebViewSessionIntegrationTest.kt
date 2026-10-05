package com.slukhayka.audiobooks.ui.screens

import android.webkit.CookieManager
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.source.AndroidCookieJar
import com.slukhayka.audiobooks.data.source.SourceAccessMode
import com.slukhayka.audiobooks.data.source.SourceSelectionCoordinator
import com.slukhayka.audiobooks.ui.catalog.catalogSessionCandidates
import com.slukhayka.audiobooks.ui.catalog.hasUsableSourceSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCookieManager

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
    // The fixture therefore reads the jar through the SAME door the code under
    // test uses (`AndroidCookieJar`, pinned once per process — see the
    // regression test below), never through its own `getInstance()`. So the
    // write, the callback-free `flush`, `clear` and every read share one store
    // by construction, and this class measures the real production path instead
    // of a parallel one. This is a fixture, not a weakened check: every
    // assertion still runs, and each precondition is asserted explicitly
    // instead of assumed.
    private val cookies: CookieManager get() = AndroidCookieJar.instance()

    @Before
    fun resetCookies() {
        SourceWebViewSession.resetForTest()
        cookies.removeAllCookies(null)
        cookies.flush()
    }

    @After
    fun cleanCookies() {
        cookies.removeAllCookies(null)
        cookies.flush()
        SourceWebViewSession.resetForTest()
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
        // Precondition, same discipline as the expiry test below: prove the
        // jar really holds both sessions before asserting the policy built
        // from them, so a fixture miss reads as a fixture miss.
        assertTrue(
            "CookieManager не бачить sluhay-сесію — фікстура не готова",
            hasUsableSourceSession(cookies.getCookie(first.url).orEmpty())
        )
        assertTrue(
            "CookieManager не бачить 4read-сесію — фікстура не готова",
            hasUsableSourceSession(cookies.getCookie("https://4read.org/book").orEmpty())
        )
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

        // Control: with no expiry attribute this exact name/value/URL IS a
        // usable session, so the expired read below cannot pass for an
        // unrelated reason (wrong host, wrong path, a jar that refused the
        // write). This is the precondition the fixture used to assume.
        cookies.setCookie(book.url, "challenge=ready; Path=/books; Secure")
        cookies.flush()
        assertTrue(
            "CookieManager не бачить щойно записаний кукі — фікстура не готова",
            hasUsableSourceSession(cookies.getCookie(book.url).orEmpty())
        )
        assertEquals(sessionThenBrowser, candidates(book))

        // #966 — the step that CLAIMS to expire the cookie must actually
        // expire it. Robolectric 4.16.1's `RoboCookieManager` applies `Expires`
        // and ONLY `Expires`, measured on this class:
        //   * `Max-Age=0` is inert — the write lands as `challenge=`, an EMPTY
        //     VALUE, not an expiry, so the old fixture asserted the policy's
        //     reaction to an empty cookie and never to an expired one;
        //   * a past `Expires` re-written over a LIVE cookie is rejected at
        //     parse time and does NOT delete the stored entry (`getCookie`
        //     still returns `challenge=ready`, and `removeExpiredCookie()`
        //     cannot help because that entry carries no parsed expiry at all);
        //     rewriting `Max-Age=0` as `Expires` would therefore just move the
        //     silent no-op around — in an English-locale CI it would not even
        //     be a no-op, it would fail;
        //   * what it does apply is the expiry of a cookie as it is WRITTEN: an
        //     already-expired cookie is never retained.
        // The platform-accurate shape of an expired cookie is thus
        // `getCookie(url) == null` — a real Chromium-backed CookieManager
        // prunes it the same way. Rebuild the jar in that shape.
        cookies.removeAllCookies(null)
        cookies.flush()
        cookies.setCookie(
            book.url,
            "challenge=ready; Expires=${httpDateInThePast()}; Path=/books; Secure"
        )
        cookies.flush()

        // Proof from the opposite: drop the `Expires` above and the write is
        // stored as a plain session cookie, so this assertion fails loudly
        // instead of the policy assertion below quietly passing against a
        // cookie that never expired — which is exactly what `Max-Age=0` did.
        assertNull(
            "Expires у минулому не спрацював — CookieManager досі віддає кукі",
            cookies.getCookie(book.url)
        )
        assertEquals(browserOnly, candidates(book))
    }

    @Test
    fun `clear acts on the jar the fixture seeded even after the shadow swaps its singleton`() {
        val book = source("shared", "https://sluhay.com/books/shared")
        cookies.setCookie(book.url, "session=shared-ok; Path=/books; Secure")
        cookies.flush()
        SourceWebViewSession.rememberVisitedUrl("sluhay", book.url)
        assertTrue(
            "CookieManager не бачить щойно записаний кукі — фікстура не готова",
            hasUsableSourceSession(cookies.getCookie(book.url).orEmpty())
        )

        // The reported flake, made deterministic instead of ~1 run in 25. The
        // shadow keeps its store behind a static that its @Resetter nulls, so a
        // later `getInstance()` builds a DIFFERENT, empty RoboCookieManager
        // (#948). Reproduce that swap on purpose: a clear that re-resolves the
        // singleton per call now runs against the empty jar, reads no cookie
        // names, issues no expiry command and silently leaves the book looking
        // like it still holds a session — which is how the 4th case of #948
        // failed PRs that never touch cookies.
        ShadowCookieManager.resetCookies()

        // The reproduction is only honest while the swap really happened: if a
        // future Robolectric made the shadow a stable singleton, this test would
        // keep passing while proving nothing, so say so out loud.
        assertNotSame(
            "ShadowCookieManager більше не підмінює синглтон — відтворення #948 зламане",
            cookies,
            CookieManager.getInstance()
        )

        SourceWebViewSession.clear("sluhay")

        // The post-clear shape differs by platform and both shapes mean the same
        // thing: a Chromium-backed jar deletes the entry (`getCookie` → null),
        // while Robolectric's applies only `Expires` and leaves the inert
        // `Max-Age=0` remnant `name=` (measured on this class, #966). The
        // guarantee worth pinning is the one the policy actually reads — no
        // non-empty value left, so the book is no longer a reusable session —
        // not one platform's spelling of "gone".
        assertFalse(
            "clear відпрацював на іншому сховищі CookieManager — сесія лишилась придатною",
            hasUsableSourceSession(cookies.getCookie(book.url).orEmpty())
        )
        assertEquals(browserOnly, candidates(book))
    }

    // `RoboCookieManager.getExpiration` parses `Expires` with a SimpleDateFormat
    // built from the JVM's default FORMAT locale, so the fixture has to emit the
    // HTTP date in that same locale — the shadow silently stops seeing the
    // attribute under any other locale, and the assertion above would fail for
    // the wrong reason.
    private fun httpDateInThePast(): String {
        val format = SimpleDateFormat(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            Locale.getDefault(Locale.Category.FORMAT)
        )
        format.timeZone = TimeZone.getTimeZone("GMT")
        return format.format(Date(System.currentTimeMillis() - 86_400_000L))
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
