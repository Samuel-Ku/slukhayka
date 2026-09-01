package com.slukhayka.audiobooks.ui.screens

import android.webkit.CookieManager
import com.slukhayka.audiobooks.data.source.SourceBrowserPolicy
import com.slukhayka.audiobooks.data.source.SourceSessionCookieDeletion
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Android-only adapter for the source-scoped cookie deletion policy. */
internal object SourceWebViewSession {
    private val visitedUrls = ConcurrentHashMap<String, MutableSet<String>>()

    fun rememberVisitedUrl(sourceId: String, url: String) {
        if (!SourceBrowserPolicy.isUrlAllowed(url, sourceId)) return
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        val origin = "${uri.scheme}://${uri.host}"
        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
        val paths = buildList {
            add("/")
            var path = ""
            segments.forEach { segment ->
                path += "/$segment"
                add(path)
                add("$path/")
            }
        }
        visitedUrls.computeIfAbsent(sourceId) { ConcurrentHashMap.newKeySet() }
            .addAll(paths.map { path -> "$origin$path" })
    }

    fun clear(sourceId: String) {
        val cookieManager = CookieManager.getInstance()
        val rootUrls = SourceBrowserPolicy.allowedHostsFor(sourceId).map { host -> "https://$host/" }
        val targets = (rootUrls + visitedUrls[sourceId].orEmpty()).distinct()
        val headers = targets.associateWith { url ->
            cookieManager.getCookie(url)
        }
        SourceSessionCookieDeletion.commandsForUrls(sourceId, headers).forEach { command ->
            cookieManager.setCookie(command.url, command.value)
        }
        cookieManager.flush()
        visitedUrls.remove(sourceId)
    }
}
