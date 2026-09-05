package com.slukhayka.audiobooks.data.privacy

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
    val playbackHttp: OkHttpClient get() = currentClients().playback

    /** Long-lived Coil and Media3 factories select the current route for every new call. */
    val calls = Call.Factory { request -> okHttp.newCall(request) }
    val playbackCalls = Call.Factory { request -> playbackHttp.newCall(request) }
}
