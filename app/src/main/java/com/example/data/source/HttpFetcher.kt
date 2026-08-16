package com.example.data.source

import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The outcome of an HTTP GET: the status code and the response body ("" for
 * non-200 responses and transport failures, whose status is 0). Carries the
 * status so callers can distinguish a rate-limit (429) — which retrying may
 * cure — from any other failure (spec-26 T3).
 */
data class FetchResult(val status: Int, val body: String)

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
    open fun getText(url: String): String = getText(url, emptyMap())

    /**
     * Like [getText] with additional request headers (e.g. the sluhayua
     * `X-Requested-With: XMLHttpRequest` gate). Open so fixture fakes can serve
     * canned content by URL, ignoring headers.
     */
    open fun getText(url: String, extraHeaders: Map<String, String>): String =
        getResult(url, extraHeaders).body

    /**
     * The status-aware GET — [getText] plus the response code (0 on transport
     * failure), so a caller can retry specifically on 429 (spec-26 T3). Same
     * degrade-never-throw convention: body is "" on any non-200. Open so
     * fixture fakes can serve canned (status, body) pairs by URL.
     */
    open fun getResult(url: String, extraHeaders: Map<String, String> = emptyMap()): FetchResult {
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
            return FetchResult(0, "")
        }
        return try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                FetchResult(code, connection.inputStream.bufferedReader().use { it.readText() })
            } else {
                FetchResult(code, "")
            }
        } catch (e: Exception) {
            FetchResult(0, "")
        } finally {
            connection.disconnect()
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
