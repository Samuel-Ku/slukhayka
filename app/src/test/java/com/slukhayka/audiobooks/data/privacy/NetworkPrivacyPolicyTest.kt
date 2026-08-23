package com.slukhayka.audiobooks.data.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-38 T1/T2 (#253/#254) — the pure route resolver behind the network
 * privacy module's single door: user settings → resolved route. No stored
 * settings resolve «прямо»; a custom address parses into host/port with an
 * optional scheme; garbage rejects with an understandable reason; the max
 * privacy toggle resolves Tor's local SOCKS port. The Android proxy wiring
 * itself is thin glue (spec-38 Testing Decisions) — this seam stays pure JVM.
 */
class NetworkPrivacyPolicyTest {

    // --- the default route ---

    @Test
    fun `no settings resolve the direct route`() {
        val result = NetworkPrivacy.resolve(PrivacyPrefs())
        assertTrue(result is RouteResolution.Ok)
        assertEquals(NetworkRoute.Direct, (result as RouteResolution.Ok).route)
    }

    @Test
    fun `direct mode ignores a leftover junk address`() {
        val result = NetworkPrivacy.resolve(
            PrivacyPrefs(routeMode = RouteMode.DIRECT, proxyAddress = "не адреса")
        )
        assertTrue(result is RouteResolution.Ok)
        assertEquals(NetworkRoute.Direct, (result as RouteResolution.Ok).route)
    }

    // --- max privacy (Tor / Orbot) ---

    @Test
    fun `max privacy resolves Tor localhost SOCKS5`() {
        val result = NetworkPrivacy.resolve(PrivacyPrefs(routeMode = RouteMode.MAX_PRIVACY))
        assertEquals(
            RouteResolution.Ok(NetworkRoute.Proxy(RouteProxyType.SOCKS5, "127.0.0.1", 9050)),
            result
        )
    }

    // --- custom proxy parsing ---

    @Test
    fun `a bare host_port parses as an http proxy`() {
        val result = NetworkPrivacy.resolve(
            PrivacyPrefs(routeMode = RouteMode.CUSTOM_PROXY, proxyAddress = "192.168.1.10:8080")
        )
        assertEquals(
            RouteResolution.Ok(NetworkRoute.Proxy(RouteProxyType.HTTP, "192.168.1.10", 8080)),
            result
        )
    }

    @Test
    fun `an explicit http scheme parses as http`() {
        val result = NetworkPrivacy.resolve(
            PrivacyPrefs(routeMode = RouteMode.CUSTOM_PROXY, proxyAddress = "http://proxy.lan:3128")
        )
        assertEquals(
            RouteResolution.Ok(NetworkRoute.Proxy(RouteProxyType.HTTP, "proxy.lan", 3128)),
            result
        )
    }

    @Test
    fun `a socks5 scheme parses as socks5`() {
        val result = NetworkPrivacy.resolve(
            PrivacyPrefs(routeMode = RouteMode.CUSTOM_PROXY, proxyAddress = "socks5://10.0.0.2:1080")
        )
        assertEquals(
            RouteResolution.Ok(NetworkRoute.Proxy(RouteProxyType.SOCKS5, "10.0.0.2", 1080)),
            result
        )
    }

    @Test
    fun `surrounding whitespace does not break the parse`() {
        val endpoint = NetworkPrivacy.parseAddress("  host.local:9050  ")
        assertEquals(RouteProxyType.HTTP, endpoint?.type)
        assertEquals("host.local", endpoint?.host)
        assertEquals(9050, endpoint?.port)
    }

    // --- honest rejection of garbage ---

    @Test
    fun `an empty address rejects with a reason`() {
        val result = NetworkPrivacy.resolve(
            PrivacyPrefs(routeMode = RouteMode.CUSTOM_PROXY, proxyAddress = "   ")
        )
        assertTrue(result is RouteResolution.Invalid)
        assertTrue((result as RouteResolution.Invalid).reason.isNotBlank())
    }

    @Test
    fun `a missing port rejects`() {
        assertNull(NetworkPrivacy.parseAddress("192.168.1.10"))
    }

    @Test
    fun `a non-numeric port rejects`() {
        assertNull(NetworkPrivacy.parseAddress("192.168.1.10:eight"))
    }

    @Test
    fun `a port outside 1_65535 rejects`() {
        assertNull(NetworkPrivacy.parseAddress("192.168.1.10:0"))
        assertNull(NetworkPrivacy.parseAddress("192.168.1.10:70000"))
    }

    @Test
    fun `an empty host rejects`() {
        assertNull(NetworkPrivacy.parseAddress(":8080"))
        assertNull(NetworkPrivacy.parseAddress("http://:8080"))
    }

    @Test
    fun `garbage text rejects`() {
        assertNull(NetworkPrivacy.parseAddress("не адреса"))
        assertNull(NetworkPrivacy.parseAddress("http://"))
    }

    @Test
    fun `every rejection carries an understandable reason`() {
        val result = NetworkPrivacy.resolve(
            PrivacyPrefs(routeMode = RouteMode.CUSTOM_PROXY, proxyAddress="192.168.1.10")
        )
        assertTrue(result is RouteResolution.Invalid)
        // The reason names the expected shape so the user can fix the input.
        assertTrue("порт" in (result as RouteResolution.Invalid).reason.lowercase())
    }

    // --- relay prototype route (spec-38 T6, #258) ---

    @Test
    fun `a https workers address resolves to a normalized relay base`() {
        val result = NetworkPrivacy.resolve(
            PrivacyPrefs(routeMode = RouteMode.RELAY, proxyAddress = "https://slukhayka-relay.example.workers.dev/")
        )
        assertEquals(
            RouteResolution.Ok(NetworkRoute.Relay("https://slukhayka-relay.example.workers.dev")),
            result
        )
    }

    @Test
    fun `a relay base keeps its path and explicit port but drops trailing slashes`() {
        assertEquals(
            "https://relay.example.dev/relay",
            NetworkPrivacy.parseRelayBase("https://relay.example.dev/relay///")
        )
        assertEquals(
            "http://127.0.0.1:8787",
            NetworkPrivacy.parseRelayBase("http://127.0.0.1:8787")
        )
    }

    @Test
    fun `an http localhost base parses for wrangler dev`() {
        val result = NetworkPrivacy.resolve(
            PrivacyPrefs(routeMode = RouteMode.RELAY, proxyAddress = "http://127.0.0.1:8787")
        )
        assertTrue(result is RouteResolution.Ok)
        assertEquals(NetworkRoute.Relay("http://127.0.0.1:8787"), (result as RouteResolution.Ok).route)
    }

    @Test
    fun `a blank relay address rejects with a reason`() {
        val result = NetworkPrivacy.resolve(
            PrivacyPrefs(routeMode = RouteMode.RELAY, proxyAddress = "   ")
        )
        assertTrue(result is RouteResolution.Invalid)
        assertTrue((result as RouteResolution.Invalid).reason.contains("реле"))
    }

    @Test
    fun `garbage relay text rejects`() {
        assertNull(NetworkPrivacy.parseRelayBase("не адреса"))
        assertNull(NetworkPrivacy.parseRelayBase("relay.example.workers.dev"))
        assertNull(NetworkPrivacy.parseRelayBase("https://"))
    }

    @Test
    fun `a non-http relay scheme rejects`() {
        assertNull(NetworkPrivacy.parseRelayBase("ftp://relay.example.dev"))
        assertNull(NetworkPrivacy.parseRelayBase("socks5://127.0.0.1:9050"))
    }

    @Test
    fun `a relay base with query fragment or credentials rejects`() {
        assertNull(NetworkPrivacy.parseRelayBase("https://relay.example.dev/?token=x"))
        assertNull(NetworkPrivacy.parseRelayBase("https://relay.example.dev/#frag"))
        assertNull(NetworkPrivacy.parseRelayBase("https://user:pass@relay.example.dev"))
    }

    @Test
    fun `the relay rewrite is total and appends the encoded target`() {
        val relay = NetworkRoute.Relay("https://relay.example.dev")
        assertEquals(
            "https://relay.example.dev?url=https%3A%2F%2Fsluhay.com%2Fbook.html",
            relay.rewrite("https://sluhay.com/book.html")
        )
        // Special characters survive round-trip encoding — the worker decodes.
        assertEquals(
            "https://relay.example.dev/prefix?url=https%3A%2F%2Fcdn.example.com%2Fa%3Fb%3D1%26c%3D2",
            NetworkRoute.Relay("https://relay.example.dev/prefix")
                .rewrite("https://cdn.example.com/a?b=1&c=2")
        )
    }

    @Test
    fun `rewriteThroughRelay touches nothing unless the relay route is resolved`() {
        TransportPrivacy.install(PrivacyPrefs(routeMode = RouteMode.DIRECT))
        assertEquals("https://sluhay.com/book.html", TransportPrivacy.rewriteThroughRelay("https://sluhay.com/book.html"))
        assertFalse(TransportPrivacy.isRelayActive())
        try {
            TransportPrivacy.install(PrivacyPrefs(routeMode = RouteMode.RELAY, proxyAddress = "https://relay.example.dev"))
            assertEquals(
                "https://relay.example.dev?url=https%3A%2F%2Fsluhay.com%2Fbook.html",
                TransportPrivacy.rewriteThroughRelay("https://sluhay.com/book.html")
            )
            assertTrue(TransportPrivacy.isRelayActive())
            // The relay is NOT a wire-level proxy: the connection opens straight
            // to the relay origin, indirection lives in the rewritten URL.
            assertNull(TransportPrivacy.currentJavaProxy())
        } finally {
            // Never leak the relay into other tests in this JVM.
            TransportPrivacy.install(PrivacyPrefs(routeMode = RouteMode.DIRECT))
        }
    }
}
