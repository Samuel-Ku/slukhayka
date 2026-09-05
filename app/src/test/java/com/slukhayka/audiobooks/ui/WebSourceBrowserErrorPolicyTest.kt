package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.ui.screens.shouldReplaceWebViewErrorPage
import com.slukhayka.audiobooks.ui.screens.BrowserRecoverySurface
import com.slukhayka.audiobooks.ui.screens.browserRecoverySurface
import com.slukhayka.audiobooks.ui.screens.isCloudflareChallenge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSourceBrowserErrorPolicyTest {

    @Test
    fun everyMainFrameFailureReplacesWebViewsTechnicalErrorPage() {
        // WebView may map ERR_PROXY_CONNECTION_FAILED to a code outside the
        // small DNS/connect/timeout subset. The user must still see our plain
        // recovery card rather than Chromium's localized raw error page.
        assertTrue(shouldReplaceWebViewErrorPage(isForMainFrame = true, errorCode = -5))
        assertTrue(shouldReplaceWebViewErrorPage(isForMainFrame = true, errorCode = -1))
    }

    @Test
    fun subresourceFailureDoesNotCoverAnOtherwiseUsablePage() {
        assertFalse(shouldReplaceWebViewErrorPage(isForMainFrame = false, errorCode = -5))
    }

    @Test
    fun automaticRecoveryStaysHiddenUntilCloudflareNeedsTheListener() {
        assertEquals(
            BrowserRecoverySurface.HIDDEN,
            browserRecoverySurface(automaticRecovery = true, cloudflareChallenge = false)
        )
        assertEquals(
            BrowserRecoverySurface.CLOUDFLARE_ONLY,
            browserRecoverySurface(automaticRecovery = true, cloudflareChallenge = true)
        )
        assertEquals(
            BrowserRecoverySurface.FULL_BROWSER,
            browserRecoverySurface(automaticRecovery = false, cloudflareChallenge = false)
        )
    }

    @Test
    fun cloudflareChallengeRecognizesUrlTitleAndDomMarker() {
        assertTrue(isCloudflareChallenge("https://4read.org/cdn-cgi/challenge-platform/x", "", false))
        assertTrue(isCloudflareChallenge("https://4read.org/book", "Just a moment...", false))
        assertTrue(isCloudflareChallenge("https://4read.org/book", "4read", true))
        assertFalse(isCloudflareChallenge("https://4read.org/book", "Проблема з миром", false))
    }
}
