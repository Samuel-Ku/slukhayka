package com.slukhayka.audiobooks.data.privacy

import android.util.Log
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

/**
 * Spec-38 T4 (#256) — the process-wide DNS resolver behind the door: every
 * transport consults [lookup] per hostname. Installed alongside the route
 * from the same prefs ([TransportPrivacy.install] delegates here), so the
 * «DoH увімкнено / фолбек» decision always mirrors what the listener saved.
 *
 * Thin glue by contract (spec-38 Testing Decisions): the decision itself is
 * pure ([NetworkPrivacy.resolveDns], [FallbackDns]); this object only builds
 * the real RFC-8484 resolver (OkHttp `DnsOverHttps`, pinned to
 * [NetworkPrivacy.DOH_BOOTSTRAP_IPS] — the DoH server is reached WITHOUT any
 * DNS query, so nothing leaks) and logs the transparent fallback. #516: the
 * fallback runs only while the listener is on DIRECT — under a CHOSEN
 * privacy route the strict DoH resolver fails the lookup (and with it the
 * request) instead of leaking the name through the system resolver. A failed
 * lookup is still logged loudly either way (repo rule: silent degradations
 * must stay diagnosable).
 */
object TransportDns : Dns {

    private const val TAG = "TransportDns"

    @Volatile
    private var strategy: DnsStrategy = NetworkPrivacy.resolveDns(PrivacyPrefs())

    /** Mirrors the persisted prefs into the per-lookup decision. */
    fun install(prefs: PrivacyPrefs) {
        strategy = NetworkPrivacy.resolveDns(prefs)
    }

    override fun lookup(hostname: String): List<InetAddress> = when (val current = strategy) {
        is DnsStrategy.SystemOnly -> systemResolver().lookup(hostname)
        is DnsStrategy.DohFirst -> dohFirst().lookup(hostname)
    }

    private var cachedClient: OkHttpClient? = null
    private var cachedResolver: Dns? = null

    /** Rebuild the bootstrap client when its immutable transport route changes. */
    @Synchronized
    private fun dohFirst(): Dns {
        val client = TransportClients.okHttp
        if (cachedClient === client) return checkNotNull(cachedResolver)
        val resolver = FallbackDns(
            primary = DnsOverHttps.Builder()
                .client(client)
                .url(NetworkPrivacy.DOH_URL.toHttpUrl())
                .bootstrapDnsHosts(NetworkPrivacy.DOH_BOOTSTRAP_IPS.map { InetAddress.getByName(it) })
                .build(),
            fallback = systemResolver(),
            onFallback = { hostname, failure ->
                if ((strategy as? DnsStrategy.DohFirst)?.allowSystemFallback == false) throw failure
                Log.w(TAG, "DoH недоступний ($hostname) — прозорий фолбек на системний резолвер", failure)
            }
        )
        cachedClient = client
        cachedResolver = resolver
        return resolver
    }

    /**
     * Indirection only so JVM unit tests of pure pieces never touch Android;
     * production always gets OkHttp's platform resolver.
     */
    internal fun systemResolver(): Dns = Dns.SYSTEM
}
