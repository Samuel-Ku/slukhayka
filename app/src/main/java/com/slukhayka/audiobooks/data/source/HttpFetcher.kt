package com.slukhayka.audiobooks.data.source

import android.util.Log
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal JVM HTTP GET used by [SourceAdapter]s. Configurable [referer] so the
 * audiobookmp3 playlist CDN (which 403s without one) can be fetched, and
 * per-request [extraHeaders] for endpoints gated on headers like
 * `X-Requested-With` (sluhay.com.ua — verified in the spec-11 T1 spike).
 * Returns an empty string on any failure — adapters degrade to no results,
 * never throw.
 */
open class HttpFetcher(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val referer: String? = null
) {

    /** Open so adapter fixture tests can serve canned content without network. */
    open fun getText(url: String): String = getTextResult(url, emptyMap()).second

    /**
     * Like [getText] with additional request headers (e.g. the sluhayua
     * `X-Requested-With: XMLHttpRequest` gate). Open so fixture fakes can serve
     * canned content by URL, ignoring headers.
     */
    open fun getText(url: String, extraHeaders: Map<String, String>): String =
        getTextResult(url, extraHeaders).second

    /**
     * The HTTP status + body of one GET — the status-aware variant of
     * [getText] (spec-26 T3: the Wikidata 429 retry needs the status code).
     * The body is "" on any non-200 status or failure; the status is 0 when
     * the request itself failed before a response code existed. Open so
     * fixture fakes can serve canned status+content by URL.
     */
    open fun getTextResult(url: String): Pair<Int, String> = getTextResult(url, emptyMap())

    /** Like [getTextResult] with additional request headers. */
    open fun getTextResult(url: String, extraHeaders: Map<String, String>): Pair<Int, String> {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 18_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                if (referer != null) setRequestProperty("Referer", referer)
                extraHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
                instanceFollowRedirects = true
            }
        } catch (e: Exception) {
            // Failures degrade to an empty body by design (adapters never throw),
            // but they MUST be logged: the catalogue silently going empty on
            // device-side DNS failures (e.g. a VPN with a broken resolver) was
            // undiagnosable before (device debugging, 2026-08-17).
            Log.w("HttpFetcher", "GET $url failed to connect", e)
            return 0 to ""
        }
        return try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_OK) {
                status to connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                status to ""
            }
        } catch (e: Exception) {
            Log.w("HttpFetcher", "GET $url failed (status/read)", e)
            0 to ""
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Spec-37 T1 — sized binary GET: the download path's verified transport.
     * Like [getStream] but the response's `Content-Length` travels alongside the
     * stream so the caller can honestly reject a short body. Null when the
     * server omits the header (the caller then falls back to the existing
     * minimal-size threshold). Never throws. Open so fixture fakes can serve
     * in-memory bytes with a canned length.
     */
    data class SizedStream(val stream: InputStream, val contentLength: Long?)

    open fun getSizedStream(url: String, extraHeaders: Map<String, String> = emptyMap()): SizedStream? {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 18_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                if (referer != null) setRequestProperty("Referer", referer)
                extraHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
                instanceFollowRedirects = true
            }
        } catch (e: Exception) {
            return null
        }
        return try {
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val length = connection.getHeaderFieldLong("Content-Length", -1)
                    .let { if (it >= 0) it else null }
                val wrapped = object : FilterInputStream(connection.inputStream) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            connection.disconnect()
                        }
                    }
                }
                SizedStream(wrapped, length)
            } else {
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            connection.disconnect()
            null
        }
    }

    /**
     * ADR-0006 — binary GET: the ONE transport the offline download path
     * uses. Returns null on any failure — the same degrade-never-throw
     * convention as [getText]. The caller owns reading and must close the
     * stream: closing it disconnects the underlying connection (the returned
     * stream is a [FilterInputStream] over the connection's input). Never
     * throws. Open so fixture fakes can serve in-memory bytes.
     */
    open fun getStream(url: String, extraHeaders: Map<String, String> = emptyMap()): InputStream? {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 18_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                if (referer != null) setRequestProperty("Referer", referer)
                extraHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
                instanceFollowRedirects = true
            }
        } catch (e: Exception) {
            return null
        }
        return try {
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                object : FilterInputStream(connection.inputStream) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            connection.disconnect()
                        }
                    }
                }
            } else {
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            connection.disconnect()
            null
        }
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile; SM-S918B) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
