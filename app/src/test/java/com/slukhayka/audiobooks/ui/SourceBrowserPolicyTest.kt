package com.slukhayka.audiobooks.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceBrowserPolicyTest {
    @Test fun `4read browser accepts only its own http pages`() {
        assertTrue(SourceBrowserPolicy.allowsPageNavigation("4read", "https://4read.org/book.html"))
        assertTrue(SourceBrowserPolicy.allowsPageNavigation("4read", "http://www.4read.org/search"))
        assertFalse(SourceBrowserPolicy.allowsPageNavigation("4read", "https://reasd.org/audio.mp3"))
        assertFalse(SourceBrowserPolicy.allowsPageNavigation("4read", "https://evil4read.org/book"))
        assertFalse(SourceBrowserPolicy.allowsPageNavigation("4read", "file:///sdcard/book.html"))
    }

    @Test fun `audio hosts never become page hosts`() {
        assertTrue(SourceBrowserPolicy.allowsAudioHost("4read", "media.reasd.org", "4read.org"))
        assertFalse(SourceBrowserPolicy.allowsAudioHost("4read", "elsewhere.example", "4read.org"))
    }
}
