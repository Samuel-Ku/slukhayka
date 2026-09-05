package com.slukhayka.audiobooks.data.duration

import com.slukhayka.audiobooks.data.source.HttpFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * spec-24 T8 (#169) — the transport seam of the chapter-duration probe: one
 * HEAD (Content-Length) plus one ranged GET (a window from byte 0) per
 * track, returning the stream's byte size and the CBR frame header parsed
 * from the window. Null on any failure — the pass never aborts on a bad
 * probe.
 *
 * The MPEG parse itself is [MpegAudioFrame] (pure JVM, fixture-tested); this
 * is the only network code in the module. Fixture tests inject a fake.
 *
 * #516 — the probe rides the SAME network policy as playback and downloads:
 * the shared OkHttp stack inside [HttpFetcher] (app DNS / DoH / privacy
 * route / one browser identity) instead of the former raw
 * `HttpURLConnection` (system resolver, no privacy route). The per-source
 * header seam travels with the probe — Referer-gated CDNs (playerjs,
 * reasd audio hosts) were silently unprovable before.
 */
interface StreamProber {
    /** @return the stream's content length and CBR frame header, or null. */
    suspend fun probe(url: String): ProbeResult?

    /**
     * #516 — the source-aware probe: the per-source header seam travels
     * with the request (Referer-gated CDNs are provable again). Legacy
     * implementations ignore the source identity and answer without extra
     * headers.
     */
    suspend fun probe(sourceId: String, url: String): ProbeResult? = probe(url)
}

/** The facts one successful probe yields: size × 8 / bitrate = seconds. */
data class ProbeResult(
    val contentLength: Long,
    val frame: MpegAudioFrame.Header
)

class HttpStreamProber(
    private val windowBytes: Int = DEFAULT_FETCH_WINDOW_BYTES,
    /**
     * #516 — the per-source header seam: (sourceId, streamUrl) → extra
     * request headers (the same [com.slukhayka.audiobooks.data.source.headersFor]
     * policy playback and downloads apply). Legacy callers omit it; then no
     * extra headers travel.
     */
    private val extraHeadersProvider: ((sourceId: String, streamUrl: String) -> Map<String, String>)? = null
) : StreamProber {

    private val fetcher = HttpFetcher()

    override suspend fun probe(url: String): ProbeResult? = probe("unknown", url)

    override suspend fun probe(sourceId: String, url: String): ProbeResult? = withContext(Dispatchers.IO) {
        val extraHeaders = extraHeadersProvider?.invoke(sourceId, url).orEmpty()
        val length = headLength(url, extraHeaders) ?: return@withContext null
        if (length <= 0L) return@withContext null
        val window = rangeGet(url, 0L, minOf(length - 1, windowBytes - 1L), extraHeaders) ?: return@withContext null
        val frame = MpegAudioFrame.parse(window) ?: return@withContext null
        ProbeResult(length, frame)
    }

    private fun headLength(url: String, extraHeaders: Map<String, String>): Long? {
        val length = fetcher.headContentLength(url, extraHeaders)
        return length?.takeIf { it > 0L }
    }

    private fun rangeGet(url: String, from: Long, to: Long, extraHeaders: Map<String, String>): ByteArray? {
        val headers = extraHeaders + mapOf("Range" to "bytes=$from-$to")
        val response = fetcher.getRangeStream(url, headers) ?: return null
        // 206 = the server honoured the Range; 200 = it ignored it (we
        // still only read the head window before disconnecting).
        // Closing the stream closes the underlying response (owned-stream
        // contract) — the connection returns to the pool either way.
        return response.stream.use { readAtMost(it, windowBytes) }
    }

    private fun readAtMost(input: java.io.InputStream, maxBytes: Int): ByteArray? {
        val buffer = ByteArray(maxBytes)
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return buffer.copyOf(read)
    }

    companion object {
        /** How much of the stream head is ever fetched (typical ID3v2 + 2 frames). */
        const val DEFAULT_FETCH_WINDOW_BYTES = 128 * 1024
    }
}
