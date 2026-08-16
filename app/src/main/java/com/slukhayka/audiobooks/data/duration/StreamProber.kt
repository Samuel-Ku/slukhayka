package com.slukhayka.audiobooks.data.duration

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
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
 */
interface StreamProber {
    /** @return the stream's content length and CBR frame header, or null. */
    suspend fun probe(url: String): ProbeResult?
}

/** The facts one successful probe yields: size × 8 / bitrate = seconds. */
data class ProbeResult(
    val contentLength: Long,
    val frame: MpegAudioFrame.Header
)

/**
 * Real HEAD + ranged-GET prober over [HttpURLConnection], same degrade-never-
 * throw convention as the adapter transport ([com.slukhayka.audiobooks.data.source.HttpFetcher]).
 * The window is capped at [DEFAULT_FETCH_WINDOW_BYTES] — only the head of the
 * stream is ever read, never the whole file.
 */
class HttpStreamProber(
    private val windowBytes: Int = DEFAULT_FETCH_WINDOW_BYTES
) : StreamProber {

    override suspend fun probe(url: String): ProbeResult? = withContext(Dispatchers.IO) {
        val length = headLength(url) ?: return@withContext null
        if (length <= 0L) return@withContext null
        val window = rangeGet(url, 0L, minOf(length - 1, windowBytes - 1L)) ?: return@withContext null
        val frame = MpegAudioFrame.parse(window) ?: return@withContext null
        ProbeResult(length, frame)
    }

    private fun headLength(url: String): Long? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "HEAD"
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.contentLengthLong.takeIf { it > 0L }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    private fun rangeGet(url: String, from: Long, to: Long): ByteArray? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=$from-$to")
            instanceFollowRedirects = true
        }
        try {
            // 206 = the server honoured the Range; 200 = it ignored it (we
            // still only read the head window before disconnecting).
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_PARTIAL || code == HttpURLConnection.HTTP_OK) {
                readAtMost(connection.inputStream, windowBytes)
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    private fun readAtMost(input: InputStream, maxBytes: Int): ByteArray? {
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
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 18_000
    }
}
