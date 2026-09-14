package com.slukhayka.audiobooks.data.privacy

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-51 #527 (AC4) — the Referer belongs to the ORIGIN.
 *
 * A redirect must never carry one source's identity onto another host: the
 * media CDN gets the Referer it expects, a third-party host gets nothing. The
 * rule is pure, so it is proved without a network.
 */
class PlaybackRedirectsTest {

    @Test
    fun `notice matching does not ban legitimate books on the shared CDN`() {
        for (url in listOf(
            "https://reasd.org/notice/4read-notice.mp3",
            "http://s1.reasd.org/notice/4read-notice.mp3?expires=123",
            "https://REASD.ORG/notice/%34read-notice.mp3",
        )) assertTrue(url, AudioNoticePolicy.isBlocked(url.toHttpUrl()))
        for (url in listOf(
            "https://reasd.org/4769/01.mp3",
            "https://arch.sound-books.net/3563/book.mp3",
            "https://reasd.org.example.com/notice/4read-notice.mp3",
            "https://example.com/notice/4read-notice.mp3",
        )) assertFalse(url, AudioNoticePolicy.isBlocked(url.toHttpUrl()))
    }

    @Test
    fun `the same origin keeps its headers`() {
        val from = "https://cdn.audiobookmp3.com/a.mp3".toHttpUrl()
        val to = "https://cdn.audiobookmp3.com/b.mp3".toHttpUrl()

        assertFalse(PlaybackRedirects.crossesOrigin(from, to))
        assertTrue(PlaybackRedirects.headersToDrop(false).isEmpty())
    }

    @Test
    fun `another host drops the source identity`() {
        val from = "https://cdn.audiobookmp3.com/a.mp3".toHttpUrl()
        val to = "https://tracker.example.net/b.mp3".toHttpUrl()

        assertTrue(PlaybackRedirects.crossesOrigin(from, to))
        assertEquals(
            listOf("Referer", "Authorization", "Cookie"),
            PlaybackRedirects.headersToDrop(true)
        )
    }

    @Test
    fun `another port on the same host is another origin too`() {
        val from = "https://cdn.audiobookmp3.com/a.mp3".toHttpUrl()
        val to = "https://cdn.audiobookmp3.com:8443/b.mp3".toHttpUrl()
        assertTrue(PlaybackRedirects.crossesOrigin(from, to))
    }

    @Test
    fun `a cleartext downgrade is refused outright`() {
        assertFalse(
            "https → http must not be followed (#516)",
            PlaybackRedirects.follow("https://cdn.audiobookmp3.com/a.mp3", "http://cdn.audiobookmp3.com/b.mp3")
        )
        assertTrue(PlaybackRedirects.follow("https://cdn.audiobookmp3.com/a.mp3", "/b.mp3"))
        assertTrue(
            PlaybackRedirects.follow(
                "https://cdn.audiobookmp3.com/a.mp3",
                "https://cdn.audiobookmp3.com/b.mp3"
            )
        )
    }
}
