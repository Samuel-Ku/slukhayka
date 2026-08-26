package com.slukhayka.audiobooks.player

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.IStatus
import fi.iki.elonen.NanoHTTPD.Response.Status.FORBIDDEN
import fi.iki.elonen.NanoHTTPD.Response.Status.INTERNAL_ERROR
import fi.iki.elonen.NanoHTTPD.Response.Status.OK
import fi.iki.elonen.NanoHTTPD.Response.Status.PARTIAL_CONTENT
import fi.iki.elonen.NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #361 «Проксі відтворення» — the playback proxy: a tiny embedded HTTP server
 * on the phone that a Cast receiver will point at (`http://<phone>:<port>/<token>/...`)
 * while the proxy alone fetches the upstream bytes through the app's shared
 * transport. This ticket ships ONLY the component: the receiver wiring, Cast
 * dependencies and wake locks live in later tickets.
 *
 * The seam is deliberately shaped around what the shared transport can
 * honestly provide. [HttpFetcher.getStream] answers only HTTP 200 and hides
 * status/headers, which is exactly what byte-range playback cannot live on —
 * so the proxy defines its own narrow [Upstream] door instead of reusing it:
 * the caller hands over a stream together with its status, length and
 * Content-Range, and returns null whenever upstream has nothing honest to
 * serve (the same degrade-never-throw convention HttpFetcher uses — the
 * adapter never throws, it declines). Production wiring adapts HttpFetcher
 * onto this seam; tests inject in-memory fakes.
 *
 * Honesty rules the responses: upstream bytes are relayed verbatim (no
 * buffering into fabricated bodies), a failed upstream becomes an empty 502,
 * an unsatisfiable range becomes a bare 416, and a wrong token never even
 * reaches the upstream door — 403 before anything else. The proxy closes
 * every upstream stream it opens ([SelfClosingStream]).
 */
class PlaybackProxy(
    /**
     * The only way bytes enter the proxy. Called once per client request with
     * the verbatim Range header (`"bytes=a-b"`), or null when the client sent
     * none; returns null when upstream has nothing honest to serve.
     */
    private val upstream: Upstream,

    /**
     * The address the embedded server binds. Loopback by default so JVM
     * end-to-end tests need no network; production wiring passes the Wi-Fi
     * address (see [wifiBindAddress]) so the receiver on the same LAN can
     * reach it.
     */
    private val bindAddress: InetAddress = InetAddress.getLoopbackAddress(),

    /**
     * Fallback Content-Type used when the upstream response carries none;
     * null (the default) means the header is omitted entirely rather than
     * fabricated.
     */
    private val contentType: String? = null
) {

    /**
     * One upstream attempt: status is 200 (full body) or 206 (served slice).
     * [subPath] is the request path after the token prefix (e.g. `"ch3"`) —
     * the caller's resource identity, opaque to the proxy itself.
     */
    fun interface Upstream {
        fun open(subPath: String?, rangeHeader: String?): UpstreamResponse?
    }

    /**
     * What upstream answered. [body] holds exactly the bytes the status
     * describes (whole file for 200, the requested slice for 206);
     * [contentLength] is that body's length when known, [contentRange] the
     * verbatim `Content-Range` header for a 206.
     */
    data class UpstreamResponse(
        val status: Int,
        val body: InputStream,
        val contentLength: Long?,
        val contentRange: String?,
        val contentType: String?
    )

    /** Where the running server lives: the actual (ephemeral) port and the token-guarded prefix. */
    data class Started(val port: Int, val pathPrefix: String)

    @Volatile
    private var server: Server? = null

    @Volatile
    private var startedInfo: Started? = null

    @Volatile
    private var pathPrefix: String? = null

    /** Lifecycle probe for callers and tests: a server between successful [start] and [stop]. */
    val isRunning: Boolean
        get() = server?.alive == true

    /**
     * Binds the embedded server on an ephemeral port and generates the fresh
     * random token guarding every URL. Returns the resulting [Started], or
     * null honestly when the server cannot start (unusable bind address,
     * socket exhaustion) — never throws. Starting an already-running proxy
     * returns its current snapshot instead of silently rebinding.
     */
    @Synchronized
    fun start(): Started? {
        server?.let { return startedInfo }
        val prefix = "/" + UUID.randomUUID().toString()
        val fresh = Server()
        return try {
            fresh.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            fresh.alive = true
            pathPrefix = prefix
            val info = Started(port = fresh.listeningPort, pathPrefix = prefix)
            server = fresh
            startedInfo = info
            info
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    /**
     * Stops the listener (open client connections are cut by their own
     * sockets) and forgets the lifecycle state. Nothing else is closed here —
     * upstream streams are owned by [SelfClosingStream].
     */
    @Synchronized
    fun stop() {
        server?.apply {
            alive = false
            stop()
        }
        server = null
        startedInfo = null
    }

    /**
     * Scans the network interfaces for a non-loopback site-local IPv4 address
     * (the wlan-shaped address production wiring will pass as [bindAddress]).
     * Null when none exists — the caller degrades to no receiver exposure,
     * never throws.
     */
    fun wifiBindAddress(): InetAddress? = try {
        NetworkInterface.getNetworkInterfaces()?.asIterator()?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.asIterator().asSequence() }
            ?.firstOrNull { address ->
                address is Inet4Address &&
                    !address.isLoopbackAddress &&
                    address.isSiteLocalAddress
            }
    } catch (e: Exception) {
        null
    }

    /**
     * The request pipeline: token gate first (403 before anything moves),
     * then verbatim Range forwarding through [upstream], then an honest
     * mirror of whatever upstream said.
     */
    private inner class Server : NanoHTTPD(bindAddress.hostAddress, EPHEMERAL_PORT) {

        /** Set once the listener bound successfully; cleared by [PlaybackProxy.stop]. */
        @Volatile
        var alive: Boolean = false

        override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response =
            try {
                respond(session.uri, session.headers["range"])
            } catch (e: Exception) {
                emptyResponse(INTERNAL_ERROR)
            }
    }

    private fun respond(uri: String?, rangeHeader: String?): NanoHTTPD.Response {
        val prefix = pathPrefix
        if (prefix == null || uri == null || !uri.startsWith("$prefix/")) {
            return emptyResponse(FORBIDDEN)
        }
        val subPath = uri.removePrefix("$prefix/")
        val range = rangeHeader?.let(::parseByteRange)
        val upstreamResponse =
            upstream.open(subPath, if (range != null) rangeHeader else null)
                ?: return if (range != null) {
                    emptyResponse(RANGE_NOT_SATISFIABLE)
                } else {
                    emptyResponse(BAD_GATEWAY)
                }
        return when (upstreamResponse.status) {
            STATUS_OK -> {
                // Full-length answer: if the requested start sits beyond the now-known
                // length, the range was unsatisfiable however sloppy upstream was.
                val start = range?.first
                val knownLength = upstreamResponse.contentLength
                if (start != null && knownLength != null && start >= knownLength) {
                    upstreamResponse.body.closeQuietly()
                    emptyResponse(RANGE_NOT_SATISFIABLE)
                } else {
                    fullBody(upstreamResponse)
                }
            }
            STATUS_PARTIAL_CONTENT -> partialBody(upstreamResponse)
            else -> {
                // The seam promises 200/206 only; anything else is not honest input.
                upstreamResponse.body.closeQuietly()
                emptyResponse(BAD_GATEWAY)
            }
        }
    }

    /** Mirrors an upstream 200: verbatim body, streamed with its declared length. */
    private fun fullBody(upstreamResponse: UpstreamResponse): NanoHTTPD.Response {
        val mime = upstreamResponse.contentType ?: contentType
        val length = upstreamResponse.contentLength
        val response = if (length != null && length >= 0) {
            NanoHTTPD.newFixedLengthResponse(
                OK, mime, SelfClosingStream(upstreamResponse.body), length
            )
        } else {
            // Length unknown upstream — chunked stays verbatim and unbuffered.
            NanoHTTPD.newChunkedResponse(OK, mime, SelfClosingStream(upstreamResponse.body))
        }
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    /**
     * Mirrors an upstream 206: Content-Range travels unchanged, the response
     * length is the served slice derived from it, Accept-Ranges announces
     * range support to future players.
     */
    private fun partialBody(upstreamResponse: UpstreamResponse): NanoHTTPD.Response {
        val servedLength = servedSliceLength(upstreamResponse)
        if (servedLength == null) {
            // A 206 whose size cannot be stated is not honest output — refuse it.
            upstreamResponse.body.closeQuietly()
            return emptyResponse(BAD_GATEWAY)
        }
        val mime = upstreamResponse.contentType ?: contentType
        val response = NanoHTTPD.newFixedLengthResponse(
            PARTIAL_CONTENT, mime, SelfClosingStream(upstreamResponse.body), servedLength
        )
        upstreamResponse.contentRange?.let { response.addHeader("Content-Range", it) }
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    /** Slice size straight out of `bytes=start-end/total`; falls back to the body's length. */
    private fun servedSliceLength(upstreamResponse: UpstreamResponse): Long? =
        upstreamResponse.contentRange
            ?.let(CONTENT_RANGE_REGEX::matchEntire)
            ?.groupValues
            ?.takeIf { it.size > 2 }
            ?.let { it[2].toLong() - it[1].toLong() + 1 }
            ?.takeIf { it > 0 }
            ?: upstreamResponse.contentLength?.takeIf { it >= 0 }

    /** An empty-bodied response; null mime keeps NanoHTTPD from inventing a Content-Type. */
    private fun emptyResponse(status: IStatus): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, null, "")

    /** `bytes=a-b` / `bytes=a-` (the forms the contract forwards); everything else is ignored. */
    private fun parseByteRange(header: String): Pair<Long, Long?>? =
        BYTE_RANGE_REGEX.matchEntire(header.trim())
            ?.groupValues
            ?.takeIf { it.size > 2 }
            ?.let { it[1].toLong() to it[2].toIntOrNull()?.toLong() }

    private fun InputStream.closeQuietly() {
        try {
            close()
        } catch (e: IOException) {
            // Nothing honest left to do — the connection dies with the socket.
        }
    }

    /**
     * Closes the wrapped upstream stream the moment it is exhausted (EOF) or
     * explicitly closed — whichever lands first. NanoHTTPD drains the body on
     * its worker thread and never calls close itself, so EOF is the reliable
     * signal the upstream transfer is done; this keeps «the proxy closes
     * everything it opens» true without depending on container housekeeping.
     */
    private class SelfClosingStream(private val origin: InputStream) : FilterInputStream(origin) {

        private val released = AtomicBoolean(false)

        private fun releaseOnce() {
            if (released.compareAndSet(false, true)) {
                try {
                    origin.close()
                } catch (e: IOException) {
                    // Degrade quietly — the underlying socket teardown owns this failure.
                }
            }
        }

        override fun read(): Int = super.read().also { if (it < 0) releaseOnce() }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it < 0) releaseOnce() }

        override fun close() {
            try {
                super.close()
            } finally {
                releaseOnce()
            }
        }
    }

    private companion object {
        private const val EPHEMERAL_PORT = 0

        /** The two statuses the [Upstream] seam documents. */
        private const val STATUS_OK = 200
        private const val STATUS_PARTIAL_CONTENT = 206

        /** `bytes=5-9/100` → groups: start, end, total. */
        private val CONTENT_RANGE_REGEX = Regex("""^bytes\s+(\d+)-(\d+)/(.*)$""")

        /** `bytes=5-` / `bytes=5-9`; suffix form `bytes=-500` is outside the contract. */
        private val BYTE_RANGE_REGEX = Regex("""^bytes=(\d+)-(\d*)$""")

        /** 502 has no entry in NanoHTTPD 2.3.1's status enum — spell it out honestly. */
        private val BAD_GATEWAY = object : IStatus {
            override fun getRequestStatus(): Int = 502
            override fun getDescription(): String = "502 Bad Gateway"
        }
    }
}
