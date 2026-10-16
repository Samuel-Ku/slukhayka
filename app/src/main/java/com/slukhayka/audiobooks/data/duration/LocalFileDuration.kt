package com.slukhayka.audiobooks.data.duration

import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #1187 — how long the audio inside one LOCAL file is, in seconds; null when
 * the file is missing, unreadable, or its container says nothing usable.
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
    /** @return the audio length in seconds, or null when it cannot be known. */
    suspend fun durationSeconds(path: String): Long?
}

/**
 * The production reader — [MediaMetadataRetriever] over the copied local file:
 * the container's own header, offline, no network, and without reading the
 * audio bytes (the ticket's «метадані, а не байти»). ADR-0061 keeps the
 * original file in the imported folder, so the path the local import stored is
 * the file this opens.
 *
 * Duration is never derived from file size or bitrate here: that arithmetic
 * belongs to the stream prober, where a CBR frame header proves the bitrate. A
 * local container that reports nothing stays unknown.
 */
class MediaMetadataRetrieverLocalFileDuration : LocalFileDurationReader {

    override suspend fun durationSeconds(path: String): Long? = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext null
        if (!File(path).isFile) return@withContext null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                // The metadata is milliseconds; a sub-second value rounds to
                // zero and stays unknown rather than becoming a fake 0.
                ?.div(1_000L)
                ?.takeIf { it > 0L }
        } catch (e: Exception) {
            // A torn download, a codec this device cannot parse, a file the
            // folder lost: the honest answer is "unknown", never a guess.
            Log.w(TAG, "local file duration unreadable: $path", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        const val TAG = "LocalFileDuration"
    }
}
