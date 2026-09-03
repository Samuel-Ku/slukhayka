package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.ui.screens.pageHasPlaylistRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #476 — the verbatim live DOM snapshots (Safari, post-challenge,
 * 2026-09-03) parse through the production metadata + signal doors without
 * Android or network. Chapter expansion needs the session manifest, so these
 * fixtures pin the profile half and the playlist-reference signal; the
 * manifest half is pinned by the marker tests in [WebViewHtmlParserTest].
 */
class LiveFixtureParserTest {

    private fun fixture(name: String): String = requireNotNull(
        LiveFixtureParserTest::class.java.getResourceAsStream("/fixtures/$name")
    ).bufferedReader().use { it.readText() }

    private data class Expected(
        val file: String,
        val url: String,
        val title: String,
        val author: String
    )

    private val pages = listOf(
        Expected(
            "4read-book-7589-2026-09-03.html",
            "https://4read.org/7589-neostannij-bij-kostjantin-shelest.html",
            "Неостанній бій",
            "Костянтин Шелест"
        ),
        Expected(
            "4read-book-7810-2026-09-03.html",
            "https://4read.org/7810-dzho-aberkrombi-trohi-nenavisti.html",
            "Трохи ненависті",
            "Джо Аберкромбі"
        ),
        Expected(
            "4read-book-7832-2026-09-03.html",
            "https://4read.org/7832-endi-vejr-proyekt-ave-marija.html",
            // Verbatim og:title keeps its &quot; entities (titleFromPage does
            // not decode them) — pinned as-is, out of #476 scope to change.
            "Проєкт &quot;Аве Марія&quot;",
            "Енді Вейр"
        )
    )

    @Test
    fun `verbatim live pages expose the bibliographic profile`() {
        for (page in pages) {
            val detail = WebViewHtmlParser().parse(fixture(page.file), page.url)

            assertEquals(page.file, page.title, detail.title)
            assertEquals(page.file, page.author, detail.author)
            assertTrue(page.file, detail.narrator.isNotBlank())
        }
    }

    @Test
    fun `verbatim live pages carry the playlist signal`() {
        for (page in pages) {
            assertTrue(page.file, pageHasPlaylistRef(fixture(page.file)))
        }
    }
}
