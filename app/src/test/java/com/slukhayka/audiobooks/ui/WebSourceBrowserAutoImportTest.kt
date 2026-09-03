package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.ui.screens.pageHasPlaylistRef
import com.slukhayka.audiobooks.ui.screens.shouldAutoImportPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #478 — the first-signal auto-import gate: the import starts itself once
 * per page when intercepted audio or a playlist reference appears, instead
 * of waiting for the manual «Додати до медіатеки» tap.
 */
class WebSourceBrowserAutoImportTest {

    @Test
    fun `live m33u2 manifest reference is a signal`() {
        assertTrue(
            pageHasPlaylistRef(
                """<script>Playerjs({id:"playerjs1",file:"https://4read.org/m33u2/7589-neostannij-bij-kostjantin-shelest.m3u"});</script>"""
            )
        )
    }

    @Test
    fun `legacy v1 and txt manifest references are signals`() {
        assertTrue(pageHasPlaylistRef("""var player = new Playerjs({file:"https://4read.org/m3u/7589.txt"});"""))
        assertTrue(pageHasPlaylistRef("""file:'https://4read.org/m3u/{v1}/7589'"""))
    }

    @Test
    fun `inline json playlist is a signal`() {
        assertTrue(pageHasPlaylistRef("""new Playerjs({file:"[{"title":"Глава 1","file":"https://s1.reasd.org/01.mp3"}]"});"""))
    }

    @Test
    fun `plain and catalogue pages are not signals`() {
        assertFalse(pageHasPlaylistRef("<html><body><p>Просто текст</p></body></html>"))
        assertFalse(
            pageHasPlaylistRef(
                """<div class="poster__title line-clamp">Кобзар</div><div class="poster__subtitle ws-nowrap">Тарас Шевченко</div>"""
            )
        )
        assertFalse(pageHasPlaylistRef(""))
    }

    @Test
    fun `fresh page with audio or playlist reference fires`() {
        assertTrue(shouldAutoImportPage(null, "https://4read.org/7589.html", 1, false))
        assertTrue(shouldAutoImportPage(null, "https://4read.org/7589.html", 0, true))
    }

    @Test
    fun `fires at most once per page`() {
        assertFalse(
            shouldAutoImportPage(
                "https://4read.org/7589.html",
                "https://4read.org/7589.html",
                5,
                true
            )
        )
        assertTrue(
            shouldAutoImportPage(
                "https://4read.org/7589.html",
                "https://4read.org/7810.html",
                1,
                false
            )
        )
    }

    @Test
    fun `stays silent without signals or outside book capture`() {
        assertFalse(shouldAutoImportPage(null, "https://4read.org/7589.html", 0, false))
        assertFalse(shouldAutoImportPage(null, "", 3, true))
        assertFalse(
            shouldAutoImportPage(null, "https://4read.org/7589.html", 3, true, autoCaptureEnabled = false)
        )
    }
}
