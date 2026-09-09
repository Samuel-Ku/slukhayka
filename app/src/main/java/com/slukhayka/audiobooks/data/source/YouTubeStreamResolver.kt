package com.slukhayka.audiobooks.data.source

import android.util.Log
import com.slukhayka.audiobooks.data.collections.MiniJson
import com.slukhayka.audiobooks.data.privacy.BrowserIdentity
import com.slukhayka.audiobooks.data.privacy.TransportClients
import com.slukhayka.audiobooks.data.privacy.TransportPrivacy
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Spec 2026-08-26 — the YouTube side of the track-URL contract.
 *
 * A 4read page with no playerjs audio persists its chapters with the YouTube
 * WATCH URL as the track locator (see [WebViewHtmlParser]'s embed fallback).
 * The watch URL is the identity that survives forever; a PLAYABLE stream URL
 * is signed, expires (~6h) and is never persisted. [isYouTubeWatchUrl] tells
 * the two apart everywhere the persisted URL meets the engine or the
 * download loop.
 */
object YouTubeTracks {

    fun isYouTubeWatchUrl(url: String): Boolean =
        url.startsWith("https://www.youtube.com/watch?v=", ignoreCase = true) ||
            url.startsWith("http://www.youtube.com/watch?v=", ignoreCase = true)

    /** The one normalised watch-URL shape the parser persists. */
    fun watchUrlOf(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"
}

/**
 * One candidate audio stream an extractor found for a video — shape-kept
 * away from NewPipe types so the selection logic stays pure and JVM-testable.
 */
data class AudioStreamSpec(
    val url: String,
    val isM4a: Boolean,
    /** True when the URL directly serves bytes (progressive); false for manifests. */
    val isDirectUrl: Boolean,
    val bitrateKbps: Int
)

/**
 * The per-use resolution seam: a persisted track URL in, a playable stream
 * URL out. Identity for plain (non-YouTube) URLs — callers may resolve
 * unconditionally. A YouTube URL resolves through the injected extractor;
 * `null` means honest failure (the video is gone or extraction broke) and
 * every caller must refuse to fabricate audio (ADR-0019).
 *
 * The signed stream URL expires — callers re-resolve on EVERY use, never
 * cache across sessions; a 403 on a resolved URL heals by re-preparing,
 * which re-resolves (the [com.slukhayka.audiobooks.player.StreamHealPolicy]
 * budget stays one retry per user-initiated prepare).
 */
class YouTubeStreamResolver(
    private val extractAudioStreams: suspend (String) -> List<AudioStreamSpec>
) {

    /**
     * Resolves [persistedUrl] to a concrete audio stream URL. Non-YouTube
     * URLs pass through unchanged; a YouTube URL that yields no usable
     * stream returns null (honest failure).
     */
    suspend fun resolve(persistedUrl: String): String? {
        if (!YouTubeTracks.isYouTubeWatchUrl(persistedUrl)) return persistedUrl
        // Deliberately no android.util.Log here: this class is pure JVM and
        // the callers own the logging of a null resolution (the player and
        // the download loop both log it where context lives).
        val candidates = runCatching { extractAudioStreams(persistedUrl) }
            .getOrNull()
            .orEmpty()
        return pickBestAudio(candidates)?.url
    }

    companion object {

        /**
         * The one pure decision: prefer progressive M4A (the engine plays it
         * natively, same family as the playerjs streams), highest bitrate
         * first; any other direct audio stream is the fallback; manifests are
         * never this concept — ExoPlayer receives a plain URI or nothing.
         */
        fun pickBestAudio(candidates: List<AudioStreamSpec>): AudioStreamSpec? =
            candidates.asSequence()
                .filter { it.isDirectUrl && it.url.startsWith("http") }
                .sortedWith(
                    compareByDescending<AudioStreamSpec> { it.isM4a }
                        .thenByDescending { it.bitrateKbps }
                )
                .firstOrNull()
    }
}

/**
 * The production extractor: NewPipeExtractor driven over the ONE shared
 * OkHttp client ([TransportClients.okHttp] — one pool, the device's browser
 * identity, DoH and the privacy route preserved). NewPipe is initialised
 * once per process.
 */
object NewPipeYouTubeExtractor {

    @Volatile
    private var initialized = false

    /**
     * Extracts the audio streams of one video. Blocking extraction on the IO
     * dispatcher — callers already sit off the main thread. A failure is
     * logged HERE (with the exception): the pure resolver above swallows into
     * an honest null by design, and without this line a device-side
     * extraction death is undiagnosable (device debugging, 2026-08-26).
     */
    suspend fun extract(watchUrl: String): List<AudioStreamSpec> = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            val info = StreamInfo.getInfo(watchUrl)
            info.audioStreams.map { stream ->
                AudioStreamSpec(
                    url = stream.content,
                    isM4a = stream.format == org.schabi.newpipe.extractor.MediaFormat.M4A,
                    isDirectUrl = stream.isUrl,
                    bitrateKbps = stream.averageBitrate
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Throwable deliberately: the classpath death this module exists
            // to surface is a NoClassDefFoundError (an Error, not an
            // Exception) — the 2026-08-26 protobuf clash died invisibly
            // until this catch was widened.
            Log.e("YouTubeResolver", "extraction failed for $watchUrl", t)
            throw t
        }
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            NewPipe.init(SharedClientDownloader)
            initialized = true
        }
    }

    /**
     * NewPipe's transport door backed by the shared client. The request
     * mirrors [HttpFetcher]'s identity (browser UA + Accept headers, relay
     * rewrite) so YouTube rides the same wire rules as every other source.
     */
    private object SharedClientDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val target = TransportPrivacy.rewriteThroughRelay(request.url())
            val builder = okhttp3.Request.Builder().url(target)
            // YouTube's innertube API (the player endpoint) is POST with a
            // body — a GET-only downloader answers every call with garbage
            // and extraction dies honestly. NewPipe's method + body lead.
            request.dataToSend()?.let { body ->
                builder.post(okhttp3.RequestBody.create(null, body))
            } ?: builder.get()
            // NewPipe's own headers (Accept-Language, the localization set)
            // win where present; the device browser identity fills the gap.
            request.headers().forEach { (name, values) ->
                values.filter { it.isNotBlank() }.forEach { value -> builder.header(name, value) }
            }
            if (request.headers()["User-Agent"].isNullOrEmpty()) {
                builder.header("User-Agent", BrowserIdentity.currentUserAgent())
            }
            return TransportClients.okHttp.newCall(builder.build()).execute().use { response ->
                Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    response.body?.string().orEmpty(),
                    response.request.url.toString()
                )
            }
        }
    }
}

/**
 * The alternate production extractor (ADR-0035 / #603): yt-dlp driven
 * RESOLVE-ONLY — the binary prints its full JSON (`-J --no-download
 * --no-playlist`) and the shared pure-JVM [MiniJson] decoder maps the
 * formats to [AudioStreamSpec]. Bytes are NEVER fetched here: the returned
 * URL rides the existing download loop / player through the shared
 * [com.slukhayka.audiobooks.data.privacy.TransportClients] (spec-38 route,
 * human-rhythm pacing) — the same contract as [NewPipeYouTubeExtractor], so
 * swapping one engine for the other behind [YouTubeStreamResolver] is a
 * one-line change. Runs on [Dispatchers.IO]; any failure yields an EMPTY
 * list — the pure resolver turns it into the honest null (ADR-0019, no
 * fabricated audio).
 */
object YtDlpStreamExtractor {

    /**
     * Drops `"key": null` pairs BEFORE [MiniJson.parse] — the shared pure
     * decoder treats a JSON null as a parse error (its `parseValue` returns
     * null for both `null` and failure), so a document carrying nulls (and
     * yt-dlp emits `"acodec": null` / `"abr": null` on its HLS and
     * storyboard formats) would otherwise decode to nothing. The field is
     * absent after the strip — the format reads treat absence exactly like
     * null. Three passes cover pair-with-trailing-comma, pair-with-leading-
     * comma and a lone last pair; a `: null` inside a quoted STRING value is
     * never matched (the value there is quoted, not bare `null`).
     */
    internal fun stripNullFields(json: String): String {
        val nullField = Regex("\"[A-Za-z0-9_-]+\"\\s*:\\s*null")
        val trailingComma = Regex("\"[A-Za-z0-9_-]+\"\\s*:\\s*null\\s*,")
        val leadingComma = Regex(",\\s*\"[A-Za-z0-9_-]+\"\\s*:\\s*null")
        val lone = nullField
        // Repeated until stable: a pair once trailing-comma-stripped may leave
        // a new leading-comma neighbour, and vice versa.
        var text = json
        var changed = true
        while (changed) {
            changed = false
            val a = trailingComma.replace(text, "")
            val b = leadingComma.replace(a, "")
            val c = lone.replace(b, "")
            if (c != text) { text = c; changed = true }
        }
        return text
    }

    /**
     * Parses one `yt-dlp -J` document into audio candidates. Pure JVM,
     * fixture-tested from a REAL capture (2026-09-07, ozaZXk5Qcwc).
     * Audio-only formats only: a format whose `vcodec` is not `none` (a
     * video+audio file) or that carries NO audio codec (storyboard
     * mhtml stubs, HLS audio shells) is never an audio stream this
     * resolver picks.
     */
    fun parseFormats(ytDlpJson: String): List<AudioStreamSpec> {
        val root = MiniJson.parse(stripNullFields(ytDlpJson)) as? Map<*, *> ?: return emptyList()
        val formats = root["formats"] as? List<*> ?: return emptyList()
        return formats.mapNotNull { item ->
            val format = item as? Map<*, *> ?: return@mapNotNull null
            val url = format["url"] as? String ?: return@mapNotNull null
            if (url.isBlank() || !url.startsWith("http")) return@mapNotNull null
            val vcodec = format["vcodec"] as? String ?: return@mapNotNull null
            val acodec = format["acodec"] as? String ?: return@mapNotNull null
            if (vcodec != "none" || acodec.isEmpty() || acodec == "none") return@mapNotNull null
            val protocol = (format["protocol"] as? String).orEmpty()
            val ext = (format["ext"] as? String).orEmpty().lowercase(Locale.ROOT)
            val bitrate = (format["abr"] as? Double)?.toInt() ?: 0
            AudioStreamSpec(
                url = url,
                isM4a = ext == "m4a",
                isDirectUrl = protocol == "https" || protocol == "http",
                bitrateKbps = bitrate
            )
        }
    }

    /** Extracts via the real `yt-dlp` binary (resolve-only JSON). */
    suspend fun extract(watchUrl: String): List<AudioStreamSpec> =
        extract(watchUrl, ::runYtDlpJson)

    /**
     * Fetches the flat-playlist metadata JSON of a submitted link (single
     * video or playlist) — `-J --flat-playlist` returns the title (and, for a
     * playlist, its ordered entries with titles + URLs) WITHOUT fetching each
     * video's formats. This is the [LibraryImport.importSubmittedYouTube]
     * input: title + entries are the observed identity and chapter list
     * (ADR-0035 / #604). Resolve-only: no bytes are downloaded.
     */
    suspend fun fetchMetadataJson(url: String): String? = fetchMetadataJson(url, ::runFlatPlaylistJson)

    /** The seam-tested path: an injected launcher supplies the metadata JSON. */
    suspend fun fetchMetadataJson(url: String, launcher: suspend (String) -> String?): String? =
        withContext(Dispatchers.IO) {
            runCatching { launcher(url) }.getOrNull()
        }

    /**
     * The seam-tested path: an injected launcher supplies the `-J` JSON, so
     * the extract/parse flow is JVM-testable without a binary on CI.
     */
    suspend fun extract(
        watchUrl: String,
        launcher: suspend (String) -> String?
    ): List<AudioStreamSpec> = withContext(Dispatchers.IO) {
        val json = runCatching { launcher(watchUrl) }.getOrNull() ?: return@withContext emptyList()
        parseFormats(json)
    }

    /**
     * Runs `yt-dlp -J --no-download --no-playlist <url>` and returns stdout,
     * or null on any failure (missing binary, network, non-zero exit). The
     * signed stream URLs in the JSON expire (~6h) — callers re-resolve per
     * use, never cache (the same rule as NewPipe).
     */
    private suspend fun runYtDlpJson(watchUrl: String): String? = try {
        val process = ProcessBuilder(
            "yt-dlp", "-J", "--no-download", "--no-playlist", "--no-warnings", watchUrl
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() != 0) null else output
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.w("YtDlpExtractor", "yt-dlp failed for $watchUrl", t)
        null
    }

    private suspend fun runFlatPlaylistJson(url: String): String? = try {
        val process = ProcessBuilder(
            "yt-dlp", "-J", "--flat-playlist", "--no-download", "--no-warnings", url
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() != 0) null else output
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.w("YtDlpExtractor", "yt-dlp metadata failed for $url", t)
        null
    }
}
