package com.slukhayka.audiobooks.player

import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.YouTubeTracks
import com.slukhayka.audiobooks.data.source.headersFor
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

/**
 * #1751 — one cast session's upstream door.
 *
 * The receiver only ever asks the phone's [PlaybackProxy] for `/chN`; it never
 * touches the internet itself (ADR-0043). A persisted locator can therefore be
 * a YouTube WATCH URL, which is an identity, not audio: fetching it verbatim
 * returns HTML (or a 502), the receiver lands in `IDLE_REASON_ERROR`, and the
 * cast looks active while it is silent. The local engine already resolves that
 * watch URL per use ([com.slukhayka.audiobooks.data.source.YouTubeStreamResolver]);
 * this door does the same for the proxy, with the same one-retry budget
 * ([StreamHealPolicy.MAX_HEAL_ATTEMPTS]).
 *
 * The signed audio URL is cached for the session, because the proxy gets many
 * range requests per chapter. When it expires, the failed fetch invalidates
 * the cache and the chapter is resolved exactly once more. Plain (non-YouTube)
 * URLs pass through untouched and keep their existing page-heal door
 * ([healer]).
 */
internal class CastUpstream(
    chapterUrls: List<String?>,
    private val bookId: String?,
    private val fetcher: HttpFetcher,
    private val resolver: (suspend (String) -> String?)? = null,
    private val healer: (suspend (String, Int, String) -> String?)? = null,
    /** Initial resolution plus one stale-URL re-resolution. */
    private val maxResolveAttempts: Int = StreamHealPolicy.MAX_HEAL_ATTEMPTS + 1,
    private val headers: (String) -> Map<String, String> = { url ->
        headersFor(sourceIdForUrl(url), url)
    }
) {

    /** Healed plain URLs live for the session; the persisted list is the identity. */
    private val urls = CopyOnWriteArrayList(chapterUrls)

    private val resolvedByChapter = ConcurrentHashMap<Int, String>()
    private val resolveAttemptsByChapter = ConcurrentHashMap<Int, Int>()
    private val healAttemptsByChapter = ConcurrentHashMap<Int, Int>()
    private val resolutionLock = Any()

    /** The [PlaybackProxy.Upstream] entry point. */
    fun open(subPath: String?, rangeHeader: String?): PlaybackProxy.UpstreamResponse? {
        val index = subPath?.removePrefix("ch")?.toIntOrNull() ?: return null
        val persistedUrl = urls.getOrNull(index) ?: return null
        val firstUrl = playableUrl(index, persistedUrl) ?: return null
        val first = fetch(firstUrl, persistedUrl, rangeHeader)
        if (first != null) return toResponse(first, firstUrl)

        // The resolved URL went stale (signed URLs expire) or a plain source
        // moved. Spend the ONE retry: re-resolve or re-fetch the page.
        val retryUrl = if (YouTubeTracks.isYouTubeWatchUrl(persistedUrl)) {
            resolvedByChapter.remove(index)
            playableUrl(index, persistedUrl)
        } else {
            heal(index, persistedUrl, firstUrl)
        } ?: return null
        val retry = fetch(retryUrl, persistedUrl, rangeHeader) ?: return null
        if (!YouTubeTracks.isYouTubeWatchUrl(persistedUrl)) urls[index] = retryUrl
        return toResponse(retry, retryUrl)
    }

    /**
     * The URL the proxy should fetch for this chapter. A YouTube watch URL is
     * resolved through the injected resolver and cached; null is the honest
     * refusal (no fabricated audio, ADR-0019) the receiver sees as a 502.
     */
    private fun playableUrl(index: Int, persistedUrl: String): String? {
        if (!YouTubeTracks.isYouTubeWatchUrl(persistedUrl)) return persistedUrl
        resolvedByChapter[index]?.let { return it }
        val currentResolver = resolver ?: return null
        synchronized(resolutionLock) {
            resolvedByChapter[index]?.let { return it }
            val spent = resolveAttemptsByChapter.getOrDefault(index, 0)
            if (spent >= maxResolveAttempts) return null
            resolveAttemptsByChapter[index] = spent + 1
            val resolved = runCatching {
                runBlocking { currentResolver(persistedUrl) }
            }.getOrNull()
                ?.takeIf { it != persistedUrl && it.startsWith("http", ignoreCase = true) }
                ?: return null
            resolvedByChapter[index] = resolved
            return resolved
        }
    }

    private fun heal(index: Int, persistedUrl: String, failedUrl: String): String? {
        val currentHealer = healer ?: return null
        val owner = bookId ?: return null
        val spent = healAttemptsByChapter.getOrDefault(index, 0)
        if (spent >= StreamHealPolicy.MAX_HEAL_ATTEMPTS) return null
        healAttemptsByChapter[index] = spent + 1
        return runCatching {
            runBlocking { currentHealer(owner, index, failedUrl) }
        }.getOrNull()
            ?.takeIf { it != failedUrl && it.startsWith("http", ignoreCase = true) }
    }

    private fun fetch(
        targetUrl: String,
        persistedUrl: String,
        rangeHeader: String?
    ): HttpFetcher.RangeResponse? {
        val requestHeaders = headers(persistedUrl)
        return fetcher.getRangeStream(
            targetUrl,
            if (rangeHeader != null) requestHeaders + ("Range" to rangeHeader) else requestHeaders
        )
    }

    private fun toResponse(
        response: HttpFetcher.RangeResponse,
        url: String
    ): PlaybackProxy.UpstreamResponse =
        PlaybackProxy.UpstreamResponse(
            status = response.status,
            body = response.stream,
            contentLength = response.contentLength,
            contentRange = response.contentRange,
            // The origin's own type is evidence (googlevideo serves
            // `audio/mp4` from a URL with no extension); guessing stays the
            // fallback, never the override.
            contentType = response.contentType ?: CastMimeTypes.forCast(url)
        )
}
