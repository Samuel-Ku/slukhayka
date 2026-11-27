package com.slukhayka.audiobooks.player

import com.slukhayka.audiobooks.data.source.YouTubeTracks
import java.util.Locale

/**
 * #1751 — the MIME decisions of the cast playlist and proxy fallback.
 *
 * [androidx.media3.cast.CastPlayer]'s default converter requires a MIME type,
 * so a YouTube watch URL — an identity with no file extension — cannot simply
 * be guessed as `audio/mpeg`. The resolver PREFERS progressive M4A, so such a
 * chapter declares [AUDIO_MP4]; the declaration is not a hope: [CastUpstream]
 * resolves the URL before serving it and refuses a stream of any other
 * family, so the receiver can only ever get the family the playlist declared
 * (#1751 review, P2). The proxy still prefers the origin's own
 * `Content-Type`, then the resolved stream's real family; this object is only
 * the last fallback.
 */
internal object CastMimeTypes {

    /** The one family the cast playlist declares for a YouTube watch URL. */
    const val AUDIO_MP4 = "audio/mp4"

    /** The audio family the cast playlist declares for [url]. */
    fun forCast(url: String): String =
        if (YouTubeTracks.isYouTubeWatchUrl(url)) AUDIO_MP4 else guess(url)

    /**
     * True when [declared] is the family a resolved stream with
     * [resolvedMime] really serves. No answer from the extractor cannot prove
     * the declaration wrong, so an unknown family passes; evidence of another
     * family is exactly the lie #1751 refuses to hand the receiver.
     */
    fun declaresFamily(declared: String, resolvedMime: String?): Boolean {
        val actual = resolvedMime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() } ?: return true
        return actual == declared.lowercase(Locale.ROOT)
    }

    /** The extension-based fallback the proxy uses when nothing better is known. */
    fun guess(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return when {
            path.endsWith(".m4b") || path.endsWith(".m4a") -> AUDIO_MP4
            path.endsWith(".aac") -> "audio/aac"
            path.endsWith(".ogg") || path.endsWith(".opus") -> "audio/ogg"
            path.endsWith(".wav") -> "audio/wav"
            path.endsWith(".flac") -> "audio/flac"
            else -> "audio/mpeg"
        }
    }
}
