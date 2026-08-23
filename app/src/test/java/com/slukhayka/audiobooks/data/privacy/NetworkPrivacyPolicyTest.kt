package com.slukhayka.audiobooks.data.privacy

import org.junit.Assert.assertEquals
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
}
