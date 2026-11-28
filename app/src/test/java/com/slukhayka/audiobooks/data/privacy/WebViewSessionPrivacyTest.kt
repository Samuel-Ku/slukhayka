package com.slukhayka.audiobooks.data.privacy

// Explicit imports pin these names to the WebView door's nested types — the
// transport door owns a top-level RouteResolution in the same package.
import com.slukhayka.audiobooks.data.privacy.WebViewSessionPrivacy.RouteResolution
import com.slukhayka.audiobooks.data.privacy.WebViewSessionPrivacy.SessionRoute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-38 T3 (#255) — the WebView session door, pure JVM. The same resolved
 * route the transport rides converts into one webkit proxy rule (HTTP stays
 * HTTP, SOCKS5 — including Tor's local port — becomes a socks5 rule); a
 * routed session on a WebView without PROXY_OVERRIDE refuses instead of
 * silently going direct; «прямо» resolves to no override regardless of
 * feature support; the lockdown script denies geolocation and every sensor
 * JS global. The webkit/WebView wiring itself is thin glue (spec-38 Testing
 * Decisions); on-device behaviour is QA #259.
 */
class WebViewSessionPrivacyTest {

    // --- the route: same exit as the transport ---

    @Test
    fun `direct route resolves to system default`() {
        val result = WebViewSessionPrivacy.resolve(
            proxyOverrideSupported = true,
            route = NetworkRoute.Direct
        )
        assertEquals(RouteResolution.Ok(SessionRoute.SystemDefault), result)
    }

    @Test
    fun `direct route resolves to system default even without proxy support`() {
        val result = WebViewSessionPrivacy.resolve(
            proxyOverrideSupported = false,
            route = NetworkRoute.Direct
        )
        assertEquals(RouteResolution.Ok(SessionRoute.SystemDefault), result)
    }

    @Test
    fun `an http proxy becomes an http rule`() {
        val result = WebViewSessionPrivacy.resolve(
            proxyOverrideSupported = true,
            route = NetworkRoute.Proxy(RouteProxyType.HTTP, "192.168.1.10", 8080)
        )
        assertEquals(
            RouteResolution.Ok(SessionRoute.Routed("http://192.168.1.10:8080")),
            result
        )
    }

    @Test
    fun `a socks5 proxy becomes a socks5 rule`() {
        val result = WebViewSessionPrivacy.resolve(
            proxyOverrideSupported = true,
            route = NetworkRoute.Proxy(RouteProxyType.SOCKS5, "10.0.0.2", 1080)
        )
        assertEquals(
            RouteResolution.Ok(SessionRoute.Routed("socks5://10.0.0.2:1080")),
            result
        )
    }

    @Test
    fun `the transport door and the webview door resolve the same exit`() {
        try {
            TransportPrivacy.install(PrivacyPrefs(routeMode = RouteMode.MAX_PRIVACY))
            val transportProxy = TransportPrivacy.currentJavaProxy()
            val result = WebViewSessionPrivacy.resolve(proxyOverrideSupported = true)
            assertTrue(result is RouteResolution.Ok)
            val routed = (result as RouteResolution.Ok).route as SessionRoute.Routed
            assertEquals("socks5://127.0.0.1:9050", routed.proxyRule)
            // Same host/port as the java.net.Proxy the shared fetcher opens.
            assertEquals("127.0.0.1", (transportProxy!!.address() as java.net.InetSocketAddress).hostName)
            assertEquals(9050, (transportProxy.address() as java.net.InetSocketAddress).port)
        } finally {
            TransportPrivacy.install(PrivacyPrefs())
        }
    }

    // --- honest refusal when WebView cannot ride the route ---

    @Test
    fun `a routed session on unsupported webview refuses`() {
        val result = WebViewSessionPrivacy.resolve(
            proxyOverrideSupported = false,
            route = NetworkRoute.Proxy(RouteProxyType.SOCKS5, "127.0.0.1", 9050)
        )
        assertTrue(result is RouteResolution.Refused)
        assertTrue((result as RouteResolution.Refused).reason.isNotBlank())
    }

    @Test
    fun `the refusal never produces a direct-fallback rule`() {
        val result = WebViewSessionPrivacy.resolve(
            proxyOverrideSupported = false,
            route = NetworkRoute.Proxy(RouteProxyType.HTTP, "proxy.lan", 3128)
        )
        assertTrue(result is RouteResolution.Refused)
    }

    @Test
    fun `the relay route refuses instead of silently going direct`() {
        // The relay rewrites transport URLs; an interactive WebView session
        // cannot ride it — and a silent direct session is not an option.
        val result = WebViewSessionPrivacy.resolve(
            proxyOverrideSupported = true,
            route = NetworkRoute.Relay("https://slukhayka-relay.example.workers.dev")
        )
        assertTrue(result is RouteResolution.Refused)
        assertTrue((result as RouteResolution.Refused).reason.isNotBlank())
    }

    @Test
    fun `the rule format matches the webkit controller's expected shape`() {
        assertEquals(
            "http://host.local:3128",
            WebViewSessionPrivacy.proxyRule(NetworkRoute.Proxy(RouteProxyType.HTTP, "host.local", 3128))
        )
        assertEquals(
            "socks5://127.0.0.1:9050",
            WebViewSessionPrivacy.proxyRule(NetworkRoute.Proxy(RouteProxyType.SOCKS5, "127.0.0.1", 9050))
        )
    }

    // --- the lockdown script ---

    @Test
    fun `the lockdown script denies geolocation`() {
        assertTrue("geolocation" in WebViewSessionPrivacy.lockdownScript())
    }

    @Test
    fun `the lockdown script denies orientation motion and every generic sensor global`() {
        val script = WebViewSessionPrivacy.lockdownScript()
        for (name in listOf(
            "DeviceOrientationEvent",
            "DeviceMotionEvent",
            "Accelerometer",
            "Gyroscope",
            "LinearAccelerationSensor",
            "AbsoluteOrientationSensor",
            "RelativeOrientationSensor",
            "Magnetometer",
            "AmbientLightSensor"
        )) {
            assertTrue("'$name'" in script)
        }
    }

    @Test
    fun `the lockdown script is idempotent-shaped and self-contained`() {
        val script = WebViewSessionPrivacy.lockdownScript()
        assertTrue(script.startsWith("(function(){"))
        assertTrue(script.endsWith("})();"))
    }
}
