package com.example

import android.app.Application
import com.example.data.catalog.SourceCatalog
import com.example.data.collections.CollectionAssets
import com.example.data.collections.OpenLibraryTrendingSource
import com.example.data.db.AudiobookDatabase
import com.example.data.downloads.OfflineDownloads
import com.example.data.duration.ChapterDurationProbe
import com.example.data.duration.DurationEnrichment
import com.example.data.duration.HttpStreamProber
import com.example.data.entries.LibraryEntries
import com.example.data.imports.LibraryImport
import com.example.data.listening.ListeningStateStore
import com.example.data.metadata.StoredTitleScrub
import com.example.data.source.AudiobookMp3Adapter
import com.example.data.source.FourReadAdapter
import com.example.data.source.LihtarAdapter
import com.example.data.source.SluhayAdapter
import com.example.data.source.SluhayuaAdapter
import com.example.data.source.SoundBooksAdapter
import com.example.data.source.SourceAdapter
import com.example.player.AudioPlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Application-scoped dependency graph.
 *
 * ADR-0002 (#140): the five deep modules compose here — Listening State,
 * Library Import, Source Catalog, Offline Downloads, Library Entries — and
 * are shared by MainViewModel, PlaybackService and the widgets. The god
 * repository is gone; every caller composes these modules directly.
 *
 * Previously [com.example.ui.MainViewModel] constructed the repository and
 * [AudioPlayerManager] and released the player in `onCleared()` — which meant
 * backgrounding the app and letting the system destroy the Activity (or the
 * ViewModel store) killed playback within minutes. The playback stack now
 * lives here, for the lifetime of the process, and is shared by:
 *
 *  - `MainViewModel` (UI state + playback commands)
 *  - `PlaybackService` (MediaSession + notification for background playback)
 *
 * AudioPlaybackEspressoTest reads `App.instance` through the activity's
 * `MainViewModel`, so the single instance is intentionally kept here rather
 * than duplicated.
 */
class App : Application() {

    private val database by lazy { AudiobookDatabase.getDatabase(this) }

    /** ADR-0002: one Listening State Store shared by the player and the ViewModel. */
    val listeningState: ListeningStateStore by lazy { ListeningStateStore(database.audiobookDao()) }

    /**
     * Every verified source behind the adapter seam (spec-10 T4 + spec-13 T2).
     * sluhay (WebView-pattern, spec-13) joins now that its adapter parses the
     * captured page + fetches the inline playlist with the source Referer.
     * The cookie lambda only runs on fetchNew (Android-side), so JVM fixture
     * tests stay free of WebView.
     */
    private val sourceAdapters: List<SourceAdapter> by lazy {
        listOf(
            FourReadAdapter(),
            SoundBooksAdapter(),
            AudiobookMp3Adapter(),
            LihtarAdapter(),
            SluhayuaAdapter(),
            SluhayAdapter(cookieProvider = {
                runCatching {
                    android.webkit.CookieManager.getInstance().getCookie("https://sluhay.com/")
                }.getOrNull().orEmpty()
            })
        )
    }

    /** Library Import: the five import doors + rescan over the shared adapters. */
    val libraryImport: LibraryImport by lazy {
        LibraryImport(database.audiobookDao(), this, sourceAdapters)
    }

    /** Source Catalog: browse/sync/search + chapter materialisation. */
    val sourceCatalog: SourceCatalog by lazy {
        // Spec-16: the curated smart-collection lists ride the context seam —
        // one JSON file per collection, loaded once at the composition root.
        // The live «Популярне зараз» list (OpenLibrary trending, keyless) is
        // fetched over the shared HTTP transport on the union refresh.
        SourceCatalog(
            database.audiobookDao(),
            sourceAdapters,
            libraryImport,
            collectionLists = CollectionAssets.load(this),
            liveCollectionSources = listOf(OpenLibraryTrendingSource())
        )
    }

    /** Offline Downloads: download/remove/cache-clear over the catalog's chapter fetch. */
    val offlineDownloads: OfflineDownloads by lazy {
        OfflineDownloads(database.audiobookDao(), this, sourceCatalog)
    }

    /** Library Entries: delete/remove/favourite/metadata + library reads. */
    val libraryEntries: LibraryEntries by lazy {
        LibraryEntries(database.audiobookDao(), sourceAdapters)
    }

    /**
     * spec-18 T2 (#113): the throttled background duration-enrichment pass.
     * The page fetch rides the 4read source adapter — the same seam every
     * other door uses.
     */
    val durationEnrichment: DurationEnrichment by lazy {
        val fourRead = sourceAdapters.first { it.sourceId == "4read" }
        DurationEnrichment(database.audiobookDao(), fourRead::fetchBookPage)
    }

    /**
     * spec-24 T8 (#169): the throttled background chapter-duration probing
     * pass. The chapter→track pairing rides the catalog seam
     * (getPlayableChapters — the player's own pairing); the transport is the
     * only network code (HEAD + ranged GET of the stream head).
     */
    val chapterDurationProbe: ChapterDurationProbe by lazy {
        ChapterDurationProbe(
            database.audiobookDao(),
            sourceCatalog::getPlayableChapters,
            HttpStreamProber()
        )
    }

    /**
     * Spec-24 T1 — the one-time stored-title scrub runner (audiobooks +
     * works rows). Idempotent: a second run matches nothing, so repeated
     * starts are safe.
     */
    val storedTitleScrub: StoredTitleScrub by lazy {
        StoredTitleScrub(database.audiobookDao())
    }

    /** Single player manager; created lazily on first playback/service access. */
    val playerManager: AudioPlayerManager by lazy {
        // The player runs on the store; chapter materialisation (incl. the
        // 4read page fallback) stays on the catalog's chapter-fetch path.
        // ADR-0007: the fetcher yields chapter→track pairs (getPlayableChapters)
        // — the physical stream URLs live on the Source tracks.
        AudioPlayerManager(this, listeningState, sourceCatalog::getPlayableChapters)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // ADR-0002 (#138): cold start performs no network I/O during module
        // construction — the catalogue sync is an explicit composition-root
        // call, kicked off here (best-effort, never blocks startup).
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { sourceCatalog.fetchCatalogSections() }
        }
        // Spec-24 T1: scrub SEO title suffixes from rows stored before the
        // write-path rule existed (audiobooks + works). Best-effort and
        // idempotent — a failing or repeated pass never blocks startup.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { storedTitleScrub.scrubOnce() }
        }
    }

    companion object {
        /** Late-init singleton; safe because Application.onCreate runs before any component. */
        lateinit var instance: App
            private set
    }
}
