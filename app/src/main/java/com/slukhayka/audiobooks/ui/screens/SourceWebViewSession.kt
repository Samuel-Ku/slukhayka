package com.slukhayka.audiobooks.ui.screens

import android.webkit.CookieManager
import com.slukhayka.audiobooks.data.source.SourceBrowserPolicy
import com.slukhayka.audiobooks.data.source.SourceSessionCookieDeletion

/** Android-only adapter for the source-scoped cookie deletion policy. */
internal object SourceWebViewSession {
    fun clear(sourceId: String) {
        val cookieManager = CookieManager.getInstance()
        val headers = SourceBrowserPolicy.allowedHostsFor(sourceId).associateWith { host ->
            cookieManager.getCookie("https://$host/")
        }
        SourceSessionCookieDeletion.commandsFor(sourceId, headers).forEach { command ->
            cookieManager.setCookie(command.url, command.value)
        }
        cookieManager.flush()
    }
}
