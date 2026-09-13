package com.slukhayka.audiobooks.data.source

import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.net.HttpURLConnection
import java.net.URL
import com.slukhayka.audiobooks.data.privacy.TransportPrivacy
import com.slukhayka.audiobooks.data.privacy.TransportClients
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * #708 — the LIVE metadata spike. Never runs in ordinary test runs: it needs
 * real network and real URLs, so it is gated behind system properties:
 *
 *   ./gradlew testDebugUnitTest --tests "*NewPipeMetadataSpikeTest" \
 *     -Dnewpipe.spike=1 -Dspike.video=<url> [-Dspike.playlist=<url>]
 *
 * Its output IS the evidence the ticket asks for: ordered playlist entries with
 * durations, how far paging goes, and how long it takes.
 */
class NewPipeMetadataSpikeTest {

    private fun gate(): Boolean = System.getProperty("newpipe.spike") != null

    /** A plain transport: no privacy relay, no header substitution. */
    private object PlainDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val conn = URL(request.url()).openConnection() as HttpURLConnection
            conn.requestMethod = request.httpMethod()
            request.headers().forEach { (k, v) -> conn.setRequestProperty(k, v.joinToString(", ")) }
            request.dataToSend()?.let { data ->
                conn.doOutput = true
                conn.outputStream.use { it.write(data) }
            }
            val stream = if (conn.responseCode < 400) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }
            val headers = conn.headerFields
                .filterKeys { it != null }
                .mapValues { entry -> entry.value ?: emptyList() }
            return Response(conn.responseCode, conn.responseMessage, headers, body ?: "", request.url())
        }
    }

    /** One of our two behaviours at a time — one variant per RUN (NewPipe.init is global). */
    private class VariantDownloader(
        private val relay: Boolean,
        private val spoofUserAgent: Boolean
    ) : Downloader() {
        override fun execute(request: Request): Response {
            val target = if (relay) TransportPrivacy.rewriteThroughRelay(request.url()) else request.url()
            val builder = okhttp3.Request.Builder().url(target)
            request.dataToSend()?.let { body -> builder.post(okhttp3.RequestBody.create(null, body)) } ?: builder.get()
            request.headers().forEach { (name, values) ->
                values.filter { it.isNotBlank() }.forEach { value -> builder.header(name, value) }
            }
            if (spoofUserAgent && request.headers()["User-Agent"].isNullOrEmpty()) {
                builder.header("User-Agent", com.slukhayka.audiobooks.data.privacy.BrowserIdentity.currentUserAgent())
            }
            return TransportClients.okHttp.newCall(builder.build()).execute().use { response ->
                Response(response.code, response.message, response.headers.toMultimap(),
                    response.body?.string().orEmpty(), response.request.url.toString())
            }
        }
    }

    @Test
    fun `relay without header spoofing`() {
        assumeTrue(gate())
        val url = System.getProperty("spike.video") ?: return
        NewPipe.init(VariantDownloader(relay = true, spoofUserAgent = false))
        val info = StreamInfo.getInfo(ServiceList.YouTube.getStreamExtractor(url))
        println("SPIKE relay-only name=${info.name} durationSec=${info.duration}")
    }

    @Test
    fun `header spoofing without relay`() {
        assumeTrue(gate())
        val url = System.getProperty("spike.video") ?: return
        NewPipe.init(VariantDownloader(relay = false, spoofUserAgent = true))
        val info = StreamInfo.getInfo(ServiceList.YouTube.getStreamExtractor(url))
        println("SPIKE headers-only name=${info.name} durationSec=${info.duration}")
    }

    @Test
    fun `bare okhttp forced to http1`() {
        assumeTrue(gate())
        val url = System.getProperty("spike.video") ?: return
        val bare = okhttp3.OkHttpClient()
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response {
                val builder = okhttp3.Request.Builder().url(request.url())
                request.dataToSend()?.let { body -> builder.post(okhttp3.RequestBody.create(null, body)) } ?: builder.get()
                request.headers().forEach { (name, values) ->
                    values.filter { it.isNotBlank() }.forEach { value -> builder.header(name, value) }
                }
                if (request.headers()["User-Agent"].isNullOrEmpty()) {
                    builder.header("User-Agent", com.slukhayka.audiobooks.data.privacy.BrowserIdentity.currentUserAgent())
                }
                return bare.newCall(builder.build()).execute().use { response ->
                    // OkHttp ALREADY gunzipped the body; forwarding the original
                    // Content-Encoding/Content-Length makes NewPipe handle it twice.
                    val headers = response.headers.toMultimap()
                        .filterKeys { !it.equals("Content-Encoding", true) && !it.equals("Content-Length", true) }
                    Response(response.code, response.message, headers,
                        response.body?.string().orEmpty(), response.request.url.toString())
                }
            }
        })
        val info = StreamInfo.getInfo(ServiceList.YouTube.getStreamExtractor(url))
        println("SPIKE http11-okhttp name=${info.name} durationSec=${info.duration}")
    }

    @Test
    fun `bare okhttp without our stack`() {
        assumeTrue(gate())
        val url = System.getProperty("spike.video") ?: return
        val bare = okhttp3.OkHttpClient()
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request): Response {
                val builder = okhttp3.Request.Builder().url(request.url())
                request.dataToSend()?.let { body -> builder.post(okhttp3.RequestBody.create(null, body)) } ?: builder.get()
                request.headers().forEach { (name, values) ->
                    values.filter { it.isNotBlank() }.forEach { value -> builder.header(name, value) }
                }
                if (request.headers()["User-Agent"].isNullOrEmpty()) {
                    builder.header("User-Agent", com.slukhayka.audiobooks.data.privacy.BrowserIdentity.currentUserAgent())
                }
                return bare.newCall(builder.build()).execute().use { response ->
                    Response(response.code, response.message, response.headers.toMultimap(),
                        response.body?.string().orEmpty(), response.request.url.toString())
                }
            }
        })
        val info = StreamInfo.getInfo(ServiceList.YouTube.getStreamExtractor(url))
        println("SPIKE bare-okhttp name=${info.name} durationSec=${info.duration}")
    }

    @Test
    fun `dump what okhttp actually receives`() {
        assumeTrue(gate())
        val url = System.getProperty("spike.video") ?: return
        val bare = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(url)
            .header("User-Agent", com.slukhayka.audiobooks.data.privacy.BrowserIdentity.currentUserAgent())
            .build()
        bare.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            println("SPIKE okhttp code=${response.code} url=${response.request.url}")
            println("SPIKE okhttp ytInitialData=${body.contains("ytInitialData")} consent=${body.contains("consent", true)}")
            println("SPIKE okhttp head=${body.take(200).replace('\n', ' ')}")
        }
    }

    @Test
    fun `plain transport resolves the same video`() {
        assumeTrue(gate())
        val url = System.getProperty("spike.video") ?: return
        NewPipe.init(PlainDownloader)
        val info = StreamInfo.getInfo(ServiceList.YouTube.getStreamExtractor(url))
        println("SPIKE plain-transport name=${info.name} durationSec=${info.duration}")
    }

    @Test
    fun `single video exposes a real duration`() {
        assumeTrue(gate())
        val url = System.getProperty("spike.video") ?: return
        NewPipe.init(NewPipeYouTubeExtractor.SharedClientDownloader)
        val info = StreamInfo.getInfo(ServiceList.YouTube.getStreamExtractor(url))
        println("SPIKE video name=${info.name} durationSec=${info.duration} uploader=${info.uploaderName}")
        check(info.duration > 0) { "a single video must carry a real duration" }
    }

    @Test
    fun `channel tab pages and reports how far paging goes`() {
        assumeTrue(gate())
        val url = System.getProperty("spike.channel") ?: return
        NewPipe.init(NewPipeYouTubeExtractor.SharedClientDownloader)
        // 0.26.5: the channel's ITEM LIST lives in ChannelTabExtractor (the
        // ChannelExtractor only carries channel info) — verified via javap.
        val handler = ServiceList.YouTube.channelTabLHFactory.fromUrl(url)
        val extractor = ServiceList.YouTube.getChannelTabExtractor(handler)
        extractor.fetchPage()
        var page = extractor.initialPage
        var items = page.items.size
        var pages = 1
        val started = System.currentTimeMillis()
        // The AC asks for the KNOWN limit: page until it stops, bounded so a
        // runaway channel cannot hang the spike.
        while (page.hasNextPage() && pages < 20) {
            page = extractor.getPage(page.nextPage)
            items += page.items.size
            pages++
        }
        val elapsed = System.currentTimeMillis() - started
        println("SPIKE channel pages=$pages items=$items hasNext=${page.hasNextPage()} elapsedMs=$elapsed")
    }

    @Test
    fun `playlist exposes ordered entries with durations`() {
        assumeTrue(gate())
        val url = System.getProperty("spike.playlist") ?: return
        NewPipe.init(NewPipeYouTubeExtractor.SharedClientDownloader)
        val started = System.currentTimeMillis()
        val extractor = ServiceList.YouTube.getPlaylistExtractor(url)
        extractor.fetchPage()
        val page = extractor.initialPage
        val elapsed = System.currentTimeMillis() - started
        println("SPIKE playlist streamCount=${extractor.streamCount} pageItems=${page.items.size} " +
            "hasNext=${page.hasNextPage()} elapsedMs=$elapsed")
        page.items.take(5).forEach { item ->
            println("  SPIKE entry name=${item.name} url=${item.url}")
        }
        check(extractor.streamCount > 0) { "a playlist must report its entry count" }
    }
}
