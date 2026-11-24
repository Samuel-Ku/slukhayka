package com.slukhayka.audiobooks.data.duration

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.slukhayka.audiobooks.App
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #1187 — how long the audio behind one LOCAL address is, in seconds; null when
 * it is missing, unreadable, or its container says nothing usable.
 *
 * This is the local half of the chapter-duration probe: a local import has no
 * site total and no stream to measure, so before this seam its chapters only
 * got a duration from playback — the book page showed a sum that grew with
 * every chapter heard, and `position >= that sum` "finished" a book nobody had
 * finished.
 *
 * The seam is an interface because the measurement itself is Android's: the
 * pass is unit-tested against a fake, and the fake is also the honest answer
 * for a platform without a retriever.
 */
fun interface LocalFileDurationReader {
    /**
     * @param address a local file path, or a SAF document URI (ADR-0061: the
     *   folder is the storage, so a by-reference track's address is `content://`).
     * @return the audio length in seconds, or null when it cannot be known.
     */
    suspend fun durationSeconds(address: String): Long?
}

/**
 * The production reader — [MediaMetadataRetriever] over the local audio: the
 * container's own header, offline, no network, and without reading the audio
 * bytes (the ticket's «метадані, а не байти»).
 *
 * Two kinds of address reach it. A copy written by an older version is a plain
 * file path. A by-reference import (ADR-0061) stores the SAF document URI as
 * the track's only address, so the retriever must open that URI through the
 * ContentResolver — the persisted grant taken by the picker keeps it readable
 * across launches. [contextProvider] exists so tests can pass null and the
 * default reads the app singleton ([App.instance]); a platform without a
 * context simply answers "unknown".
 *
 * Duration is never derived from file size or bitrate here: that arithmetic
 * belongs to the stream prober, where a CBR frame header proves the bitrate. A
 * local container that reports nothing stays unknown.
 */
class MediaMetadataRetrieverLocalFileDuration(
    private val contextProvider: () -> Context? = { runCatching { App.instance }.getOrNull() }
) : LocalFileDurationReader {

    override suspend fun durationSeconds(address: String): Long? = withContext(Dispatchers.IO) {
        if (address.isBlank()) return@withContext null
        val retriever = MediaMetadataRetriever()
        try {
            if (address.startsWith("content://")) {
                val context = contextProvider() ?: return@withContext null
                retriever.setDataSource(context, Uri.parse(address))
            } else {
                if (!File(address).isFile) return@withContext null
                retriever.setDataSource(address)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                // The metadata is milliseconds; a sub-second value rounds to
                // zero and stays unknown rather than becoming a fake 0.
                ?.div(1_000L)
                ?.takeIf { it > 0L }
        } catch (e: Exception) {
            // A torn file, a codec this device cannot parse, a grant the folder
            // lost: the honest answer is "unknown", never a guess.
            Log.w(TAG, "local duration unreadable: $address", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        const val TAG = "LocalFileDuration"
    }
}
