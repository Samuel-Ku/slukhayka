package com.slukhayka.audiobooks.data.privacy

import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Spec-38 (#252) — the process-wide door state: the route every transport
 * consults per request. Installed once at startup from the persisted prefs
 * and re-installed whenever the listener changes privacy settings.
 *
 * The default (nothing installed, JVM tests) is the honest DIRECT route —
 * identical behaviour to before spec-38.
 */
object TransportPrivacy {

    @Volatile
    private var route: NetworkRoute = NetworkRoute.Direct

    /**
     * Resolves [prefs] through the door and installs the result when valid;
     * an invalid resolution changes nothing and carries its reason back to
     * the caller (the settings UI shows it). The DNS half of the same prefs
     * (spec-38 T4, [TransportDns]) installs alongside the route, so both
     * decisions always mirror what the listener saved.
     */
    fun install(prefs: PrivacyPrefs): RouteResolution = when (val resolution = NetworkPrivacy.resolve(prefs)) {
        is RouteResolution.Ok -> {
            val changed = route != resolution.route
            route = resolution.route
            if (changed) TransportClients.routeChanged()
            TransportDns.install(prefs)
            resolution
        }
        is RouteResolution.Invalid -> resolution
    }

    fun current(): NetworkRoute = route

    /**
     * Spec-38 T6 (#258) — the relay seam for URL-based transports: the target
     * URL passes through untouched unless the resolved route is a
     * [NetworkRoute.Relay], in which case it comes back rewritten to
     * `<relay base>?url=<encoded target>`. Pure string mapping; the caller
     * opens the connection to the result exactly as before.
     */
    fun rewriteThroughRelay(targetUrl: String): String = when (val r = route) {
        is NetworkRoute.Relay -> r.rewrite(targetUrl)
        else -> targetUrl
    }

    /** True only when the listener consciously chose the relay route. */
    fun isRelayActive(): Boolean = route is NetworkRoute.Relay

    /**
     * The `java.net.Proxy` the transport opens connections through, or null
     * for a direct connection. Hosts resolve lazily at connect time
     * (`createUnresolved`) — with SOCKS5 this keeps DNS inside Tor instead
     * of leaking lookups through the system resolver. The relay route also
     * yields null: its connection opens straight to the relay origin and all
     * indirection lives in the rewritten URL ([rewriteThroughRelay]).
     */
    fun currentJavaProxy(): Proxy? = when (val r = route) {
        is NetworkRoute.Direct -> null
        is NetworkRoute.Proxy -> Proxy(
            if (r.type == RouteProxyType.HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS,
            InetSocketAddress.createUnresolved(r.host, r.port)
        )
        is NetworkRoute.Relay -> null
    }
}
