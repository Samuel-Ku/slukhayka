package com.slukhayka.audiobooks.data.source

import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * The scheduled YouTube contract canary — the early-warning probe for the
 * component that breaks first when YouTube changes its anti-bot story.
 *
 * It is deliberately Android-free (a plain HttpURLConnection transport, no
 * privacy relay, no browser identity) so it can ride an ordinary JVM test in
 * scheduled CI: its job is to notice a *platform* change, not to exercise our
 * own transport. The NewPipeExtractor under test is whatever the version
 * catalog currently ships.
 *
 * Gated exactly like the #708 spike, so ordinary PR CI only skips it:
 *
 *   ./gradlew :app:testDebugUnitTest --tests "*YouTubeContractCanaryTest" \
 *     -Dyoutube.canary=1 [-Dyoutube.canary.video=<url>]
 *
 * A red canary means: bump the engine before tagging a release.
 */
class YouTubeContractCanaryTest {

    private fun gate(): Boolean = System.getProperty("youtube.canary") != null

    /** A plain transport: no relay, no header substitution, no Android. */
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

    @Test
    fun `a public video still resolves metadata and a fetchable audio stream`() {
        assumeTrue(gate())
        val url = System.getProperty("youtube.canary.video") ?: DEFAULT_VIDEO
        NewPipe.init(PlainDownloader)
        val info = StreamInfo.getInfo(ServiceList.YouTube.getStreamExtractor(url))

        check(info.name.isNotBlank()) { "canary: video name is blank — extraction is broken" }
        check(info.duration > 0) { "canary: duration is unknown — extraction is broken" }
        val audio = info.audioStreams.filter { it.isUrl && it.content.startsWith("http") }
        check(audio.isNotEmpty()) {
            "canary: the player response carries no direct audio stream — YouTube changed the client"
        }

        // The decisive signal: a signed stream URL that answers 403 is exactly
        // how YouTube's usual poToken/signature break shows up.
        val probe = audio.maxByOrNull { it.averageBitrate }!!
        val status = rangedGetStatus(probe.content)
        println(
            "CANARY name=${info.name} durationSec=${info.duration} " +
                "audioStreams=${audio.size} probeHttp=$status"
        )
        check(status in 200..299) {
            "canary: audio stream answered HTTP $status — signatures/poTokens likely broke"
        }
    }

    private fun rangedGetStatus(streamUrl: String): Int {
        val conn = URL(streamUrl).openConnection() as HttpURLConnection
        conn.setRequestProperty("Range", "bytes=0-1023")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        return try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        /**
         * "Me at the zoo" — the first YouTube video: public, tiny and by far
         * the most likely upload to survive as a stable anonymous probe.
         */
        const val DEFAULT_VIDEO = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
    }
}
