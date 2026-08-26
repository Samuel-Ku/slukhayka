package com.slukhayka.audiobooks.data.source

import android.util.Log
import com.slukhayka.audiobooks.data.privacy.BrowserIdentity
import com.slukhayka.audiobooks.data.privacy.TransportClients
import com.slukhayka.audiobooks.data.privacy.TransportPrivacy
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Minimal JVM HTTP GET used by [SourceAdapter]s. Configurable [referer] so the
 * audiobookmp3 playlist CDN (which 403s without one) can be fetched, and
 * per-request [extraHeaders] for endpoints gated on headers like
 * `X-Requested-With` (sluhay.com.ua — verified in the spec-11 T1 spike).
 * Returns an empty string on any failure — adapters degrade to no results,
 * never throw.
 *
 * Spec-38 (#252): every request looks like ordinary mobile browsing — the UA
 * is the device's real system WebView one ([BrowserIdentity], cached once per
 * process; static fallback in JVM), standard Accept/Accept-Language travel by
 * default, and when the listener chose a privacy route the connection opens
 * through it ([TransportPrivacy]) — wire-level proxy, or URL rewrite for the
 * relay prototype. A chosen route that cannot connect fails the request —
 * there is NO silent fallback to a direct connection.
 *
 * Spec-38 T4 (#256): the engine is the shared OkHttp client
 * ([TransportClients.okHttp]) — one pool, one identity, one route trampoline,
 * and domain names resolved through the DoH door ([TransportDns], encrypted
 * by default with a transparent system-resolver fallback); HttpURLConnection
 * has no seam for a custom resolver, and audit PERF-013 already pointed here.
 * The observable contract is unchanged: same budgets (12 s connect / 18 s
 * read), same headers and override order, redirects followed, `status to
 * body` semantics, streams that disconnect-on-close. Per-source [referer]
 * rules (spec-13) are unaffected. Explicit [userAgent] overrides exist only
 * for tests; production callers leave it null.
 */
open class HttpFetcher(
    private val userAgent: String? = null,
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
        val viaPrivacyRoute = TransportPrivacy.currentJavaProxy() != null ||
            TransportPrivacy.isRelayActive()
        return try {
            TransportClients.okHttp.newCall(buildRequest(url, extraHeaders)).execute()
                .use { response ->
                    val status = response.code
                    if (status == HTTP_OK) status to response.body.stringOrEmpty()
                    else status to ""
                }
        } catch (e: Exception) {
            // Failures degrade to an empty body by design (adapters never throw),
            // but they MUST be logged: the catalogue silently going empty on
            // device-side DNS failures was undiagnosable before (device
            // debugging, 2026-08-17). With a privacy route enabled this is ALSO
            // where its honest failure lands: the request failed THROUGH the
            // chosen route — no direct retry.
            Log.w(
                "HttpFetcher",
                "GET $url failed" +
                    if (viaPrivacyRoute) " (privacy route active — no direct fallback)" else "",
                e
            )
            0 to ""
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
        val response = execute(url, extraHeaders) ?: return null
        return try {
            if (response.code == HTTP_OK) {
                val length = response.header("Content-Length")
                    ?.substringBefore(';')?.trim()?.toLongOrNull()
                    ?.takeIf { it >= 0 }
                SizedStream(ownedStream(response), length)
            } else {
                response.close()
                null
            }
        } catch (e: Exception) {
            response.close()
            null
        }
    }

    /**
     * ADR-0006 — binary GET: the ONE transport the offline download path
     * uses. Returns null on any failure — the same degrade-never-throw
     * convention as [getText]. The caller owns reading and must close the
     * stream: closing it closes the underlying response (the returned stream
     * owns the response; the pooled connection is released with it). Never
     * throws. Open so fixture fakes can serve in-memory bytes.
     */
    open fun getStream(url: String, extraHeaders: Map<String, String> = emptyMap()): InputStream? {
        val response = execute(url, extraHeaders) ?: return null
        return try {
            if (response.code == HTTP_OK) ownedStream(response)
            else {
                response.close()
                null
            }
        } catch (e: Exception) {
            response.close()
            null
        }
    }

    /**
     * The single place a request takes shape: the URL goes through the relay
     * seam first (spec-38 T6 — untouched unless the relay route is resolved),
     * then the browser identity headers, the per-source Referer, and finally
     * the caller's own headers (so they can override defaults). Route, DNS
     * and timeouts live on the shared client.
     */
    private fun buildRequest(url: String, extraHeaders: Map<String, String>): Request {
        val target = TransportPrivacy.rewriteThroughRelay(url)
        return Request.Builder()
            .url(target)
            .get()
            .header("User-Agent", userAgent ?: BrowserIdentity.currentUserAgent())
            .header("Accept", BrowserIdentity.ACCEPT_HEADER)
            .header("Accept-Language", BrowserIdentity.ACCEPT_LANGUAGE_HEADER)
            .apply { if (referer != null) header("Referer", referer!!) }
            .apply { extraHeaders.forEach { (name, value) -> header(name, value) } }
            .build()
    }

    /**
     * ADR-0024 (#361/#362) — ranged binary GET for the Playback Proxy: the
     * receiver seeks by issuing HTTP Range requests, and the proxy forwards
     * them upstream through this ONE transport (per-source Referer, privacy
     * route, DoH — the same [buildRequest] as every other call). Unlike
     * [getStream]/[getSizedStream] it accepts BOTH 200 (full body, no range
     * honoured) and 206 (partial content) — the status travels with the
     * stream so the proxy can mirror 206 semantics honestly. Returns null on
     * any failure; caller owns reading and closing.
     */
    open fun getRangeStream(url: String, extraHeaders: Map<String, String> = emptyMap()): RangeResponse? {
        val response = execute(url, extraHeaders) ?: return null
        return try {
            if (response.code == HTTP_OK || response.code == HTTP_PARTIAL) {
                RangeResponse(
                    stream = ownedStream(response),
                    status = response.code,
                    contentLength = response.body?.contentLength()?.takeIf { it >= 0 },
                    contentRange = response.header("Content-Range")
                )
            } else {
                response.close()
                null
            }
        } catch (e: Exception) {
            runCatching { response.close() }
            null
        }
    }

    /** One network attempt; any failure degrades to null (never throws). */
    private fun execute(url: String, extraHeaders: Map<String, String>): Response? = try {
        TransportClients.okHttp.newCall(buildRequest(url, extraHeaders)).execute()
    } catch (e: Exception) {
        val viaPrivacyRoute = TransportPrivacy.currentJavaProxy() != null ||
            TransportPrivacy.isRelayActive()
        Log.w(
            "HttpFetcher",
            "GET $url failed to connect" +
                if (viaPrivacyRoute) " (privacy route active — no direct fallback)" else "",
            e
        )
        null
    }

    /** A stream that closes the whole [Response] with it (pool release). */
    private fun ownedStream(response: Response): InputStream =
        object : FilterInputStream(response.body.requireStream()) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    response.close()
                }
            }
        }

    private fun ResponseBody?.stringOrEmpty(): String = this?.string() ?: ""

    private fun ResponseBody?.requireStream(): InputStream =
        this?.byteStream() ?: throw IllegalStateException("empty body")

    private companion object {
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL = 206
    }

    /**
     * ADR-0024 — the ranged variant of a binary response: [status] is 200
     * (whole body) or 206 (a slice), [contentRange] carries the upstream's
     * own `bytes s-e/total` header verbatim when it sent one.
     */
    class RangeResponse(
        val stream: InputStream,
        val status: Int,
        val contentLength: Long?,
        val contentRange: String?
    )
}
