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
     * the caller (the settings UI shows it).
     */
    fun install(prefs: PrivacyPrefs): RouteResolution = when (val resolution = NetworkPrivacy.resolve(prefs)) {
        is RouteResolution.Ok -> {
            route = resolution.route
            TransportDns.install(prefs)
            resolution
        }
        is RouteResolution.Invalid -> resolution
    }

    fun current(): NetworkRoute = route

    /**
     * The `java.net.Proxy` the transport opens connections through, or null
     * for a direct connection. Hosts resolve lazily at connect time
     * (`createUnresolved`) — with SOCKS5 this keeps DNS inside Tor instead
     * of leaking lookups through the system resolver.
     */
    fun currentJavaProxy(): Proxy? = when (val r = route) {
        is NetworkRoute.Direct -> null
        is NetworkRoute.Proxy -> Proxy(
            if (r.type == RouteProxyType.HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS,
            InetSocketAddress.createUnresolved(r.host, r.port)
        )
    }
}
