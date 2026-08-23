package com.slukhayka.audiobooks.data.privacy

import okhttp3.OkHttpClient
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
}
