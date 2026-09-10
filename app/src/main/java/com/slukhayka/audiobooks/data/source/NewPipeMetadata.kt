package com.slukhayka.audiobooks.data.source

import android.util.Log
import com.slukhayka.audiobooks.data.collections.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Spec-53 T2 — the in-app metadata engine for listener submissions. NewPipe
 * (already the stream resolver; verified for metadata by the wave-53 T1
 * spike — `docs/wayfinder/research/sources-wave-53-spike.md`) supplies the
 * title, the single-video duration and the ORDERED playlist entries with
 * REAL durations. The external yt-dlp binary is no longer on the listener
 * path: it stays as an optional backend behind the seam, never a second
 * failure for the listener.
 *
 * [metadataJsonOf] is pure and builds exactly the document the planner
 * already parses (title, optional duration, entries[]), so nothing
 * downstream changes shape. [fetchMetadataJson] runs NewPipe on the IO
 * dispatcher; any failure is an honest null — never a fabricated document.
 * The transport is the shared client ([NewPipeYouTubeExtractor]), one
 * NewPipe initialisation per process.
 */
object NewPipeMetadata {

    /** One playlist entry as the engine observes it. */
    data class Entry(
        val id: String?,
        val url: String?,
        val title: String?,
        val durationSeconds: Long? = null
    )

    /** The observed metadata: title + optional total (single video only) + entries. */
    data class Metadata(
        val title: String,
        val durationSeconds: Long? = null,
        val entries: List<Entry> = emptyList()
    )

    /**
     * Builds the planner's JSON document. Nulls are omitted (the shared
     * decoder treats bare nulls as parse errors), strings are escaped, and a
     * blank title yields an empty document — the caller reports the honest
     * refusal.
     */
    fun metadataJsonOf(metadata: Metadata): String {
        if (metadata.title.isBlank()) return ""
        val out = StringBuilder()
        out.append('{')
        out.append("\"title\":\"").append(escape(metadata.title)).append('"')
        metadata.durationSeconds?.takeIf { it > 0 }?.let {
            out.append(",\"duration\":").append(it)
        }
        if (metadata.entries.isNotEmpty()) {
            out.append(",\"entries\":[")
            metadata.entries.forEachIndexed { index, entry ->
                if (index > 0) out.append(',')
                out.append('{')
                val fields = mutableListOf<Pair<String, String>>()
                entry.id?.takeIf { it.isNotBlank() }?.let { fields += "id" to "\"${escape(it)}\"" }
                entry.url?.takeIf { it.isNotBlank() }?.let { fields += "url" to "\"${escape(it)}\"" }
                entry.title?.let { fields += "title" to "\"${escape(it)}\"" }
                entry.durationSeconds?.takeIf { it > 0 }?.let { fields += "duration" to it.toString() }
                out.append(fields.joinToString(",") { (name, value) -> "\"$name\":$value" })
                out.append('}')
            }
            out.append(']')
        }
        out.append('}')
        return out.toString()
    }

    /**
     * The listener-path fetch: NewPipe metadata mapped to the planner's JSON,
     * or null. [provider] is the injected seam (JVM tests never touch the
     * network); production uses [fetchWithNewPipe].
     */
    suspend fun fetchMetadataJson(
        url: String,
        provider: suspend (String) -> Metadata? = ::fetchWithNewPipe
    ): String? = withContext(Dispatchers.IO) {
        val metadata = runCatching { provider(url) }.getOrNull() ?: return@withContext null
        metadataJsonOf(metadata).takeIf { it.isNotEmpty() }
    }

    /** Playlist links carry `list=`; everything else is treated as a video. */
    internal fun isPlaylistUrl(url: String): Boolean = url.contains("list=")

    /**
     * The production engine: PlaylistInfo for playlists (ordered entries with
     * real durations), StreamInfo for a single video (title + duration). The
     * channel door (T10) reuses the same mapping.
     */
    internal suspend fun fetchWithNewPipe(url: String): Metadata? {
        NewPipeYouTubeExtractor.ensureInitialized()
        return try {
            if (isPlaylistUrl(url)) {
                val playlist = PlaylistInfo.getInfo(ServiceList.YouTube, url)
                Metadata(
                    title = playlist.name.orEmpty(),
                    entries = playlist.relatedItems
                        .filterIsInstance<StreamInfoItem>()
                        .map { item ->
                            Entry(
                                id = watchIdOf(item.url),
                                url = item.url,
                                title = item.name,
                                durationSeconds = item.duration.takeIf { it > 0 }
                            )
                        }
                )
            } else {
                val stream = StreamInfo.getInfo(ServiceList.YouTube, url)
                Metadata(
                    title = stream.name.orEmpty(),
                    durationSeconds = stream.duration.takeIf { it > 0 }
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("NewPipeMetadata", "metadata failed for $url", t)
            null
        }
    }

    private fun watchIdOf(watchUrl: String): String? =
        watchUrl.substringAfter("v=", "").substringBefore('&').takeIf { it.isNotBlank() }

    private fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        return out.toString()
    }
}
