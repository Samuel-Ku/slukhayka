package com.slukhayka.audiobooks.data.recommend

import java.io.File
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
    private val snapshot get() = File(directory, "content-graph.json")
    private val signature get() = File(directory, "content-graph.json.sig")
    private val attempt get() = File(directory, "content-graph-attempt.txt")

    @Synchronized
    fun refresh(): PrecomputedRecommendationGraph? {
        val now = clock()
        val cached = runCatching { cachedGraph(now) }.getOrNull()
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
            val body = fetch(url, PrecomputedRecommendationGraph.MAX_BYTES) ?: return cached
            val signed = fetch("$url.sig", MAX_SIGNATURE_BYTES) ?: return cached
            if (!verify(body, signed)) return cached
            val graph = PrecomputedRecommendationGraph.parse(body, now) ?: return cached
            replace(body, signed)
            graph
        }.getOrElse { cached }
    }

    private fun cachedGraph(now: Long): PrecomputedRecommendationGraph? {
        if (snapshot.length() !in 1..PrecomputedRecommendationGraph.MAX_BYTES.toLong()) return null
        val body = snapshot.readText()
        val signed = signature.takeIf { it.length() in 1..MAX_SIGNATURE_BYTES.toLong() }?.readText()
            ?: return null
        if (!verify(body, signed)) return null
        return PrecomputedRecommendationGraph.parse(body, now)
    }

    private fun replace(body: String, signed: String) {
        val bodyTemporary = File(directory, "content-graph.tmp")
        val signatureTemporary = File(directory, "content-graph.tmp.sig")
        bodyTemporary.writeText(body)
        signatureTemporary.writeText(signed)
        check(bodyTemporary.renameTo(snapshot))
        check(signatureTemporary.renameTo(signature))
    }

    private companion object {
        const val MAX_SIGNATURE_BYTES = 4 * 1024
    }
}
