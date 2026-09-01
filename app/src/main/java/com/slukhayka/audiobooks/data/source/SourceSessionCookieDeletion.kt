package com.slukhayka.audiobooks.data.source

/**
 * Produces narrowly scoped WebView cookie-expiry commands for one Source.
 *
 * Cookie values are deliberately never returned or persisted. The caller may
 * read WebView's in-memory Cookie header solely to identify cookie *names*,
 * then immediately replace those names with expiry commands at the matching
 * first-party host. This keeps a user's hard-earned source session durable
 * while still letting them clear one Source without erasing another Source.
 */
object SourceSessionCookieDeletion {
    data class Command(val url: String, val value: String)

    fun commandsFor(
        sourceId: String,
        cookieHeadersByHost: Map<String, String?>
    ): List<Command> = SourceBrowserPolicy.allowedHostsFor(sourceId)
        .toList()
        .sorted()
        .flatMap { host ->
            cookieNames(cookieHeadersByHost[host]).map { name ->
                Command(
                    url = "https://$host/",
                    value = "$name=; Max-Age=0; Path=/; Secure"
                )
            }
        }

    private fun cookieNames(header: String?): List<String> = header
        .orEmpty()
        .split(';')
        .mapNotNull { fragment ->
            val name = fragment.substringBefore('=', missingDelimiterValue = "").trim()
            name.takeIf { it.matches(COOKIE_NAME) }
        }
        .distinct()

    private val COOKIE_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
}
