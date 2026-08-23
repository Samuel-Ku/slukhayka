package com.slukhayka.audiobooks.data.privacy

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Spec-38 T4 (#256) — the DNS half of the network privacy door, pure JVM:
 * DoH is the ticket's own default («типово увімкнений»), opting out resolves
 * the plain system strategy, and — the route-independence criterion — the
 * DNS decision never depends on which route mode is installed. The fallback
 * chain degrades to the system resolver on any primary failure without
 * surfacing an error (доступність важливіша за прихованість імен).
 */
class DohPolicyTest {

    // --- the default decision ---

    @Test
    fun `no settings resolve the doh-first strategy`() {
        val strategy = NetworkPrivacy.resolveDns(PrivacyPrefs())
        assertEquals(DnsStrategy.DohFirst(NetworkPrivacy.DOH_URL), strategy)
    }

    @Test
    fun `the doh endpoint is a https url with bootstrap ips`() {
        assertTrue(NetworkPrivacy.DOH_URL.startsWith("https://"))
        assertTrue(NetworkPrivacy.DOH_BOOTSTRAP_IPS.isNotEmpty())
        // The bootstrap must be IP literals — resolving the resolver over DNS
        // would leak the very names DoH hides.
        NetworkPrivacy.DOH_BOOTSTRAP_IPS.forEach {
            InetAddress.getByName(it) // throws if not a literal
        }
    }

    // --- opting out ---

    @Test
    fun `doh off resolves the system-only strategy`() {
        val strategy = NetworkPrivacy.resolveDns(PrivacyPrefs(dohEnabled = false))
        assertEquals(DnsStrategy.SystemOnly, strategy)
    }

    // --- route independence (AC3, pure side) ---

    @Test
    fun `the dns decision ignores the route mode`() {
        for (mode in listOf(RouteMode.DIRECT, RouteMode.CUSTOM_PROXY, RouteMode.MAX_PRIVACY)) {
            assertEquals(
                "DoH must stay on for route $mode",
                DnsStrategy.DohFirst(NetworkPrivacy.DOH_URL),
                NetworkPrivacy.resolveDns(PrivacyPrefs(routeMode = mode))
            )
            assertEquals(
                "DoH opt-out must hold for route $mode",
                DnsStrategy.SystemOnly,
                NetworkPrivacy.resolveDns(PrivacyPrefs(routeMode = mode, dohEnabled = false))
            )
        }
    }

    // --- the transparent fallback chain (AC2) ---

    @Test
    fun `a primary answer serves without consulting the fallback`() {
        val consulted = mutableListOf<String>()
        val dns = FallbackDns(
            primary = fakeDns { _ -> listOf(address("1.2.3.4")) },
            fallback = fakeDns { host -> consulted += host; emptyList() }
        )
        val answers = dns.lookup("4read.org")
        assertEquals(1, answers.size)
        assertTrue(consulted.isEmpty())
    }

    @Test
    fun `an unknown host from doh falls back to the system resolver`() {
        val dns = FallbackDns(
            primary = fakeDns { throw UnknownHostException("doh: nx") },
            fallback = fakeDns { _ -> listOf(address("5.6.7.8")) }
        )
        assertEquals(1, dns.lookup("sluhay.com").size)
    }

    @Test
    fun `a transport failure of doh falls back too`() {
        val dns = FallbackDns(
            primary = fakeDns { throw UnknownHostException("doh unreachable") },
            fallback = fakeDns { _ -> listOf(address("9.9.9.9")) }
        )
        assertEquals(1, dns.lookup("audiobook-mp3.com").size)
    }

    @Test
    fun `every fallback is reported through the hook`() {
        val reported = mutableListOf<Pair<String, String>>()
        val dns = FallbackDns(
            primary = fakeDns { throw IllegalStateException("boom") },
            fallback = fakeDns { _ -> listOf(address("1.1.1.1")) },
            onFallback = { hostname, failure -> reported += hostname to failure.message.orEmpty() }
        )
        dns.lookup("sound-books.net")
        assertEquals(listOf("sound-books.net" to "boom"), reported)
    }

    @Test
    fun `when both resolvers fail the fallback exception surfaces`() {
        val dns = FallbackDns(
            primary = fakeDns { throw UnknownHostException("doh down") },
            fallback = fakeDns { throw UnknownHostException("system down") }
        )
        try {
            dns.lookup("4read.org")
            throw AssertionError("expected UnknownHostException")
        } catch (expected: UnknownHostException) {
            assertEquals("system down", expected.message)
        }
    }
}

/** A fake resolver built from a lambda — OkHttp's [Dns] is not a fun interface. */
private fun fakeDns(answer: (hostname: String) -> List<InetAddress>): Dns = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> = answer(hostname)
}

private fun address(ip: String): InetAddress = InetAddress.getByAddress(ip.split('.').map { it.toInt().toByte() }.toByteArray())
