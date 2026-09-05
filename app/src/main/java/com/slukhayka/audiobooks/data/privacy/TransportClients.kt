package com.slukhayka.audiobooks.data.privacy

import okhttp3.Interceptor
import okhttp3.Response
import java.net.URI
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Connections belong to one immutable privacy route, including streamed response bodies. */
object TransportClients {
    private class Clients(val route: NetworkRoute) {
        private val active = ConcurrentHashMap.newKeySet<Call>()
        val http = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .proxy(when (route) {
                is NetworkRoute.Proxy -> Proxy(
                    if (route.type == RouteProxyType.HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS,
                    InetSocketAddress.createUnresolved(route.host, route.port)
                )
                else -> Proxy.NO_PROXY
            })
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { active.add(call) }
                override fun callEnd(call: Call) { active.remove(call) }
                override fun callFailed(call: Call, ioe: IOException) { active.remove(call) }
            })
            .addInterceptor { chain ->
                // Retained clients and calls created just before a settings change fail closed.
                if (TransportPrivacy.current() != route) throw IOException("Network route changed")
                chain.proceed(chain.request().newBuilder()
                    .header("User-Agent", BrowserIdentity.currentUserAgent()).build())
            }
            .dns(TransportDns)
            .build()
        val playback = http.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .followRedirects(false)
            .addInterceptor(PlaybackRedirects.interceptor())
            .build()

        fun close() {
            // Dispatcher alone no longer owns synchronous calls after response headers arrive.
            active.forEach { it.cancel() }
            http.connectionPool.evictAll()
        }
    }

    private var clients: Clients? = null

    @Synchronized
    private fun currentClients(): Clients {
        val route = TransportPrivacy.current()
        val previous = clients
        if (previous != null && previous.route == route) return previous
        previous?.close()
        return Clients(route).also { clients = it }
    }

    @Synchronized
    internal fun routeChanged() {
        clients?.close()
        clients = null
    }

    val okHttp: OkHttpClient get() = currentClients().http
    val playbackOkHttp: OkHttpClient get() = currentClients().playback
    val playbackHttp: OkHttpClient get() = currentClients().playback

    /** Long-lived Coil and Media3 factories select the current route for every new call. */
    val calls = Call.Factory { request -> okHttp.newCall(request) }
    val playbackCalls = Call.Factory { request -> playbackHttp.newCall(request) }
}

/**
 * #516 — the SEC-018 redirect rule as pure code + the application seam.
 * [follow] is the decision (pure JVM): a Location is followed only when its
 * resolved scheme equals the request's scheme — a cleartext downgrade
 * (https→http) is refused. [interceptor] applies it per hop: responses with
 * a redirect status + Location loop up to [MAX_REDIRECT_HOPS] times and then
 * fail the request honestly (an unbounded loop must never hang the player).
 * Range headers ride every hop untouched; the Referer is NOT forwarded onto
 * a different host (the header seam owns per-source Referers — a hop to
 * another origin must not inherit this source's identity).
 */
object PlaybackRedirects {

    /** Redirect hops stay bounded — a loop fails honestly, never hangs. */
    private const val MAX_REDIRECT_HOPS = 5

    fun follow(requestUrl: String, location: String): Boolean {
        val base = runCatching { URI(requestUrl) }.getOrNull() ?: return false
        val resolved = runCatching { base.resolve(location.trim()) }.getOrNull() ?: return false
        val from = base.scheme?.lowercase()
        val to = resolved.scheme?.lowercase()
        return from != null && to != null && from == to
    }

    fun interceptor(): Interceptor = object : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request()
            var hops = 0
            while (true) {
                val response: Response = chain.proceed(request)
                val location = response.header("Location")
                val status = response.code
                val isRedirect = (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) &&
                    !location.isNullOrBlank()
                if (!isRedirect) return response
                response.close()
                hops++
                if (hops > MAX_REDIRECT_HOPS) {
                    throw IOException("Перевищено ліміт переходів (${MAX_REDIRECT_HOPS}) за редиректами")
                }
                if (!follow(request.url.toString(), location!!)) {
                    throw IOException("Редирект на інший протокол заборонено: $location")
                }
                val target = request.url.resolve(location)
                    ?: throw IOException("Не вдалося визначити адресу перенаправлення")
                request = request.newBuilder().url(target).apply {
                    if (target.host != request.url.host || target.port != request.url.port) {
                        removeHeader("Referer")
                        removeHeader("Authorization")
                        removeHeader("Cookie")
                    }
                }.build()
            }
        }
    }
}
