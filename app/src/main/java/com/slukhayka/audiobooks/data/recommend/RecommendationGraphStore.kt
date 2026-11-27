package com.slukhayka.audiobooks.data.recommend

import java.io.File
import java.io.FileOutputStream
import java.net.URI

/**
 * Call on IO. One bounded public request per day, no per-book requests or uploads.
 *
 * Both the fetched and the cached graph must carry a valid pinned signature;
 * an unsigned, tampered or missing-signature artifact never replaces the good
 * cache. A blank endpoint makes no network request at all.
 */
class RecommendationGraphStore(
    private val directory: File,
    private val url: String,
    private val verify: (body: String, signature: String) -> Boolean,
    private val fetch: (String, Int) -> String?,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val snapshot get() = File(directory, "content-graph.signed.json")
    private val attempt get() = File(directory, "content-graph-attempt.txt")

    // This store owns its cache. Retain only the verified parsed snapshot; another
    // instance/process revalidates disk. Every use still checks the snapshot TTL.
    private var cacheLoaded = false
    private var acceptedGraph: PrecomputedRecommendationGraph? = null

    @Synchronized
    fun refresh(): PrecomputedRecommendationGraph? {
        val now = clock()
        if (!cacheLoaded) {
            acceptedGraph = runCatching { cachedGraph(now) }.getOrNull()
            cacheLoaded = true
        }
        val cached = acceptedGraph?.takeIf { it.isFresh(now) }
        val safeUrl = runCatching {
            URI(url).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null }
        }.getOrDefault(false)
        if (!safeUrl) return cached
        val lastAttempt = runCatching { attempt.readText().toLong() }.getOrDefault(0L)
        if (lastAttempt > 0 && now >= lastAttempt && now - lastAttempt < 86_400_000L) return cached
        return runCatching {
            directory.mkdirs()
            // Persist before HTTP so a dead endpoint cannot cause a request at every launch.
            attempt.writeText(now.toString())
            val envelope = fetch(url, SignedGraphEnvelope.MAX_BYTES) ?: return cached
            val payload = SignedGraphEnvelope.decode(envelope) ?: return cached
            if (!verify(payload.graph, payload.signature)) return cached
            val graph = PrecomputedRecommendationGraph.parse(payload.graph, now) ?: return cached
            replace(envelope)
            acceptedGraph = graph
            graph
        }.getOrElse { cached }
    }

    private fun cachedGraph(now: Long): PrecomputedRecommendationGraph? {
        if (snapshot.isFile) {
            if (snapshot.length() !in 1..SignedGraphEnvelope.MAX_BYTES.toLong()) return null
            val payload = SignedGraphEnvelope.decode(snapshot.readText()) ?: return null
            if (!verify(payload.graph, payload.signature)) return null
            return PrecomputedRecommendationGraph.parse(payload.graph, now)
        }
        // Read-only migration: older APKs stored a raw graph and a detached signature.
        val legacy = File(directory, "content-graph.json")
        val signature = File(directory, "content-graph.json.sig")
        if (legacy.length() !in 1..PrecomputedRecommendationGraph.MAX_BYTES.toLong() ||
            signature.length() !in 1..4096L) return null
        val body = legacy.readText()
        if (!verify(body, signature.readText())) return null
        return PrecomputedRecommendationGraph.parse(body, now)
    }

    private fun replace(envelope: String) {
        val temporary = File(directory, "content-graph.signed.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(envelope.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        // Both values move together. Failure before this rename leaves the old snapshot intact.
        check(temporary.renameTo(snapshot))
    }
}
