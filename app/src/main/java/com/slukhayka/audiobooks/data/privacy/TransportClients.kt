package com.slukhayka.audiobooks.data.privacy

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Spec-38 T4 (#256) — the ONE OkHttp client of the transport stack: the
 * shared fetcher and the Coil image loader ride the same connection pool,
 * the same browser identity ([BrowserIdentity]), the same privacy route
 * (per-request via a trampoline [ProxySelector] — an empty selection IS the
 * honest «прямо», a chosen proxy that fails is NOT retried direct) and the
 * same encrypted name resolution ([TransportDns]).
 *
 * Consolidates the client MainActivity previously built inline for Coil
 * (spec-38 T1+T2); nothing else in the process should build its own.
 */
object TransportClients {

    /** The fetcher's former per-connection budgets, now shared stack-wide. */
    private const val CONNECT_TIMEOUT_SECONDS = 12L
    private const val READ_TIMEOUT_SECONDS = 18L

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .proxySelector(object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> =
                    listOfNotNull(TransportPrivacy.currentJavaProxy())

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                    // Honest failure: OkHttp surfaces the exception; no
                    // direct fallback is attempted here.
                }
            })
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", BrowserIdentity.currentUserAgent())
                        .build()
                )
            }
            .dns(TransportDns)
            .build()
    }

    /**
     * #516 — the PLAYBACK engine: the same pool, identity, privacy route and
     * app DNS as [okHttp], with playback-sized budgets (spec-13 T2's former
     * per-connection timeouts) and the SEC-018 redirect rule enforced per
     * hop ([PlaybackRedirects]) — same-protocol redirects are followed, a
     * cleartext downgrade is refused. Media3's OkHttp data source rides this
     * client, so streaming obeys the identical network policy as downloads
     * and page fetches.
     */
    val playbackOkHttp: OkHttpClient by lazy {
        okHttp.newBuilder()
            .connectTimeout(PLAYBACK_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(PLAYBACK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .addInterceptor(PlaybackRedirects.interceptor())
            .build()
    }

    /** Playback keeps spec-13 T2's streaming budgets. */
    private const val PLAYBACK_CONNECT_TIMEOUT_SECONDS = 15L
    private const val PLAYBACK_READ_TIMEOUT_SECONDS = 30L
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
                request = request.newBuilder()
                    .url(location)
                    .removeHeader("Referer")
                    .build()
            }
        }
    }
}
