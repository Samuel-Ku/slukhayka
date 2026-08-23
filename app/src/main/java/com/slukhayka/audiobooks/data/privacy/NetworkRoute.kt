package com.slukhayka.audiobooks.data.privacy

/**
 * Spec-38 (#252) — the resolved network route behind the privacy module's
 * single door. Pure model: the transport converts [Direct] to a plain
 * connection and [Proxy] to a `java.net.Proxy` (thin glue).
 */
sealed interface NetworkRoute {

    /** No indirection — the honest default. */
    object Direct : NetworkRoute

    /** All transport traffic rides this endpoint. */
    data class Proxy(
        val type: RouteProxyType,
        val host: String,
        val port: Int
    ) : NetworkRoute
}

/** Wire-level proxy flavour the resolver can produce. */
enum class RouteProxyType { HTTP, SOCKS5 }

/**
 * One parsed proxy endpoint — the intermediate shape between raw text input
 * and a [NetworkRoute.Proxy].
 */
data class ProxyEndpoint(val type: RouteProxyType, val host: String, val port: Int)

/**
 * The user's persisted privacy choice (spec-38 T2). Stored verbatim; the
 * interpretation lives in [NetworkPrivacy.resolve] so settings never embed
 * decisions.
 */
enum class RouteMode { DIRECT, CUSTOM_PROXY, MAX_PRIVACY }

data class PrivacyPrefs(
    val routeMode: RouteMode = RouteMode.DIRECT,
    val proxyAddress: String = ""
)
