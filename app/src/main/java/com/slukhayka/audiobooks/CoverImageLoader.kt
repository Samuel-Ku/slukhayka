package com.slukhayka.audiobooks

import android.content.Context
import coil.ImageLoader
import com.slukhayka.audiobooks.data.privacy.TransportClients

/**
 * #1742 — the app's ONE cover-image loader.
 *
 * It used to be built in `MainActivity.onCreate`, on the main thread, before
 * `setContent` (the cold-start profile showed that block inside `performCreate`).
 * The loader is application-scoped now: [App.imageLoader] owns the lazy, the
 * prewarm in `App.onCreate` builds it on `Dispatchers.IO`, and the same
 * instance is installed with `Coil.setImageLoader` so every `AsyncImage` in
 * the app keeps using it.
 *
 * [buildThread] is the thread that actually constructed the loader. It is a
 * witness, not decoration: `StartupGraphTest` asserts that Activity creation
 * never builds it on the main thread.
 */
internal object CoverImageLoader {

    @Volatile
    var buildThread: Thread? = null
        private set

    /** True once the prewarm installed the loader into Coil's singleton. */
    @Volatile
    var installed: Boolean = false
        private set

    /** Installs [loader] for every screen; called from the IO prewarm. */
    fun install(loader: ImageLoader) {
        coil.Coil.setImageLoader(loader)
        installed = true
    }

    fun build(context: Context): ImageLoader {
        // Record before the builder so a slow construction cannot hide which
        // thread started it.
        buildThread = Thread.currentThread()
        return ImageLoader.Builder(context.applicationContext)
            // Globally disable hardware bitmaps in Coil to prevent the
            // E/ashmem "Pinning is deprecated" errors (kept from the Activity
            // block this replaced).
            .allowHardware(false)
            // Spec-38 (#252): cover-image traffic is transport traffic too — it
            // rides the same browser identity (real WebView UA) and the same
            // privacy route as the shared fetcher. Spec-38 T4 (#256): that one
            // client is the shared [TransportClients.okHttp] — same pool,
            // current route, and domain names resolved through the DoH door, so
            // cover lookups no longer leak to the system resolver either.
            .callFactory { TransportClients.calls }
            .build()
    }
}
