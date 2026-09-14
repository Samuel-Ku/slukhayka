package com.slukhayka.audiobooks.data.privacy

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import java.io.IOException

/** Refused audio hosts and known substitute recordings never become playable Sources. */
object AudioNoticePolicy {
    const val ERROR_CODE = "AUDIO_NOTICE_BLOCKED"

    private object AudioRequest

    /** Audio refusal does not suppress the source's HTML, metadata or covers. */
    fun audioRequest(request: Request): Request =
        request.newBuilder().tag(AudioRequest::class.java, AudioRequest).build()

    private fun HttpUrl.belongsTo(domain: String): Boolean =
        host.trimEnd('.').let { it == domain || it.endsWith(".$domain") }

    fun isBlockedAudio(url: HttpUrl): Boolean =
        url.belongsTo("4read.org") || isBlocked(url)

    /** Reasd book recordings are allowed; its announcement directory never is. */
    fun isBlocked(url: HttpUrl): Boolean {
        if (!url.belongsTo("reasd.org")) return false
        // HttpUrl decodes escapes. Normalize repeated/encoded separators so
        // they cannot disguise the announcement path on a redirect hop.
        val segments = url.pathSegments.flatMap { it.split('/') }.filter { it.isNotEmpty() }
        return segments.firstOrNull().equals("notice", ignoreCase = true) ||
            segments.lastOrNull().equals("4read-notice.mp3", ignoreCase = true)
    }

    fun causedByNotice(error: Throwable?): Boolean =
        generateSequence(error) { it.cause }.any { it is BlockedAudioNoticeException }

    /** Also installed at the network seam: inspect each hop BEFORE following Location. */
    fun interceptor(audioOnly: Boolean = false) = Interceptor { chain ->
        val request = chain.request()
        val audio = audioOnly || request.tag(AudioRequest::class.java) != null
        fun blocked(url: HttpUrl) = if (audio) isBlockedAudio(url) else isBlocked(url)
        if (blocked(request.url)) throw BlockedAudioNoticeException()
        val response = chain.proceed(if (audioOnly) audioRequest(request) else request)
        val target = response.header("Location")?.let(request.url::resolve)
        if (response.code in setOf(301, 302, 303, 307, 308) && target != null && blocked(target)) {
            response.close()
            throw BlockedAudioNoticeException()
        }
        response
    }
}

/** Carries a stable verdict through OkHttp/Media3 without logging the signed URL. */
class BlockedAudioNoticeException : IOException("Audio source or substitute recording is blocked")
