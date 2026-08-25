package com.slukhayka.audiobooks

import android.app.Application
import android.util.Log
import androidx.room.withTransaction
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.collections.CollectionAssets
import com.slukhayka.audiobooks.data.collections.OpenLibraryTrendingSource
import com.slukhayka.audiobooks.data.collections.SluhayuaPopularSource
import com.slukhayka.audiobooks.data.collections.SoundBooksTopSource
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.downloads.OfflineDownloads
import com.slukhayka.audiobooks.data.duration.ChapterDurationProbe
import com.slukhayka.audiobooks.data.duration.DurationEnrichment
import com.slukhayka.audiobooks.data.duration.HttpStreamProber
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.data.imports.LibraryImport
import com.slukhayka.audiobooks.data.identity.FirebaseListenerIdentity
import com.slukhayka.audiobooks.data.identity.ListenerIdentity
import com.slukhayka.audiobooks.data.identity.LocalOnlyIdentity
import com.slukhayka.audiobooks.data.identity.SharedPreferencesLocalCredentialStore
import com.slukhayka.audiobooks.data.listening.ListeningStateStore
import com.slukhayka.audiobooks.data.metadata.FirestoreBookMetaStore
import com.slukhayka.audiobooks.data.metadata.CuratedCoverAssets
import com.slukhayka.audiobooks.data.metadata.CuratedCoverSeed
import com.slukhayka.audiobooks.data.metadata.LibraryCoverResolver
import com.slukhayka.audiobooks.data.metadata.SearchCoverResolver
import com.slukhayka.audiobooks.data.metadata.SearchDurationResolver
import com.slukhayka.audiobooks.data.metadata.StoredMetadataScrub
import com.slukhayka.audiobooks.data.reviews.FirestoreListenerReviewsStore
import com.slukhayka.audiobooks.data.recommend.RecommendationSettingsStore
import com.slukhayka.audiobooks.data.recommend.RecommendationPreferences
import com.slukhayka.audiobooks.data.search.FirestoreSearchCache
import com.slukhayka.audiobooks.data.merge.DuplicateWorkMerger
import com.slukhayka.audiobooks.data.privacy.BrowserIdentity
import com.slukhayka.audiobooks.data.privacy.PrivacySettingsStore
import com.slukhayka.audiobooks.data.privacy.SharedPreferencesPrivacySettingsStore
import com.slukhayka.audiobooks.data.privacy.TransportPrivacy
import com.slukhayka.audiobooks.data.source.AudiobookMp3Adapter
import com.slukhayka.audiobooks.data.source.FourReadAdapter
import com.slukhayka.audiobooks.data.source.LihtarAdapter
import com.slukhayka.audiobooks.data.source.SluhayAdapter
import com.slukhayka.audiobooks.data.source.SluhayuaAdapter
import com.slukhayka.audiobooks.data.source.SoundBooksAdapter
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.SourceAdapter

import com.slukhayka.audiobooks.data.update.SharedPreferencesUpdateCheckStore
import com.slukhayka.audiobooks.data.update.UpdateChecker
import com.slukhayka.audiobooks.data.universe.CuratedSeed
import com.slukhayka.audiobooks.data.universe.FirestoreUniverseStore
import com.slukhayka.audiobooks.data.universe.UniverseRefreshPass
import com.slukhayka.audiobooks.data.universe.MlKitTitleTranslator
import com.slukhayka.audiobooks.data.universe.SeriesUniverses
import com.slukhayka.audiobooks.data.universe.UniverseAssets
import com.slukhayka.audiobooks.data.universe.WikidataResponse
import com.slukhayka.audiobooks.data.universe.WikidataSeriesProvider
import com.slukhayka.audiobooks.player.AudioPlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Application-scoped dependency graph.
 *
 * ADR-0002 (#140): the five deep modules compose here — Listening State,
 * Library Import, Source Catalog, Offline Downloads, Library Entries — and
 * are shared by MainViewModel, PlaybackService and the widgets. The god
 * repository is gone; every caller composes these modules directly.
 *
 * Previously [com.slukhayka.audiobooks.ui.MainViewModel] constructed the repository and
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

    /** Spec-40 #281 — the local mute table's DAO, for the reviews' hide flow. */
    val audiobookDao: AudiobookDao get() = database.audiobookDao()

    /** #290 — local personalization controls; shared upload is not part of this graph. */
    val recommendationSettings: RecommendationSettingsStore by lazy {
        RecommendationSettingsStore(this)
    }
    val recommendationPreferences: RecommendationPreferences by lazy {
        RecommendationPreferences(audiobookDao, recommendationSettings)
    }

    /**
     * The ONE shared metadata store behind every consumer (imports, search
     * durations, search covers, the library cover pass) — a single Firestore
     * client wrapping the default app, or null without Firebase keys
     * (no google-services.json): every shared-cache consumer then degrades
     * to today's behaviour by contract.
     */
    private val sharedMetaStore: FirestoreBookMetaStore? by lazy {
        FirestoreBookMetaStore.create(this)
    }

    /**
     * Spec-40 #277 (#278, #280) — listener reviews of a Work over Firestore
     * (`book_reviews`, keyed `${workId}_${uid}`). Null without Firebase keys
     * (no google-services.json): the book page then simply shows no «Відгуки»
     * block — degrade-never by contract.
     */
    val listenerReviews: FirestoreListenerReviewsStore? by lazy {
        FirestoreListenerReviewsStore.create(this)
    }

    /** ADR-0002: one Listening State Store shared by the player and the ViewModel. */
    val listeningState: ListeningStateStore by lazy { ListeningStateStore(database.audiobookDao()) }

    /**
     * Spec-38 T2 (#254): the persisted privacy choice — the settings screen
     * reads and writes it, startup installs it into [TransportPrivacy]. The
     * store itself is dumb; the route decision lives behind the door.
     */
    val privacySettings: PrivacySettingsStore by lazy {
        SharedPreferencesPrivacySettingsStore(this)
    }

    /**
     * Spec-40 #275 (t1): the silent listener identity — bootstraps itself on
     * first launch (anonymous auth elevated with generated credentials) and
     * answers ⚙️ Профіль. Without Firebase keys (no google-services.json)
     * it degrades to a local-only profile, so ⚙️ Профіль works everywhere.
     */
    val listenerIdentity: ListenerIdentity by lazy {
        FirebaseListenerIdentity.create(this)
            ?: LocalOnlyIdentity(SharedPreferencesLocalCredentialStore(this))
    }

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
        LibraryImport(
            database.audiobookDao(),
            this,
            sourceAdapters,
            // Spec-26 T8 (#182): a new imported book whose series belongs to
            // a cached universe immediately re-validates that universe's
            // chain (cheap — one resolve) and spreads the update through the
            // shared base. Best-effort and silent — the import itself never
            // depends on it.
            onWorkImported = { workId -> runCatching { seriesUniverses.validateChainFor(workId) } },
            // Spec-32 T2/T3 (#232/#233): a resolved page writes its full
            // profile to the shared base (the next listener skips the page
            // fetch), and a card import reads a fresh profile back instead of
            // fetching. Null without Firebase keys: imports behave as before.
            profileStore = sharedMetaStore
        )
    }

    /** Source Catalog: browse/sync/search + chapter materialisation. */
    val sourceCatalog: SourceCatalog by lazy {
        // Spec-16: the curated smart-collection lists ride the context seam —
        // one JSON file per collection, loaded once at the composition root.
        // The live «Популярне зараз» list (OpenLibrary trending, keyless) and
        // the «Популярне у sluhay.com.ua» most-viewed list (spec-37) are
        // fetched over the shared HTTP transport on the union refresh.
        SourceCatalog(
            database.audiobookDao(),
            sourceAdapters,
            libraryImport,
            collectionLists = CollectionAssets.load(this),
            liveCollectionSources = listOf(
                OpenLibraryTrendingSource(),
                SluhayuaPopularSource(),
                SoundBooksTopSource()
            ),
            // Spec-30 T2 (#217): search cards resolve their duration through
            // the client-first precedence (local DB → shared cache, fill-the-
            // gap + mirror). Null without Firebase keys — search then behaves
            // exactly as before.
            durationResolver = SearchDurationResolver(
                database.audiobookDao(),
                sharedMetaStore
            ),
            // Spec-30 T3 (#218): search cards resolve their canonical cover
            // the same way — a locally known cover wins, the shared cache
            // fills the gap and mirrors hits into the local database.
            coverResolver = SearchCoverResolver(
                database.audiobookDao(),
                sharedMetaStore
            ),
            // Spec-33 T2 (#227): the shared search-result cache — a fresh
            // hit serves the merged result without touching the 4read /
            // sluhayua search endpoints; a miss or a stale entry resolves
            // live and writes back best-effort. Null without Firebase keys:
            // search then behaves exactly as before.
            searchCache = FirestoreSearchCache.create(this),
            // Catalogue crawls land each page's merge-on-write block as ONE
            // Room transaction — one invalidation of the endless feed's
            // PagingSource per page instead of two per row (row-by-row
            // invalidations starved every freshly-switched feed generation,
            // which read as «фільтри не працюють» while the catalogue synced).
            writeBatchRunner = { block ->
                database.withTransaction { block() }
            }
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
        // Spec-30 T4 (#219): a page-derived duration writes back to the
        // shared base so the next listener reads it instead of re-fetching.
        DurationEnrichment(database.audiobookDao(), fourRead::fetchBookPage, sharedStore = sharedMetaStore)
    }

    /**
     * spec-24 T8 (#169): the throttled background chapter-duration probing
     * pass. The chapter→track pairing rides the catalog seam
     * (getPlayableChapters — the player's own pairing); the transport is the
     * only network code (HEAD + ranged GET of the stream head).
     */
    val chapterDurationProbe: ChapterDurationProbe by lazy {
        // Spec-30 T4 (#219): a book total derived from probed chapters writes
        // back to the shared base — the costliest derivation must never repeat.
        ChapterDurationProbe(
            database.audiobookDao(),
            sourceCatalog::getPlayableChapters,
            HttpStreamProber(),
            sharedStore = sharedMetaStore
        )
    }

    /**
     * Spec-25 (#171/#173): the lazy series-universe resolution — the curated
     * universe assets first (offline-capable for the seeded universes), then
     * the Wikidata provider for unseeded series, behind the same seam. The
     * transport is the adapters' degrade-never-throw fetcher; any failure
     * contributes nothing.
     */
    val seriesUniverses: SeriesUniverses by lazy {
        val wikidataFetcher = HttpFetcher()
        SeriesUniverses(
            database.audiobookDao(),
            UniverseAssets.load(this),
            WikidataSeriesProvider(
                // spec-26 T3 (#177): the status-aware transport feeds the
                // 429 retry policy — a rate-limited response retries with
                // exponential backoff instead of silently losing the
                // resolution.
                fetch = { url ->
                    val (status, body) = wikidataFetcher.getTextResult(url)
                    WikidataResponse(status, body)
                },
                // spec-26 T1 (#175): on-device uk → ru/en title translation
                // (ML Kit, free, no key) for books whose only Wikidata
                // labels are ru/en — best-effort, silent on failure.
                translator = MlKitTitleTranslator()
            ),
            // Spec-26 T5: the shared Firestore read layer between the Room
            // cache and Wikidata — a resolution another user wrote back is
            // read here instead of paying for Wikidata. Null without Firebase
            // keys (no google-services.json): the app then behaves exactly as
            // before.
            sharedStore = FirestoreUniverseStore.create(this)
        )
    }

    /**
     * Spec-26 T7 (#181) — the background refresh pass over the tiered
     * membership schedule.
     */
    val universeRefreshPass: UniverseRefreshPass by lazy {
        UniverseRefreshPass(database.audiobookDao(), seriesUniverses)
    }

    /**
     * Spec-24 T1 + #264 — the one-time stored-metadata scrub runner (titles
     * on audiobooks + works rows, descriptions on audiobooks). Idempotent: a
     * second run matches nothing, so repeated starts are safe.
     */
    val storedMetadataScrub: StoredMetadataScrub by lazy {
        StoredMetadataScrub(database.audiobookDao())
    }

    /**
     * Spec-30 T3 (#218) — the library cover pass: fills the Медіатека rows
     * with NO local cover from the shared canonical base through the
     * existing cover write path. A startup one-shot (the library flow
     * re-emits on the write); the search resolver keeps it fresh afterwards.
     */
    val libraryCoverResolver: LibraryCoverResolver by lazy {
        LibraryCoverResolver(database.audiobookDao(), sharedMetaStore)
    }

    /**
     * Spec-27 (#184) BUG-002 — the one-time duplicate-Work merge runner.
     * Collapses library rows that share a hardened merge key (SEO-suffix
     * variants of one title), moving progress and bookmarks onto the
     * surviving card. Idempotent by construction and best-effort.
     */
    val duplicateWorkMerger: DuplicateWorkMerger by lazy {
        DuplicateWorkMerger(database.audiobookDao())
    }

    /**
     * Spec-36 T1 (#244): the app-release check — the shared GitHub Releases
     * API is the source of truth, the daily throttle lives inside the module,
     * and the Огляд screen reads its flow directly (ADR-0008).
     */
    val updateChecker: UpdateChecker by lazy {
        UpdateChecker(
            fetcher = HttpFetcher(),
            store = SharedPreferencesUpdateCheckStore(this),
            installedVersionName = BuildConfig.VERSION_NAME
        )
    }

    /** Single player manager; created lazily on first playback/service access. */
    val playerManager: AudioPlayerManager by lazy {
        // The player runs on the store; chapter materialisation (incl. the
        // 4read page fallback) stays on the catalog's chapter-fetch path.
        // ADR-0007: the fetcher yields chapter→track pairs (getPlayableChapters)
        // — the physical stream URLs live on the Source tracks.
        // Spec-32 T4 (#234): the self-healing seam — a 404/403 stream failure
        // re-resolves the book's source page and swaps the fresh URL in
        // (LibraryImport.refreshStreamUrl); the player then retries ONCE.
        AudioPlayerManager(
            this,
            listeningState,
            sourceCatalog::getPlayableChapters,
            streamUrlHealer = { bookId, chapterIndex, failedUrl ->
                libraryImport.refreshStreamUrl(bookId, chapterIndex, failedUrl)
            }
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Spec-38 T1 (#253): install the persisted privacy route BEFORE any
        // module can touch the network, and warm the real system WebView
        // User-Agent off the main thread (it initialises the WebView engine;
        // until it answers, requests carry the static fallback identity).
        TransportPrivacy.install(privacySettings.load())
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                android.webkit.WebSettings.getDefaultUserAgent(this@App)
            }.onSuccess { BrowserIdentity.reportSystemUserAgent(it) }
        }
        // Spec-30 T1 (#216): attach the App Check attestation provider BEFORE
        // any Firestore use, so every request to the shared metadata base
        // carries a token and the security rules accept the app's writes.
        installAppCheckIfConfigured()
        // ADR-0002 (#138): cold start performs no network I/O during module
        // construction — the catalogue sync is an explicit composition-root
        // call, kicked off here (best-effort, never blocks startup).
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { sourceCatalog.fetchCatalogSections() }
        }
        // Spec-24 T1: scrub SEO title suffixes from rows stored before the
        // write-path rule existed (audiobooks + works). Best-effort and
        // idempotent — a failing or repeated pass never blocks startup.
        // Spec-27 (#184) BUG-002: right after the scrub (so both copies of a
        // duplicate read clean), the one-time duplicate-Work merge collapses
        // rows sharing a hardened identity — one card per book, with progress
        // and bookmarks carried onto the survivor.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { storedMetadataScrub.scrubOnce() }
            runCatching { duplicateWorkMerger.mergeOnce() }
        }
        // Spec-26 T6 (#180): pour the curated universe asset into the shared
        // base (one document per curated series, idempotent — a re-seed on a
        // later launch writes the same documents). A no-op without Firebase
        // keys (FirestoreUniverseStore.create returns null) and silent on any
        // failure — the local curated asset works either way.
        CoroutineScope(Dispatchers.IO).launch {
            CuratedSeed.seed(
                FirestoreUniverseStore.create(this@App),
                UniverseAssets.load(this@App)
            )
        }
        // Spec-30 T3 (#218): pour the curated canonical covers into the
        // shared base (one document per Work mergeKey — the SAME key the
        // read paths use, so the app's own reads see the curated value;
        // idempotent by contract), then fill the coverless library rows
        // from the shared base through the existing cover write path.
        // Both are best-effort and silent — a no-op without Firebase keys.
        CoroutineScope(Dispatchers.IO).launch {
            CuratedCoverSeed.seed(
                sharedMetaStore,
                CuratedCoverAssets.load(this@App)
            )
            // resolve() is degrade-never by contract — no wrap needed.
            libraryCoverResolver.resolve()
        }
        // Spec-26 T7 (#181): the background universe-refresh pass — re-resolves
        // expired-tier memberships by priority (bounded per run, paced below
        // Wikidata's 429 window) so Wikidata changes reach every cache and the
        // shared base (T6 write-back) even for books no one opens. Best-effort
        // and silent; the loop itself never blocks startup.
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                runCatching { universeRefreshPass.runOnce() }
                delay(UNIVERSE_REFRESH_INTERVAL_MILLIS)
            }
        }
        // Spec-37: the daily catalogue-depth pass — the persisted Works layer
        // only ever grew from the homepage sections (~60 books), so the
        // «Весь каталог» endless feed ran dry after a few hundred rows even
        // though paging itself worked. Once per day (persisted throttle,
        // lazy after startup) the bounded 4read crawl walks the category and
        // series listings — pagination included, under the module's own page
        // budget — so depth accumulates run over run via merge-on-write.
        // Best-effort and silent; never blocks startup.
        CoroutineScope(Dispatchers.IO).launch {
            delay(CATALOG_HYDRATION_START_DELAY_MILLIS)
            val prefs = getSharedPreferences(PREFS_CATALOG_HYDRATION, MODE_PRIVATE)
            if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_HYDRATED_AT, 0L) <
                CATALOG_HYDRATION_INTERVAL_MILLIS
            ) {
                return@launch
            }
            runCatching { sourceCatalog.hydrateFourReadCatalog() }
            prefs.edit()
                .putLong(KEY_LAST_HYDRATED_AT, System.currentTimeMillis())
                .apply()
        }
        // Spec-36 T1 (#244): the daily app-release check — lazy (seconds after
        // startup), throttled to once per day inside the module, best-effort
        // and silent. The Огляд screen reads the module's flow directly.
        CoroutineScope(Dispatchers.IO).launch {
            delay(UPDATE_CHECK_START_DELAY_MILLIS)
            runCatching { updateChecker.checkNow() }
        }
        // Spec-40 #275 (t1): the silent listener identity bootstrap. First
        // launch creates the profile (anonymous auth + generated credentials),
        // every later launch is a no-op returning the same one. Best-effort
        // and silent by contract — ensure() never throws outward anyway.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { listenerIdentity.ensure() }
        }
    }

    /**
     * Spec-30 T1 (#216) — install the reCAPTCHA Enterprise App Check provider
     * on the default Firebase app when the app is configured for it
     * (google-services.json present AND its `app_check` section carries the
     * reCAPTCHA site key — [FirebaseOptions.getRecaptchaSiteKey], written by
     * the Firebase console when App Check is configured).
     *
     * Either way the call is a silent no-op: without Firebase there is no
     * Firestore at all; without the site key the provider factory would throw
     * on first use, so it is not installed — Firestore reads stay public and
     * the shared-cache writes degrade through the stores' existing
     * best-effort paths (denied by the rules, dropped, never a crash).
     */
    private fun installAppCheckIfConfigured() {
        val app = FirebaseApp.getApps(this).firstOrNull()
            ?: FirebaseApp.initializeApp(this)
            ?: return
        if (app.options.recaptchaSiteKey.isNullOrBlank()) {
            Log.w(
                "AppCheck",
                "No reCAPTCHA site key in google-services.json — App Check skipped; " +
                    "shared-cache writes will be denied by the Firestore rules (silent degrade)."
            )
            return
        }
        FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(
            RecaptchaAppCheckProviderFactory.getInstance()
        )
    }

    companion object {
        /** Late-init singleton; safe because Application.onCreate runs before any component. */
        lateinit var instance: App
            private set

        /** How often the background universe-refresh pass wakes (6 hours). */
        private const val UNIVERSE_REFRESH_INTERVAL_MILLIS: Long = 6L * 60 * 60 * 1000

        /** Spec-36 T1 (#244): the release check starts this many ms after launch. */
        private const val UPDATE_CHECK_START_DELAY_MILLIS: Long = 10_000L

        /** Spec-37: the daily catalogue-depth pass cadence and its lazy start. */
        private const val CATALOG_HYDRATION_INTERVAL_MILLIS: Long = 24L * 60 * 60 * 1000
        private const val CATALOG_HYDRATION_START_DELAY_MILLIS: Long = 20_000L
        private const val PREFS_CATALOG_HYDRATION = "catalog_hydration"
        private const val KEY_LAST_HYDRATED_AT = "last_hydrated_at"
    }
}
