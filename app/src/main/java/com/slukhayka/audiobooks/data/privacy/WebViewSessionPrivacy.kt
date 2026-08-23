package com.slukhayka.audiobooks.data.privacy

/**
 * Spec-38 T3 (#255) — the WebView session door, the sibling of
 * [TransportPrivacy] for source-browser sessions. Two decisions live here,
 * both pure:
 *
 * 1. **The route.** WebView navigation rides the SAME exit as the transport:
 *    the resolved [NetworkRoute] converts to one webkit proxy rule
 *    (`http://host:port` / `socks5://host:port`) applied through the official
 *    `androidx.webkit.ProxyController` — process-wide, all WebView sessions.
 *    The rule set carries NO `addDirect()` fallback: a chosen proxy that
 *    cannot connect fails navigation honestly, exactly like [HttpFetcher]
 *    never retries direct. On a device whose system WebView is too old for
 *    `PROXY_OVERRIDE`, a routed session is REFUSED with an understandable
 *    reason instead of silently going direct; «прямо» keeps working as before.
 * 2. **The lockdown script.** One short script neutralises the geolocation
 *    and sensor JS-API surface (Generic Sensor classes, device
 *    orientation/motion events) before any page script runs — injected via
 *    `WebViewCompat.addDocumentStartJavaScript` where supported, otherwise as
 *    a best-effort page-start injection (timing races page scripts; documented,
 *    not hidden). Geolocation is additionally hard-off through the native
 *    `WebSettings.geolocationEnabled=false` + prompt-deny wiring.
 */
object WebViewSessionPrivacy {

    /**
     * The resolved WebView route behind the door.
     * - [SystemDefault]: no override — WebView behaves exactly as before
     *   spec-38 (system defaults), the honest «прямо».
     * - [Routed]: every WebView connection is pinned to this single rule.
     */
    sealed interface SessionRoute {
        object SystemDefault : SessionRoute
        data class Routed(val proxyRule: String) : SessionRoute
    }

    /** The door's outcome: a usable WebView route or an honest refusal. */
    sealed interface RouteResolution {
        data class Ok(val route: SessionRoute) : RouteResolution
        data class Refused(val reason: String) : RouteResolution
    }

    /**
     * Resolves the transport's own route into a WebView session route.
     * Reads [TransportPrivacy.current()] by default so the two doors can
     * never drift apart; tests pass the route explicitly.
     *
     * A proxy route on a WebView without `PROXY_OVERRIDE` support refuses —
     * the alternative would be a silent direct connection past the chosen
     * exit, which the privacy module's contract forbids. The relay route
     * ([NetworkRoute.Relay]) refuses too: it indirections by REWRITING
     * request URLs for background transport fetches, and an interactive
     * browser session cannot ride it without breaking page origins,
     * Cloudflare clearance and cookies — refusing beats both a silent direct
     * session and a broken one.
     */
    fun resolve(
        proxyOverrideSupported: Boolean,
        route: NetworkRoute = TransportPrivacy.current()
    ): RouteResolution = when (route) {
        is NetworkRoute.Direct -> RouteResolution.Ok(SessionRoute.SystemDefault)
        is NetworkRoute.Proxy ->
            if (!proxyOverrideSupported) {
                RouteResolution.Refused(UNSUPPORTED_REASON)
            } else {
                RouteResolution.Ok(SessionRoute.Routed(proxyRule(route)))
            }
        is NetworkRoute.Relay -> RouteResolution.Refused(RELAY_REASON)
    }

    /**
     * The webkit rule string for one proxy endpoint — format
     * `[scheme://]host[:port]` per `ProxyConfig.Builder`: `http://…` or
     * `socks5://…`. No fallback rules are appended by contract.
     */
    fun proxyRule(proxy: NetworkRoute.Proxy): String =
        (if (proxy.type == RouteProxyType.HTTP) "http" else "socks5") +
            "://" + proxy.host + ":" + proxy.port

    /**
     * The JS-API lockdown script. Idempotent and side-effect-free beyond the
     * denial itself: it makes `navigator.geolocation` and every Generic
     * Sensor / device-motion-orientation constructor permanently `undefined`
     * (non-writable, non-configurable), so feature detection inside pages
     * sees them as unsupported. Kept minimal because document-start
     * injection blocks page loading while it runs.
     */
    fun lockdownScript(): String = buildString {
        append("(function(){var d=Object.defineProperty,w=window;")
        append("try{d(navigator,'geolocation',{value:void 0,writable:false,configurable:false})}catch(e){}")
        append(SENSOR_GLOBALS.joinToString(separator = ",") { name -> "try{d(w,'$name',{value:void 0,writable:false,configurable:false})}catch(e){}" })
        append("})();")
    }

    /** JS globals the lockdown removes from pages' reach. */
    private val SENSOR_GLOBALS = listOf(
        "DeviceOrientationEvent",
        "DeviceMotionEvent",
        "Accelerometer",
        "Gyroscope",
        "LinearAccelerationSensor",
        "AbsoluteOrientationSensor",
        "RelativeOrientationSensor",
        "Magnetometer",
        "AmbientLightSensor"
    )

    private const val UNSUPPORTED_REASON =
        "Системний WebView цього пристрою не підтримує проксі-маршрут. " +
            "Щоб не з'єднуватися напряму повз обраний маршрут, браузер джерела вимкнено — " +
            "оновіть Android System WebView або поверніть маршрут «прямо»."

    private const val RELAY_REASON =
        "Маршрут реле переписує адреси лише для фонового транспорту — " +
            "браузер джерела ним їхати не може. Оберіть прямий маршрут або проксі."
}
