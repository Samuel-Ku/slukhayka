package com.slukhayka.audiobooks.data.privacy

import okhttp3.Dns
import java.net.InetAddress

/**
 * Spec-38 T4 (#256) — the transparent fallback chain behind the DNS door:
 * the primary (DoH) resolver answers first; ANY of its failures degrades to
 * the system resolver with no error to the caller — доступність важливіша за
 * прихованість імен. Only when the fallback itself fails does the exception
 * surface (the transport's ordinary degrade paths take it from there).
 *
 * Pure JVM beside the door: [onFallback] is the seam the thin glue uses for
 * diagnosability logging, so this class itself stays free of Android types
 * and testable with fake resolvers.
 */
class FallbackDns(
    private val primary: Dns,
    private val fallback: Dns,
    private val onFallback: (hostname: String, failure: Exception) -> Unit = { _, _ -> }
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> = try {
        primary.lookup(hostname)
    } catch (primaryFailure: Exception) {
        onFallback(hostname, primaryFailure)
        fallback.lookup(hostname)
    }
}
