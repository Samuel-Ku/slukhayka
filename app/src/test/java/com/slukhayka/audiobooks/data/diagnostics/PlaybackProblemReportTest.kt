package com.slukhayka.audiobooks.data.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #597 — the playback problem report is an allowlist: it renders exactly the
 * permitted fields, omits absent ones instead of fabricating them, and never
 * carries URLs, titles, positions, tokens or raw logs.
 */
class PlaybackProblemReportTest {

    private fun text(
        category: String = "UNAVAILABLE",
        stage: String = "PLAYBACK",
        appVersion: String = "1.3.9",
        variant: String = "release",
        androidRelease: String = "14",
        sdk: Int = 34,
        webView: String? = null,
        http: Int? = null,
        rayId: String? = null
    ) = PlaybackProblemReport.build(
        category = category,
        stage = stage,
        appVersion = appVersion,
        appVariant = variant,
        androidRelease = androidRelease,
        androidSdk = sdk,
        webViewVersion = webView,
        httpStatus = http,
        cloudflareRayId = rayId
    ).toPlainText()

    @Test
    fun `blanks normalize to honest unknowns`() {
        val text = text(category = "", stage = "", appVersion = "", variant = "", androidRelease = "", sdk = 0)
        assertTrue(text.contains("Категорія: UNKNOWN"))
        assertTrue(text.contains("Етап: PLAYBACK"))
        assertTrue(text.contains("Застосунок: unknown (unknown)"))
        assertTrue(text.contains("Android: unknown (SDK 0)"))
    }

    @Test
    fun `absent optional fields are omitted, never fabricated`() {
        val text = text()
        assertFalse(text.contains("HTTP:"))
        assertFalse(text.contains("WebView:"))
        assertFalse(text.contains("Cloudflare"))
    }

    @Test
    fun `present optional fields render`() {
        val text = text(http = 403, webView = "120.0.6099.230", rayId = "8f2a1c")
        assertTrue(text.contains("HTTP: 403"))
        assertTrue(text.contains("WebView: 120.0.6099.230"))
        assertTrue(text.contains("Cloudflare Ray ID: 8f2a1c"))
    }

    @Test
    fun `a non-positive http status is dropped`() {
        assertFalse(text(http = 0).contains("HTTP:"))
    }

    @Test
    fun `the report never carries urls or secrets`() {
        val text = text()
        assertFalse(text.contains("://"))
        assertFalse(text.contains("token", ignoreCase = true))
        assertFalse(text.contains("cookie", ignoreCase = true))
        assertFalse(text.contains("streamUrl", ignoreCase = true))
    }
}
