package com.slukhayka.audiobooks.data.privacy

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
     *   understandable reason instead of guessing.
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
}

/** The door's outcome: either a usable route or a reason for the UI. */
sealed interface RouteResolution {
    data class Ok(val route: NetworkRoute) : RouteResolution
    data class Invalid(val reason: String) : RouteResolution
}
