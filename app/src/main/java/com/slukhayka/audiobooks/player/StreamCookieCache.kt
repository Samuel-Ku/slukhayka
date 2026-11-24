package com.slukhayka.audiobooks.player

import com.slukhayka.audiobooks.data.source.SourceCookieProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #1722 — the 4read session cookie used for stream headers, resolved off the
 * main thread at explicit refresh points and reused as a plain memory read.
 *
 * `CookieManager.getCookie` is a synchronous Binder call into the WebView
 * cookie store. Calling it from [AudioPlayerManager.prepareChapter] put that
 * call on the main thread for every user-initiated play and every auto-advance
 * (the ExoPlayer listener runs on the application looper) — the ANR shape in
 * #1722. [cachedHeader] is a volatile read and is the only method the player
 * calls; it never touches the provider. [refresh] reads the live jar and must
 * run on [ioDispatcher].
 *
 * Refresh points, all off the main thread:
 *  - [com.slukhayka.audiobooks.App.onCreate] — warms the cache from the
 *    persisted WebView jar at process start;
 *  - `MainViewModel.closeWebSource` / `failAutomaticBrowserRecovery` — the
 *    in-app browser has just written and flushed its first-party jar, so the
 *    next play sees the session it earned;
 *  - `MainViewModel.startAudiobookPlaybackNow` and
 *    `startRecoveredPlaybackAndAwait` — immediately before an explicit install;
 *    the freshly read header is handed to `loadAndPlayBook` explicitly.
 *
 * Between those points the value is the session cookie: it changes only when
 * the WebView session changes, which is exactly when a refresh point runs. A
 * pause/resume or an auto-advance therefore reuses the current session value
 * instead of paying a Binder round-trip on the main thread.
 */
class StreamCookieCache(
    private val provider: SourceCookieProvider,
    private val url: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Volatile
    private var header: String = ""

    /** Main-thread safe: a volatile read, never a provider/Binder call. */
    fun cachedHeader(): String = header

    /**
     * Reads [url]'s cookie from the live WebView jar on [ioDispatcher].
     * Never call from the main thread. A provider failure keeps the previous
     * value — the same degrade-never-throw contract as [SourceCookieProvider].
     */
    suspend fun refresh(): String {
        val fresh = withContext(ioDispatcher) {
            try {
                provider.cookieFor(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Degrade to the last known session value.
                header
            }
        }
        header = fresh
        return fresh
    }
}
