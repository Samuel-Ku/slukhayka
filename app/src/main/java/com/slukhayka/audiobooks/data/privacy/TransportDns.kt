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
        is DnsStrategy.DohFirst -> dohFirst.lookup(hostname)
    }

    /**
     * DoH first. Built once, lazily — the first lookup happens off the main
     * thread. The DoH client rides the SAME route as traffic
     * ([TransportClients.okHttp]'s proxy selector), which is exactly the
     * route-independence criterion; its own hostname resolves via the pinned
     * bootstrap IPs inside DnsOverHttps, so the trampoline below is never
     * re-entered (no recursion).
     *
     * #516 — the fallback half is honoured per the CURRENT strategy at
     * lookup time: DIRECT keeps the transparent system fallback, a chosen
     * Tor/проксі/реле keeps lookups strictly inside the route.
     */
    private val dohFirst: Dns by lazy {
        val doh = DnsOverHttps.Builder()
            .client(TransportClients.okHttp)
            .url(NetworkPrivacy.DOH_URL.toHttpUrl())
            .bootstrapDnsHosts(NetworkPrivacy.DOH_BOOTSTRAP_IPS.map { InetAddress.getByName(it) })
            .build()
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val allowFallback = (strategy as? DnsStrategy.DohFirst)?.allowSystemFallback ?: true
                val primary = try {
                    doh.lookup(hostname)
                } catch (primaryFailure: Exception) {
                    Log.w(TAG, "DoH недоступний ($hostname)", primaryFailure)
                    if (!allowFallback) {
                        // #516 — the chosen route is mandatory: no system
                        // resolver, the failure rides the ordinary transport
                        // degrade path.
                        throw primaryFailure
                    }
                    Log.w(
                        TAG,
                        "DoH недоступний ($hostname) — прозорий фолбек на системний резолвер",
                        primaryFailure
                    )
                    return systemResolver().lookup(hostname)
                }
                return primary
            }
        }
    }

    /**
     * Indirection only so JVM unit tests of pure pieces never touch Android;
     * production always gets OkHttp's platform resolver.
     */
    internal fun systemResolver(): Dns = Dns.SYSTEM
}
