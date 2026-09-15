package com.slukhayka.audiobooks.data.source

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Lihtar keeps book recordings and spoken navigation cues in separate directories. */
internal object LihtarAudio {
    fun isBookAudio(url: String): Boolean = hasPath(url, "/audio/library/")

    fun isNavigationAudio(url: String): Boolean = hasPath(url, "/audio/name/")

    private fun hasPath(url: String, prefix: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.host == "web.lihtar.in.ua" && parsed.encodedPath.startsWith(prefix) &&
            parsed.username.isEmpty() && parsed.password.isEmpty()
    }
}
