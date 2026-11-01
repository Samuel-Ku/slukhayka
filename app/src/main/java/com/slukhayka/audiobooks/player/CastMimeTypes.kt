package com.slukhayka.audiobooks.player

import com.slukhayka.audiobooks.data.source.YouTubeTracks
import java.util.Locale

/**
 * #1751 — the MIME decisions of the cast playlist and proxy fallback.
 *
 * [androidx.media3.cast.CastPlayer]'s default converter requires a MIME type,
 * so a YouTube watch URL — an identity with no file extension — cannot simply
 * be guessed as `audio/mpeg`: the resolver prefers progressive M4A, and the
 * playlist must declare the family the receiver will actually get. The proxy
 * still prefers the origin's own `Content-Type`; this is only its fallback.
 */
internal object CastMimeTypes {

    /** The audio family the cast playlist declares for [url]. */
    fun forCast(url: String): String =
        if (YouTubeTracks.isYouTubeWatchUrl(url)) "audio/mp4" else guess(url)

    /** The extension-based fallback the proxy uses when the origin says nothing. */
    fun guess(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return when {
            path.endsWith(".m4b") || path.endsWith(".m4a") -> "audio/mp4"
            path.endsWith(".aac") -> "audio/aac"
            path.endsWith(".ogg") || path.endsWith(".opus") -> "audio/ogg"
            path.endsWith(".wav") -> "audio/wav"
            path.endsWith(".flac") -> "audio/flac"
            else -> "audio/mpeg"
        }
    }
}
