package com.slukhayka.audiobooks.data.privacy

import okhttp3.HttpUrl
import okhttp3.Interceptor
import java.io.IOException

/** Known substitute audio is never a playable Source, including on a shared CDN. */
object AudioNoticePolicy {
    const val ERROR_CODE = "AUDIO_NOTICE_BLOCKED"

    fun isBlocked(url: HttpUrl): Boolean =
        (url.host == "reasd.org" || url.host.endsWith(".reasd.org")) &&
            url.pathSegments.joinToString("/").trimEnd('/').equals(
                "notice/4read-notice.mp3", ignoreCase = true
            )

    fun causedByNotice(error: Throwable?): Boolean =
        generateSequence(error) { it.cause }.any { it is BlockedAudioNoticeException }

    /** Also installed at the network seam: inspect each hop BEFORE following Location. */
    fun interceptor() = Interceptor { chain ->
        val request = chain.request()
        if (isBlocked(request.url)) throw BlockedAudioNoticeException()
        val response = chain.proceed(request)
        val target = response.header("Location")?.let(request.url::resolve)
        if (response.code in setOf(301, 302, 303, 307, 308) && target != null && isBlocked(target)) {
            response.close()
            throw BlockedAudioNoticeException()
        }
        response
    }
}

/** Carries a stable verdict through OkHttp/Media3 without logging the signed URL. */
class BlockedAudioNoticeException : IOException("Source redirected to blocked substitute audio")
