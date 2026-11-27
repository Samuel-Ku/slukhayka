package com.slukhayka.audiobooks.player

import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.ResolvedAudioStream
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
 * Resolution returns the URL TOGETHER WITH the MIME family of its bytes
 * ([ResolvedAudioStream]): the playlist declares a MIME to the receiver
 * before the first range request ([CastMimeTypes.forCast]), so a stream of
 * another family is refused here instead of being served under a false label
 * (the listener's honest cast error, never a silent wrong format).
 *
 * The signed audio URL is cached for the session, because the proxy gets many
 * range requests per chapter. When it expires, the failed fetch invalidates
 * the cache — but only the entry it actually saw fail: a concurrent range
 * request may already have refreshed it, and clearing that fresh URL would
 * spend the second (last) resolve attempt for nothing, leaving later range
 * requests with no URL although a usable one was cached.
 * Plain (non-YouTube) URLs pass through untouched and keep their existing
 * page-heal door ([healer]).
 */
internal class CastUpstream(
    chapterUrls: List<String?>,
    private val bookId: String?,
    private val fetcher: HttpFetcher,
    private val resolver: (suspend (String) -> ResolvedAudioStream?)? = null,
    private val healer: (suspend (String, Int, String) -> String?)? = null,
    /** Initial resolution plus one stale-URL re-resolution. */
    private val maxResolveAttempts: Int = StreamHealPolicy.MAX_HEAL_ATTEMPTS + 1,
    private val headers: (String) -> Map<String, String> = { url ->
        headersFor(sourceIdForUrl(url), url)
    }
) {

    /** A URL the proxy may fetch, plus the family of the bytes it serves. */
    private data class Playable(val url: String, val mimeType: String?)

    /** Healed plain URLs live for the session; the persisted list is the identity. */
    private val urls = CopyOnWriteArrayList(chapterUrls)

    private val resolvedByChapter = ConcurrentHashMap<Int, Playable>()
    private val resolveAttemptsByChapter = ConcurrentHashMap<Int, Int>()
    private val healAttemptsByChapter = ConcurrentHashMap<Int, Int>()
    private val resolutionLock = Any()

    /** The [PlaybackProxy.Upstream] entry point. */
    fun open(subPath: String?, rangeHeader: String?): PlaybackProxy.UpstreamResponse? {
        val index = subPath?.removePrefix("ch")?.toIntOrNull() ?: return null
        val persistedUrl = urls.getOrNull(index) ?: return null
        val first = playableUrl(index, persistedUrl) ?: return null
        val firstResponse = fetch(first, persistedUrl, rangeHeader)
        if (firstResponse != null) return toResponse(firstResponse, first)

        // The resolved URL went stale (signed URLs expire) or a plain source
        // moved. Spend the ONE retry: re-resolve or re-fetch the page.
        val retry = if (YouTubeTracks.isYouTubeWatchUrl(persistedUrl)) {
            // Conditional reset: only THIS chapter's entry, and only while it
            // is still the stale stream this request fetched. A concurrent
            // range request may have refreshed the cache to a new URL in the
            // meantime; deleting that fresh entry would burn the last resolve
            // attempt and leave every later request with null (#1751 review).
            resolvedByChapter.remove(index, first)
            playableUrl(index, persistedUrl)
        } else {
            heal(index, persistedUrl, first.url)?.let { Playable(it, null) }
        } ?: return null
        val retryResponse = fetch(retry, persistedUrl, rangeHeader) ?: return null
        if (!YouTubeTracks.isYouTubeWatchUrl(persistedUrl)) urls[index] = retry.url
        return toResponse(retryResponse, retry)
    }

    /**
     * The stream the proxy should fetch for this chapter. A YouTube watch URL
     * is resolved through the injected resolver and cached; null is the
     * honest refusal (no fabricated audio, ADR-0019) the receiver sees as a
     * 502 — including a resolved stream whose family is not the one the cast
     * playlist already declared.
     */
    private fun playableUrl(index: Int, persistedUrl: String): Playable? {
        if (!YouTubeTracks.isYouTubeWatchUrl(persistedUrl)) return Playable(persistedUrl, null)
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
                ?.takeIf { it.url != persistedUrl && it.url.startsWith("http", ignoreCase = true) }
                ?: return null
            // #1751 — the playlist told the receiver this chapter is
            // CastMimeTypes.forCast(watch URL). A stream of another family
            // (WebM/Opus) would make that declaration a lie, so the chapter is
            // refused honestly rather than mislabelled.
            if (!CastMimeTypes.declaresFamily(CastMimeTypes.forCast(persistedUrl), resolved.mimeType)) {
                return null
            }
            val playable = Playable(resolved.url, resolved.mimeType)
            resolvedByChapter[index] = playable
            return playable
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
        playable: Playable,
        persistedUrl: String,
        rangeHeader: String?
    ): HttpFetcher.RangeResponse? {
        val requestHeaders = headers(persistedUrl)
        return fetcher.getRangeStream(
            playable.url,
            if (rangeHeader != null) requestHeaders + ("Range" to rangeHeader) else requestHeaders
        )
    }

    private fun toResponse(
        response: HttpFetcher.RangeResponse,
        playable: Playable
    ): PlaybackProxy.UpstreamResponse =
        PlaybackProxy.UpstreamResponse(
            status = response.status,
            body = response.stream,
            contentLength = response.contentLength,
            contentRange = response.contentRange,
            // The origin's own type is evidence (googlevideo serves
            // `audio/mp4` from a URL with no extension); the resolved
            // stream's real family comes next, guessing stays the fallback —
            // never the override.
            contentType = response.contentType
                ?: playable.mimeType
                ?: CastMimeTypes.forCast(playable.url)
        )
}
