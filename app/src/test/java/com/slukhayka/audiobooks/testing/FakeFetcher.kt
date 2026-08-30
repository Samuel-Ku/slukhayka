package com.slukhayka.audiobooks.testing

import com.slukhayka.audiobooks.data.source.HttpFetcher
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Serves canned content per URL for source-adapter fixture tests — no network,
 * no Robolectric (parser seam). Unknown URLs fall back to [fallback] (empty by
 * default) so URL-encoded request URLs can be matched without exact keys.
 *
 * [recordedHeaders] captures the extra headers of every headerful request, so
 * tests can assert that a header-gated endpoint (sluhayua's X-Requested-With)
 * is actually sent the gate it needs.
 *
 * ADR-0006: [streamResponses] serves in-memory bytes for the binary
 * [HttpFetcher.getStream] transport — the offline download path consumes it,
 * so download-path tests exercise the real loop with no network.
 */
class FakeFetcher(
    private val responses: Map<String, String> = emptyMap(),
    private val fallback: String = "",
    private val streamResponses: Map<String, ByteArray> = emptyMap(),
    private val sizedStreamResponses: Map<String, Pair<ByteArray, Long?>> = emptyMap(),
    private val delayMs: Long = 0L
) : HttpFetcher() {

    /** Extra headers of each headerful request, in call order. Thread-safe:
     *  the offline download loop records from several async workers at once,
     *  and a plain mutable list would lose appends under that race. */
    val recordedHeaders = java.util.concurrent.CopyOnWriteArrayList<Map<String, String>>()

    /** Concurrency observability for spec-37 T1: counts simultaneously open
     *  streams. `maxConcurrentStreams` is the high-water mark observed. */
    private val concurrentStreams = java.util.concurrent.atomic.AtomicInteger(0)
    val maxConcurrentStreams: Int get() = _maxConcurrentStreams.get()
    private val _maxConcurrentStreams = java.util.concurrent.atomic.AtomicInteger(0)

    private fun trackConcurrency(): Int {
        val cur = concurrentStreams.incrementAndGet()
        _maxConcurrentStreams.updateAndGet { max -> maxOf(max, cur) }
        return cur
    }

    private fun untrackConcurrency() {
        concurrentStreams.decrementAndGet()
    }

    private fun wrappingStream(bytes: ByteArray): InputStream {
        trackConcurrency()
        return object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (delayMs > 0) {
                    try { Thread.sleep(delayMs) } catch (_: InterruptedException) {}
                }
                return super.read(b, off, len)
            }

            override fun read(): Int {
                if (delayMs > 0) {
                    try { Thread.sleep(delayMs) } catch (_: InterruptedException) {}
                }
                return super.read()
            }

            override fun close() {
                super.close()
                untrackConcurrency()
            }
        }
    }

    override fun getText(url: String): String = responses[url] ?: fallback

    override fun getText(url: String, extraHeaders: Map<String, String>): String {
        recordedHeaders += extraHeaders
        return responses[url] ?: fallback
    }

    override fun getStream(url: String, extraHeaders: Map<String, String>): InputStream? {
        recordedHeaders += extraHeaders
        // Prefer sized responses for getStream too, to keep one source of truth.
        sizedStreamResponses[url]?.let { (bytes, _) -> return wrappingStream(bytes) }
        return streamResponses[url]?.let { wrappingStream(it) }
    }

    override fun getSizedStream(url: String, extraHeaders: Map<String, String>): SizedStream? {
        recordedHeaders += extraHeaders
        sizedStreamResponses[url]?.let { (bytes, declaredLength) ->
            val stream = wrappingStream(bytes)
            // If declaredLength is null, treat as unknown (fallback to minimal-size check).
            return SizedStream(stream, declaredLength)
        }
        streamResponses[url]?.let { bytes ->
            val stream = wrappingStream(bytes)
            return SizedStream(stream, bytes.size.toLong())
        }
        return null
    }

    override fun getSizedStreamResult(url: String, extraHeaders: Map<String, String>): SizedStreamResult {
        val stream = getSizedStream(url, extraHeaders)
        return SizedStreamResult(if (stream != null) 200 else 0, stream)
    }
}
