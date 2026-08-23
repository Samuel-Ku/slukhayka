package com.slukhayka.audiobooks.data.privacy

import java.net.URLEncoder

/**
 * Spec-38 (#252) — the resolved network route behind the privacy module's
 * single door. Pure model: the transport converts [Direct] to a plain
 * connection, [Proxy] to a `java.net.Proxy` and [Relay] to a rewritten
 * request URL (thin glue).
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

    /**
     * Spec-38 T6 (#258) — the self-hosted relay prototype (the reference
     * recipe is a Cloudflare Worker, `docs/recipes/workers-relay.md`): every
     * transport request is rewritten to `<base>?url=<encoded target>` and the
     * relay fetches the target itself, so the source sees the relay's exit
     * address, never the listener's. NOT a wire-level proxy — the connection
     * opens straight to [baseUrl]; there is no `java.net.Proxy` for this
     * route.
     *
     * Honest trust model (ADR-0014): the relay terminates the app's TLS and
     * originates its own to the source, so whoever runs [baseUrl] sees ALL
     * fetched URLs (and response metadata) — a self-deployed worker trusts
     * yourself, a third-party relay trusts its operator completely. The relay
     * is never a default; it exists so a future paid tier can be validated
     * against the ordinary route machinery today.
     *
     * @param baseUrl normalized origin (optionally with a path prefix),
     *   never with a trailing slash, query or fragment — [NetworkPrivacy]
     *   guarantees the shape at resolve time.
     */
    data class Relay(val baseUrl: String) : NetworkRoute {

        /**
         * The one rewrite rule: target → `<base>?url=<form-encoded target>`.
         * Total and pure — the worker side owns rejecting non-http(s) targets;
         * this function never inspects or alters [targetUrl] itself.
         */
        fun rewrite(targetUrl: String): String =
            baseUrl + QUERY_PARAM_URL + URLEncoder.encode(targetUrl, Charsets.UTF_8)

        private companion object {
            const val QUERY_PARAM_URL = "?url="
        }
    }
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
 * decisions. [RELAY] (spec-38 T6, #258) is the relay-prototype route — it is
 * never selected by any default; only a conscious listener choice lands here.
 */
enum class RouteMode { DIRECT, CUSTOM_PROXY, MAX_PRIVACY, RELAY }

data class PrivacyPrefs(
    val routeMode: RouteMode = RouteMode.DIRECT,
    val proxyAddress: String = "",
    /**
     * Spec-38 T4 (#256) — encrypted DNS (DoH). Stored verbatim; the
     * interpretation lives in [NetworkPrivacy.resolveDns]. The ticket's own
     * default: увімкнений — availability is guarded by the transparent
     * system-resolver fallback, not by a disabled feature.
     */
    val dohEnabled: Boolean = true
)
