package com.slukhayka.audiobooks.data.recommend

import java.io.File
import java.net.URI

/** Call on IO. One bounded public request per day, no per-book requests or uploads. */
class RecommendationGraphStore(
    private val directory: File,
    private val url: String,
    private val fetch: (String, Int) -> String?,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val snapshot get() = File(directory, "content-graph.json")
    private val attempt get() = File(directory, "content-graph-attempt.txt")

    @Synchronized
    fun refresh(): PrecomputedRecommendationGraph? {
        val now = clock()
        val cached = runCatching {
            snapshot.takeIf { it.length() <= PrecomputedRecommendationGraph.MAX_BYTES }?.readText()
                ?.let { PrecomputedRecommendationGraph.parse(it, now) }
        }.getOrNull()
        val safeUrl = runCatching { URI(url).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
        if (!safeUrl) return cached
        val lastAttempt = runCatching { attempt.readText().toLong() }.getOrDefault(0L)
        if (lastAttempt > 0 && now >= lastAttempt && now - lastAttempt < 86_400_000L) return cached
        return runCatching {
            directory.mkdirs()
            // Persist before HTTP so a dead endpoint cannot cause a request at every launch.
            attempt.writeText(now.toString())
            val body = fetch(url, PrecomputedRecommendationGraph.MAX_BYTES) ?: return cached
            val graph = PrecomputedRecommendationGraph.parse(body, now) ?: return cached
            val temporary = File(directory, "content-graph.tmp")
            temporary.writeText(body)
            check(temporary.renameTo(snapshot))
            graph
        }.getOrElse { cached }
    }
}
