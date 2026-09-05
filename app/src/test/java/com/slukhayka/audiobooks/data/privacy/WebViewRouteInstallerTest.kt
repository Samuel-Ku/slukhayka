package com.slukhayka.audiobooks.data.privacy

import com.slukhayka.audiobooks.data.privacy.WebViewSessionPrivacy.SessionRoute
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewRouteInstallerTest {

    @Test
    fun `routed WebView stays gated until proxy callback completes`() = runTest {
        var completion: (() -> Unit)? = null
        var appliedRule = ""

        val result = async {
            awaitWebViewRouteApplied(
                route = SessionRoute.Routed("socks5://127.0.0.1:9050"),
                timeoutMs = 5_000,
                clearOverride = { error("direct clear must not run") },
                setOverride = { rule, done ->
                    appliedRule = rule
                    completion = done
                }
            )
        }

        runCurrent()
        assertEquals("socks5://127.0.0.1:9050", appliedRule)
        assertFalse("load gate must stay closed before WebKit confirms", result.isCompleted)

        completion!!.invoke()
        assertEquals(WebViewRouteApplyOutcome.Ready, result.await())
    }

    @Test
    fun `missing WebKit callback fails closed after bounded timeout`() = runTest {
        val result = async {
            awaitWebViewRouteApplied(
                route = SessionRoute.Routed("http://proxy.lan:3128"),
                timeoutMs = 5_000,
                clearOverride = { error("direct clear must not run") },
                setOverride = { _, _ -> }
            )
        }

        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(5_001)

        assertTrue(result.await() is WebViewRouteApplyOutcome.Failed)
    }
}
