package com.slukhayka.audiobooks.data.ingest

import java.net.URI

/**
 * Spec-53 T6 — one canonical form per submitted link, so dedup and identity
 * are real instead of string luck. Every live YouTube shape (youtu.be,
 * `music.`/`m.`/`www.`, `/shorts/`, `/embed/`, `/live/`, `/v/`, `/watch`
 * with tracking params or a timecode) collapses to the SAME URL, and a
 * Telegram post loses its query string. Pure: no network, no state, no
 * clock — the whole policy is one table-testable function.
 *
 * Unsupported links return null; the flow's [ListenerSubmissionFlow.classify]
 * never sees a shape this policy cannot name.
 */
object SubmissionUrlCanonicalizer {

    /** The canonical submission URL, or null when the link is unsupported. */
    fun canonical(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return null
        val host = hostOf(url) ?: return null
        val path = pathOf(url)
        return when {
            host == "youtu.be" -> videoIdFromPath(path, allowBare = true)?.let(::watchUrl)
            host == "youtube.com" || host.endsWith(".youtube.com") ->
                videoIdOf(url, host, path)?.let(::watchUrl)
                    ?: playlistIdOf(url)?.let(::playlistUrl)
                    ?: channelUrlOf(path)
            host == "t.me" || host == "telegram.me" || host == "www.t.me" ->
                telegramUrlOf(path)
            else -> null
        }
    }

    /** The canonical form, or the trimmed input when it is not supported. */
    fun canonicalOrSelf(raw: String): String = canonical(raw) ?: raw.trim()

    private fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    private fun playlistUrl(playlistId: String) = "https://www.youtube.com/playlist?list=$playlistId"

    /**
     * A YouTube video in any of its live shapes. The id wins over everything
     * else: a watch URL that also carries `list=` stays a VIDEO, because that
     * is what the listener pasted and what plays.
     */
    private fun videoIdOf(url: String, host: String, path: String): String? {
        if (host == "youtu.be") return videoIdFromPath(path, allowBare = true)
        queryOf(url)["v"]?.takeIf { it.isNotBlank() }?.let { return it }
        return videoIdFromPath(path, allowBare = false)
    }

    /**
     * `/shorts/<id>`, `/embed/<id>`, `/live/<id>`, `/v/<id>` — and, on the
     * short host only, a bare `/‌<id>` segment. A bare segment on
     * `youtube.com` is a PATH (`/playlist`), never a video id.
     */
    private fun videoIdFromPath(path: String, allowBare: Boolean): String? {
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        val head = segments.first()
        val candidate = when (head) {
            "shorts", "embed", "live", "v" -> segments.getOrNull(1)
            else -> if (allowBare && segments.size == 1) head else null
        }
        return candidate?.takeIf { VIDEO_ID.matches(it) }
    }

    private fun playlistIdOf(url: String): String? =
        queryOf(url)["list"]?.takeIf { it.isNotBlank() }

    /**
     * A whole channel (spec-53 T10 consumes it): `/channel/<id>`, `/@handle`,
     * `/c/<name>` and `/user/<name>` keep their path — the handle IS the
     * identity — but tracking params are dropped.
     */
    private fun channelUrlOf(path: String): String? {
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        val first = segments.firstOrNull() ?: return null
        return when {
            first == "channel" || first == "c" || first == "user" ->
                segments.getOrNull(1)?.let { "https://www.youtube.com/$first/$it" }
            first.startsWith("@") -> "https://www.youtube.com/$first"
            else -> null
        }
    }

    /** A Telegram post or preview: the path is the identity, the query is noise. */
    private fun telegramUrlOf(path: String): String? {
        val trimmed = path.trim('/')
        return trimmed.takeIf { it.isNotBlank() }?.let { "https://t.me/$it" }
    }

    private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{6,}""")

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun pathOf(url: String): String =
        runCatching { URI(url).path.orEmpty() }.getOrDefault("")

    private fun queryOf(url: String): Map<String, String> =
        runCatching {
            URI(url).rawQuery.orEmpty()
                .split('&')
                .mapNotNull { part ->
                    val separator = part.indexOf('=')
                    if (separator <= 0) null
                    else part.substring(0, separator) to part.substring(separator + 1)
                }
                .toMap()
        }.getOrDefault(emptyMap())
}
