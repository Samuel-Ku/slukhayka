package com.slukhayka.audiobooks.data.privacy

import java.net.URI
import java.net.URISyntaxException

/**
 * Spec-38 (#252) — the network privacy module's single door: user settings →
 * resolved route. Everything decision-shaped lives here, pure JVM; the
 * Android wiring (SharedPreferences store, `java.net.Proxy` construction)
 * is thin glue beside it.
 *
 * Honest failure by contract (spec-38 Implementation Decisions): a chosen
 * proxy route that cannot connect fails the request — the transport NEVER
 * falls back to a direct connection silently, because [resolve] only ever
 * returns the route the listener actually chose.
 */
object NetworkPrivacy {

    /** Orbot's default local SOCKS port — «максимальна приватність» rides it. */
    const val TOR_HOST = "127.0.0.1"
    const val TOR_SOCKS_PORT = 9050

    /**
     * Resolves the user's prefs into a concrete route.
     * - DIRECT wins regardless of any leftover address text;
     * - MAX_PRIVACY resolves Tor's local SOCKS port (Orbot must be running);
     * - CUSTOM_PROXY parses the address and rejects garbage with an
     *   understandable reason instead of guessing;
     * - RELAY (spec-38 T6, #258) validates the relay base URL the same way —
     *   no special-cased logic beyond the shape check, the route rides the
     *   ordinary transport machinery once resolved.
     */
    fun resolve(prefs: PrivacyPrefs): RouteResolution = when (prefs.routeMode) {
        RouteMode.DIRECT -> RouteResolution.Ok(NetworkRoute.Direct)
        RouteMode.MAX_PRIVACY ->
            RouteResolution.Ok(NetworkRoute.Proxy(RouteProxyType.SOCKS5, TOR_HOST, TOR_SOCKS_PORT))
        RouteMode.CUSTOM_PROXY -> {
            if (prefs.proxyAddress.isBlank()) {
                RouteResolution.Invalid("Введіть адресу проксі.")
            } else {
                parseAddress(prefs.proxyAddress)
                    ?.let { RouteResolution.Ok(NetworkRoute.Proxy(it.type, it.host, it.port)) }
                    ?: RouteResolution.Invalid(
                        "Адреса має бути виду host:порт — наприклад 192.168.1.10:8080 або socks5://10.0.0.2:1080."
                    )
            }
        }
        RouteMode.RELAY -> {
            if (prefs.proxyAddress.isBlank()) {
                RouteResolution.Invalid("Введіть адресу реле.")
            } else {
                parseRelayBase(prefs.proxyAddress)
                    ?.let { RouteResolution.Ok(NetworkRoute.Relay(it)) }
                    ?: RouteResolution.Invalid(
                        "Адреса реле має бути повним https://-посиланням на воркер — " +
                            "наприклад https://slukhayka-relay.example.workers.dev"
                    )
            }
        }
    }

    /**
     * Parses one proxy address. Accepted forms: `host:port` (HTTP),
     * `http(s)://host:port`, `socks5://host:port` (also `socks://`). The port
     * is mandatory — an address without one is garbage, not a default. Null
     * when anything is off (blank host, non-numeric or out-of-range port).
     */
    fun parseAddress(raw: String): ProxyEndpoint? {
        var rest = raw.trim()
        if (rest.isEmpty()) return null
        var type = RouteProxyType.HTTP
        for ((prefix, parsedType) in SCHEMES) {
            if (rest.startsWith(prefix)) {
                type = parsedType
                rest = rest.removePrefix(prefix)
                break
            }
        }
        // Anything after the first slash is not part of the endpoint.
        val slash = rest.indexOf('/')
        if (slash >= 0) rest = rest.substring(0, slash)

        // lastIndexOf keeps bare IPv6 hosts (no brackets) intact as hostless
        // port splits fail below instead of corrupting the address.
        val colon = rest.lastIndexOf(':')
        if (colon <= 0) return null
        val host = rest.substring(0, colon).trim()
        val port = rest.substring(colon + 1).trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        return ProxyEndpoint(type, host, port)
    }

    private val SCHEMES = listOf(
        "http://" to RouteProxyType.HTTP,
        "https://" to RouteProxyType.HTTP,
        "socks5://" to RouteProxyType.SOCKS5,
        "socks://" to RouteProxyType.SOCKS5
    )

    /**
     * Spec-38 T6 (#258) — parses and normalizes one relay base URL. Accepted:
     * `http(s)://host[:port][/path]` (the `http://` form exists for `wrangler
     * dev` on localhost). The trailing slash(es) are stripped so [NetworkRoute.Relay.rewrite]
     * can append `?url=` blindly; a query (`?`), fragment (`#`) or embedded
     * credentials are garbage for this route and reject. Null when anything
     * is off.
     */
    fun parseRelayBase(raw: String): String? {
        val uri = try {
            URI(raw.trim())
        } catch (_: URISyntaxException) {
            return null
        }
        if (uri.scheme?.lowercase() !in RELAY_SCHEMES) return null
        val host = uri.host
        if (host.isNullOrBlank()) return null
        if (uri.port.let { it != -1 && it !in 1..65535 }) return null
        // A relay base carries no query/fragment/credentials: the rewrite
        // appends its own `?url=` and the prototype keeps no secrets in URLs.
        if (uri.rawQuery != null || uri.rawFragment != null || !uri.rawUserInfo.isNullOrBlank()) {
            return null
        }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val portPart = if (uri.port == -1) "" else ":${uri.port}"
        return "${uri.scheme.lowercase()}://$host$portPart$path"
    }

    private val RELAY_SCHEMES = setOf("http", "https")

    /**
     * Spec-38 T4 (#256) — the public encrypted-DNS endpoint (RFC 8484
     * wireformat) every domain lookup rides while DoH is enabled. The query
     * itself is ordinary HTTPS pinned to [DOH_BOOTSTRAP_IPS], so the provider
     * and the local network see only a connection to dns.google — never which
     * book domains were opened.
     */
    const val DOH_URL = "https://dns.google/dns-query"

    /**
     * dns.google's addresses — the DoH server must be reachable WITHOUT any
     * DNS lookup (bootstrap by IP), or resolving the resolver would leak the
     * very names DoH hides.
     */
    val DOH_BOOTSTRAP_IPS = listOf("8.8.8.8", "8.8.4.4")

    /**
     * Resolves the DNS half of the prefs into a strategy. Deliberately
     * INDEPENDENT of [resolve] (spec-38 T4 AC): the route (прямо / проксі /
     * Tor / реле) and name resolution are two separate decisions — DoH
     * behaves identically on any route, because a lookup is itself just
     * transport traffic and rides whatever route is installed.
     */
    fun resolveDns(prefs: PrivacyPrefs): DnsStrategy =
        if (prefs.dohEnabled) DnsStrategy.DohFirst(DOH_URL) else DnsStrategy.SystemOnly
}

/**
 * Spec-38 T4 (#256) — how the transport turns hostnames into addresses.
 * Availability above secrecy: even the DoH-first strategy never fails a
 * request over an unreachable resolver — its fallback chain degrades to the
 * system resolver transparently ([FallbackDns], thin glue beside this door).
 */
sealed interface DnsStrategy {

    /** Encrypted lookups first; the system resolver only as silent fallback. */
    data class DohFirst(val serverUrl: String) : DnsStrategy

    /** Plain system resolution — the pre-spec-38 behaviour, opt-out via settings. */
    object SystemOnly : DnsStrategy
}

/** The door's outcome: either a usable route or a reason for the UI. */
sealed interface RouteResolution {
    data class Ok(val route: NetworkRoute) : RouteResolution
    data class Invalid(val reason: String) : RouteResolution
}
