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
 * DNS query, so nothing leaks) and logs the transparent fallback. A failed
 * DoH lookup never fails the request — the system resolver answers instead,
 * silently for the user, loudly in the log (repo rule: silent degradations
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
        is DnsStrategy.DohFirst -> dohFirst.lookup(hostname)
    }

    /**
     * DoH first, system resolver as the transparent fallback. Built once,
     * lazily — the first lookup happens off the main thread. The DoH client
     * rides the SAME route as traffic ([TransportClients.okHttp]'s proxy
     * selector), which is exactly the route-independence criterion; its own
     * hostname resolves via the pinned bootstrap IPs inside DnsOverHttps, so
     * the trampoline below is never re-entered (no recursion).
     */
    private val dohFirst: Dns by lazy {
        FallbackDns(
            primary = DnsOverHttps.Builder()
                .client(TransportClients.okHttp)
                .url(NetworkPrivacy.DOH_URL.toHttpUrl())
                .bootstrapDnsHosts(NetworkPrivacy.DOH_BOOTSTRAP_IPS.map { InetAddress.getByName(it) })
                .build(),
            fallback = systemResolver(),
            onFallback = { hostname, failure ->
                Log.w(TAG, "DoH недоступний ($hostname) — прозорий фолбек на системний резолвер", failure)
            }
        )
    }

    /**
     * Indirection only so JVM unit tests of pure pieces never touch Android;
     * production always gets OkHttp's platform resolver.
     */
    internal fun systemResolver(): Dns = Dns.SYSTEM
}
