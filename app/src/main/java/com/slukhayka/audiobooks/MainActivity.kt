package com.slukhayka.audiobooks

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.slukhayka.audiobooks.data.catalog.CatalogPerson
import com.slukhayka.audiobooks.data.diagnostics.AppVisibility
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.SelectedTab
import com.slukhayka.audiobooks.ui.components.MiniPlayerBar
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.screens.BookDetailScreen
import com.slukhayka.audiobooks.ui.screens.AuthorsIndexScreen
import com.slukhayka.audiobooks.ui.screens.CanonicalAuthorScreen
import com.slukhayka.audiobooks.ui.screens.BookDetailLinkOrigin
import com.slukhayka.audiobooks.ui.screens.CollectionsIndexScreen
import com.slukhayka.audiobooks.ui.screens.GenreScreen
import com.slukhayka.audiobooks.ui.screens.HomeScreen
import com.slukhayka.audiobooks.ui.screens.LibraryScreen
import com.slukhayka.audiobooks.ui.screens.ListenScreen
import com.slukhayka.audiobooks.ui.screens.NetworkPrivacyScreen
import com.slukhayka.audiobooks.ui.screens.PeopleScreen
import com.slukhayka.audiobooks.ui.screens.PersonBooksScreen
import com.slukhayka.audiobooks.ui.screens.PlayerScreen
import com.slukhayka.audiobooks.ui.screens.ProfileScreen
import com.slukhayka.audiobooks.ui.screens.RecommendationSettingsScreen
import com.slukhayka.audiobooks.ui.screens.SeriesIndexScreen
import com.slukhayka.audiobooks.ui.screens.SeriesScreen
import com.slukhayka.audiobooks.ui.screens.StorageDestinationScreen
import com.slukhayka.audiobooks.ui.screens.Top100Screen
import com.slukhayka.audiobooks.ui.screens.WebSourceBrowserScreen
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme

internal enum class SecondaryBookParent {
    SERIES,
    GENRE,
    TOP_100,
    PERSON
}

internal data class SecondaryBookRouteFrame(
    val parent: SecondaryBookParent? = null,
    val originBookId: String = "",
    val detailBookId: String = "",
    val parentTitle: String = "",
    val parentUrl: String = "",
    val parentName: String = "",
    val parentPath: String = "",
    val parentRole: PersonRole? = null
) {
    fun withSelectedDetail(selectedBookId: String?, parentActive: Boolean): SecondaryBookRouteFrame =
        if (parent != null && parentActive && selectedBookId != null) {
            copy(detailBookId = selectedBookId)
        } else {
            this
        }

    fun ownsDetail(selectedBookId: String?, parentActive: Boolean): Boolean =
        parent != null && parentActive && detailBookId == selectedBookId

    fun afterParentState(parentActive: Boolean, childRouteOpen: Boolean): SecondaryBookRouteFrame =
        if (parent != null && !parentActive && !childRouteOpen) {
            SecondaryBookRouteFrame()
        } else {
            this
        }
}

private val SecondaryBookRouteFrameSaver = listSaver<SecondaryBookRouteFrame, String>(
    save = { frame ->
        listOf(
            frame.parent?.name.orEmpty(),
            frame.originBookId,
            frame.detailBookId,
            frame.parentTitle,
            frame.parentUrl,
            frame.parentName,
            frame.parentPath,
            frame.parentRole?.name.orEmpty()
        )
    },
    restore = { values ->
        SecondaryBookRouteFrame(
            parent = values[0].takeIf(String::isNotEmpty)?.let(SecondaryBookParent::valueOf),
            originBookId = values[1],
            detailBookId = values[2],
            parentTitle = values[3],
            parentUrl = values[4],
            parentName = values[5],
            parentPath = values[6],
            parentRole = values.getOrNull(7)
                ?.takeIf(String::isNotEmpty)
                ?.let(PersonRole::valueOf)
        )
    }
)

// FragmentActivity (not plain ComponentActivity): spec-40 #276 (t2) —
// androidx.biometric's BiometricPrompt attaches to a FragmentActivity, and
// the recovery-code gate in ⚙️ Профіль needs it. Compose is unaffected.
class MainActivity : FragmentActivity() {
    internal var pendingBookId: String? = null

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleDownloadIntent(intent)
    }

    private fun handleDownloadIntent(intent: android.content.Intent) {
        if (intent.getBooleanExtra("openBookDetail", false)) {
            intent.getStringExtra("bookId")?.let { pendingBookId = it }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withUkrainianUiLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        handleDownloadIntent(intent)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Globally disable hardware bitmaps in Coil to prevent E/ashmem Pinning is deprecated errors
        // Spec-38 (#252): cover-image traffic is transport traffic too — it rides
        // the same browser identity (real WebView UA) and the same privacy route
        // as the shared fetcher.
        // Spec-38 T4 (#256): that one client is now the shared
        // [TransportClients.okHttp] — same pool, same route trampoline, and
        // domain names resolved through the DoH door, so cover lookups no
        // longer leak to the system resolver either.
        val imageLoader = coil.ImageLoader.Builder(this)
            .allowHardware(false)
            .okHttpClient {
                com.slukhayka.audiobooks.data.privacy.TransportClients.okHttp
            }
            .build()
        coil.Coil.setImageLoader(imageLoader)

        setContent {
            AudiobookTheme {
                AudiobookApp()
            }
        }
    }
}

@Composable
fun AudiobookApp(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val selectedTab by viewModel.selectedTab.collectAsState()
    val selectedBookId by viewModel.selectedBookId.collectAsState()
    var libraryBookFocusReturnId by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryOverflowFocusReturnPending by rememberSaveable { mutableStateOf(false) }
    var bookDetailChildOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    var bookDetailChildEditionId by rememberSaveable { mutableStateOf<String?>(null) }
    var bookDetailChildRouteOpen by rememberSaveable { mutableStateOf(false) }
    var peopleFocusReturnPath by rememberSaveable { mutableStateOf<String?>(null) }
    var seriesIndexFocusReturnUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var secondaryBookRoute by rememberSaveable(stateSaver = SecondaryBookRouteFrameSaver) {
        mutableStateOf(SecondaryBookRouteFrame())
    }
    val peopleListState = rememberLazyListState()
    val seriesIndexGridState = rememberLazyGridState()
    val seriesBookListState = rememberLazyListState()
    val genreBookListState = rememberLazyListState()
    val top100BookListState = rememberLazyListState()
    val personBookListState = rememberLazyListState()
    val showFullPlayer by viewModel.showFullPlayer.collectAsState()
    val fullPlayerTransition = updateTransition(
        targetState = showFullPlayer,
        label = "full player"
    )
    val fullPlayerModalActive = shouldHideAppBackgroundForFullPlayerTransition(
        currentState = fullPlayerTransition.currentState,
        targetState = fullPlayerTransition.targetState
    )
    var fullPlayerContentPresent by remember { mutableStateOf(false) }
    val playerState by viewModel.playerState.collectAsState()
    val crashReporting = App.instance.crashReporting
    val crashReportingState by crashReporting.state.collectAsState()
    var appVisibility by remember { mutableStateOf(AppVisibility.FOREGROUND) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        App.instance.crashContextTracker.updateAppVisibility(AppVisibility.FOREGROUND)
        val observer = LifecycleEventObserver { _, event ->
            appVisibility = when (event) {
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME -> AppVisibility.FOREGROUND
                Lifecycle.Event.ON_STOP -> AppVisibility.BACKGROUND
                else -> appVisibility
            }
            App.instance.crashContextTracker.updateAppVisibility(appVisibility)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (crashReportingState.shouldShowPrompt) {
        CrashReportConsentPrompt(
            onAllow = crashReporting::allowTriggeringReport,
            onDeny = crashReporting::denyTriggeringReport
        )
    }

    // Android 13+ requires a runtime POST_NOTIFICATIONS grant for the media
    // playback notification to be visible (background audio itself still works
    // without it, but the transport controls would be hidden).
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored: audio plays either way */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // #393: consume notification tap to open book detail
    LaunchedEffect(Unit) {
        val activity = context as? MainActivity
        val bookId = activity?.pendingBookId
        if (bookId != null) {
            activity.pendingBookId = null
            viewModel.selectBook(bookId)
        }
    }

    val selectedWebSource by viewModel.selectedWebSource.collectAsState()
    val hiddenAuthors by viewModel.hiddenAuthors.collectAsState()
    val selectedSeries by viewModel.selectedSeries.collectAsState()
    val seriesIndexOpen by viewModel.seriesIndexOpen.collectAsState()
    val collectionsIndexOpen by viewModel.collectionsIndexOpen.collectAsState()
    val storageDestinationOpen by viewModel.storageDestinationOpen.collectAsState()
    val privacySettingsOpen by viewModel.privacySettingsOpen.collectAsState()
    val recommendationSettingsOpen by viewModel.recommendationSettingsOpen.collectAsState()
    val profileOpen by viewModel.profileOpen.collectAsState()
    val selectedGenre by viewModel.selectedGenre.collectAsState()
    val selectedTop100 by viewModel.selectedTop100.collectAsState()
    val selectedPeopleKind by viewModel.selectedPeopleKind.collectAsState()
    val selectedPerson by viewModel.selectedPerson.collectAsState()
    val authorsIndexOpen by viewModel.authorsIndexOpen.collectAsState()
    val authorsIndexResults by viewModel.authorsIndexResults.collectAsState()
    val selectedCanonicalAuthor by viewModel.selectedCanonicalAuthor.collectAsState()
    val canonicalAuthorWorks by viewModel.canonicalAuthorWorks.collectAsState()
    val isCanonicalAuthorLoading by viewModel.isCanonicalAuthorLoading.collectAsState()
    val canonicalAuthorLoadFailed by viewModel.canonicalAuthorLoadFailed.collectAsState()
    val secondaryBookParentActive = when (secondaryBookRoute.parent) {
        SecondaryBookParent.SERIES -> selectedSeries != null
        SecondaryBookParent.GENRE -> selectedGenre != null
        SecondaryBookParent.TOP_100 -> selectedTop100
        SecondaryBookParent.PERSON -> selectedPerson != null
        null -> false
    }
    val effectiveSecondaryBookRoute = secondaryBookRoute.withSelectedDetail(
        selectedBookId = selectedBookId,
        parentActive = secondaryBookParentActive
    )
    val secondaryBookDetailOpen = effectiveSecondaryBookRoute.ownsDetail(
        selectedBookId = selectedBookId,
        parentActive = secondaryBookParentActive
    )
    LaunchedEffect(selectedBookId, secondaryBookParentActive) {
        if (effectiveSecondaryBookRoute != secondaryBookRoute) {
            secondaryBookRoute = effectiveSecondaryBookRoute
        } else {
            val retainedRoute = secondaryBookRoute.afterParentState(
                parentActive = secondaryBookParentActive,
                childRouteOpen = bookDetailChildRouteOpen
            )
            if (retainedRoute != secondaryBookRoute) {
                secondaryBookRoute = retainedRoute
            }
        }
    }
    val bookDetailChildFocusOrigin = bookDetailChildOrigin?.let { origin ->
        runCatching { BookDetailLinkOrigin.valueOf(origin) }.getOrNull()
    }

    fun closeBookDetailChildRoute() {
        bookDetailChildRouteOpen = false
        when (bookDetailChildFocusOrigin) {
            BookDetailLinkOrigin.SERIES -> {
                viewModel.closeSeries()
                if (secondaryBookRoute.parent == SecondaryBookParent.SERIES) {
                    if (secondaryBookRoute.parentTitle.isNotEmpty() &&
                        secondaryBookRoute.parentUrl.isNotEmpty()
                    ) {
                        viewModel.openSeries(
                            secondaryBookRoute.parentTitle,
                            secondaryBookRoute.parentUrl
                        )
                    }
                }
            }
            BookDetailLinkOrigin.AUTHOR,
            BookDetailLinkOrigin.NARRATOR -> {
                viewModel.closePersonBooks()
                if (secondaryBookRoute.parent == SecondaryBookParent.PERSON) {
                    if (secondaryBookRoute.parentName.isNotEmpty() &&
                        secondaryBookRoute.parentPath.isNotEmpty() &&
                        secondaryBookRoute.parentRole != null
                    ) {
                        viewModel.openPersonBooks(
                            CatalogPerson(
                                secondaryBookRoute.parentName,
                                secondaryBookRoute.parentPath,
                                0,
                                requireNotNull(secondaryBookRoute.parentRole)
                            )
                        )
                    }
                }
            }
            null -> Unit
        }
    }

    // Handle system back press
    BackHandler(enabled = showFullPlayer || selectedBookId != null ||
        selectedWebSource != null || selectedSeries != null || seriesIndexOpen || collectionsIndexOpen ||
        storageDestinationOpen || privacySettingsOpen || recommendationSettingsOpen || profileOpen || selectedGenre != null ||
        selectedTop100 || selectedPeopleKind != null || selectedPerson != null ||
        authorsIndexOpen || selectedCanonicalAuthor != null) {
        if (showFullPlayer) {
            viewModel.setShowFullPlayer(false)
        } else if (selectedWebSource != null) {
            viewModel.closeWebSource()
        } else if (bookDetailChildRouteOpen) {
            closeBookDetailChildRoute()
        } else if (secondaryBookDetailOpen) {
            bookDetailChildRouteOpen = false
            bookDetailChildOrigin = null
            bookDetailChildEditionId = null
            viewModel.selectBook(null)
        } else if (selectedSeries != null) {
            // A series opened FROM the index keeps the index underneath:
            // back closes the series page first, then the index.
            secondaryBookRoute = SecondaryBookRouteFrame()
            viewModel.closeSeries()
        } else if (seriesIndexOpen) {
            viewModel.closeSeriesIndex()
        } else if (collectionsIndexOpen) {
            viewModel.closeCollectionsIndex()
        } else if (storageDestinationOpen) {
            libraryOverflowFocusReturnPending = true
            viewModel.closeStorageDestination()
        } else if (privacySettingsOpen) {
            libraryOverflowFocusReturnPending = true
            viewModel.closePrivacySettings()
        } else if (recommendationSettingsOpen) {
            libraryOverflowFocusReturnPending = true
            viewModel.closeRecommendationSettings()
        } else if (profileOpen) {
            libraryOverflowFocusReturnPending = true
            viewModel.closeProfileSettings()
        } else if (selectedGenre != null) {
            secondaryBookRoute = SecondaryBookRouteFrame()
            viewModel.closeGenre()
        } else if (selectedTop100) {
            secondaryBookRoute = SecondaryBookRouteFrame()
            viewModel.closeTop100()
        } else if (selectedCanonicalAuthor != null) {
            viewModel.closeCanonicalAuthor()
        } else if (authorsIndexOpen) {
            viewModel.closeAuthorsIndex()
        } else if (selectedPerson != null) {
            secondaryBookRoute = SecondaryBookRouteFrame()
            viewModel.closePersonBooks()
        } else if (selectedPeopleKind != null) {
            viewModel.closePeople()
        } else if (selectedBookId != null) {
            viewModel.selectBook(null)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .testTag("app_background")
                .accessibilityModalBackground(
                    modalVisible = fullPlayerModalActive || crashReportingState.shouldShowPrompt,
                    // BookDetailScreen restores the exact chapter after the
                    // player closes, so keep the root restorer out of that modal
                    // handoff. Outside the player it remains responsible for
                    // route-level return focus (for example, detail → library).
                    automaticFocusRestoration = !fullPlayerModalActive
                ),
            bottomBar = {
                Column {
                    // Floating Persistent Mini Player
                    MiniPlayerBar(
                        playerState = playerState,
                        onPlayPauseClick = { viewModel.playerManager.togglePlayPause() },
                        onSkipNextClick = { viewModel.playerManager.nextChapter() },
                        onBarClick = { viewModel.setShowFullPlayer(true) },
                        // ADR-0024 (#362): ready when this device can cast and
                        // the current chapter carries a stream Source.
                        castReady = runCatching {
                            App.instance.castController.isCastAvailable()
                        }.getOrDefault(false) && playerState.currentStreamUrl.isNotEmpty()
                    )

                    // Navigation Bar (spec #8 T4: exactly Explore · Library).
                    AppBottomBar(
                        selectedTab = selectedTab,
                        bookDetailOpen = selectedBookId != null,
                        onSelect = { tab ->
                            viewModel.selectBook(null)
                            viewModel.selectTab(tab)
                        }
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when {
                    // Spec-13 T3: a WebView-pattern source's browser surface
                    // (4read + sluhay). Fullscreen pushed destination; 4read
                    // remains available in release for session recovery.
                    selectedWebSource != null -> WebSourceBrowserScreen(
                        viewModel = viewModel,
                        sourceId = selectedWebSource!!.sourceId,
                        homeUrl = selectedWebSource!!.homeUrl,
                        displayName = selectedWebSource!!.displayName,
                        recoveryBookId = selectedWebSource!!.recoveryBookId,
                        recoveryChapterIndex = selectedWebSource!!.recoveryChapterIndex,
                        recoveryPositionMs = selectedWebSource!!.recoveryPositionMs,
                        onClose = { viewModel.closeWebSource() }
                    )

                    bookDetailChildRouteOpen &&
                        bookDetailChildFocusOrigin == BookDetailLinkOrigin.SERIES &&
                        selectedSeries != null -> SeriesScreen(
                        viewModel = viewModel,
                        onBackClick = { closeBookDetailChildRoute() },
                        onBookClick = { id ->
                            val childSeries = selectedSeries
                            closeBookDetailChildRoute()
                            bookDetailChildOrigin = null
                            bookDetailChildEditionId = null
                            if (childSeries != null) {
                                viewModel.openSeries(childSeries.title, childSeries.url)
                                secondaryBookRoute = SecondaryBookRouteFrame(
                                    parent = SecondaryBookParent.SERIES,
                                    originBookId = id,
                                    detailBookId = id,
                                    parentTitle = childSeries.title,
                                    parentUrl = childSeries.url
                                )
                            }
                            viewModel.selectBook(id)
                        }
                    )

                    bookDetailChildRouteOpen &&
                        bookDetailChildFocusOrigin in setOf(
                            BookDetailLinkOrigin.AUTHOR,
                            BookDetailLinkOrigin.NARRATOR
                        ) && selectedPerson != null -> PersonBooksScreen(
                        viewModel = viewModel,
                        personBookmarks = viewModel.personBookmarks,
                        onBackClick = { closeBookDetailChildRoute() },
                        onBookClick = { id ->
                            val childPerson = selectedPerson
                            closeBookDetailChildRoute()
                            bookDetailChildOrigin = null
                            bookDetailChildEditionId = null
                            if (childPerson != null) {
                                viewModel.openPersonBooks(
                                    CatalogPerson(
                                        childPerson.name,
                                        childPerson.path,
                                        0,
                                        childPerson.role
                                    )
                                )
                                secondaryBookRoute = SecondaryBookRouteFrame(
                                    parent = SecondaryBookParent.PERSON,
                                    originBookId = id,
                                    detailBookId = id,
                                    parentName = childPerson.name,
                                    parentPath = childPerson.path,
                                    parentRole = childPerson.role
                                )
                            }
                            viewModel.selectBook(id)
                        }
                    )

                    // A book opened from a pushed catalogue list overlays
                    // that retained parent route. Closing details reveals the
                    // same list and lets it restore the originating card.
                    secondaryBookDetailOpen -> BookDetailScreen(
                        viewModel = viewModel,
                        listeningState = viewModel.listeningState,
                        offlineDownloads = viewModel.offlineDownloads,
                        libraryEntries = viewModel.libraryEntries,
                        playerModalVisible = fullPlayerContentPresent || fullPlayerModalActive,
                        personBookmarks = viewModel.personBookmarks,
                        onBackClick = {
                            bookDetailChildRouteOpen = false
                            bookDetailChildOrigin = null
                            bookDetailChildEditionId = null
                            viewModel.selectBook(null)
                        },
                        returnFocusOrigin = bookDetailChildFocusOrigin
                            ?.takeIf { bookDetailChildEditionId == selectedBookId },
                        fullPlayerModalActive = fullPlayerModalActive,
                        onChildRouteOpened = { origin ->
                            bookDetailChildRouteOpen = true
                            bookDetailChildOrigin = origin.name
                            bookDetailChildEditionId = selectedBookId
                        },
                        onReturnFocusRestored = { origin ->
                            if (bookDetailChildOrigin == origin.name) {
                                bookDetailChildOrigin = null
                                bookDetailChildEditionId = null
                            }
                        }
                    )

                    // Series (cycle) page (spec #8 ticket T8).
                    selectedSeries != null -> SeriesScreen(
                        viewModel = viewModel,
                        onBackClick = {
                            secondaryBookRoute = SecondaryBookRouteFrame()
                            viewModel.closeSeries()
                        },
                        onBookClick = { id ->
                            secondaryBookRoute = SecondaryBookRouteFrame(
                                parent = SecondaryBookParent.SERIES,
                                originBookId = id,
                                detailBookId = id,
                                parentTitle = selectedSeries?.title.orEmpty(),
                                parentUrl = selectedSeries?.url.orEmpty()
                            )
                            viewModel.selectBook(id)
                        },
                        restoreFocusBookId = secondaryBookRoute.originBookId.takeIf {
                            secondaryBookRoute.parent == SecondaryBookParent.SERIES
                        },
                        onBookFocusRestored = { restoredId ->
                            if (secondaryBookRoute.parent == SecondaryBookParent.SERIES &&
                                secondaryBookRoute.originBookId == restoredId
                            ) {
                                secondaryBookRoute = SecondaryBookRouteFrame()
                            }
                        },
                        listState = seriesBookListState
                    )

                    // spec-28 (#189): the «Серії» index — every series from
                    // the catalogue sections; tapping one pushes the existing
                    // series page on top of this index.
                    seriesIndexOpen -> SeriesIndexScreen(
                        viewModel = viewModel,
                        onBackClick = { viewModel.closeSeriesIndex() },
                        onSeriesClick = { series ->
                            seriesIndexFocusReturnUrl = series.url
                            viewModel.openSeries(series.title, series.url)
                        },
                        restoreFocusSeriesUrl = seriesIndexFocusReturnUrl,
                        onSeriesFocusRestored = { restoredUrl ->
                            if (seriesIndexFocusReturnUrl == restoredUrl) {
                                seriesIndexFocusReturnUrl = null
                            }
                        },
                        gridState = seriesIndexGridState
                    )

                    // spec-28 (#190): the «Колекції» index — every matched
                    // smart collection with its books; tapping a book
                    // resolves-and-plays it (same as the inline cards).
                    collectionsIndexOpen -> CollectionsIndexScreen(
                        viewModel = viewModel,
                        onBackClick = { viewModel.closeCollectionsIndex() },
                        onBookClick = { result -> viewModel.openGlobalSearchResult(result) }
                    )

                    // spec-28 (#194): the «Завантаження та пам'ять»
                    // destination — the storage line and the destructive
                    // delete, reached from the Медіатека ⋮ overflow menu.
                    storageDestinationOpen -> StorageDestinationScreen(
                        viewModel = viewModel,
                        onBackClick = {
                            libraryOverflowFocusReturnPending = true
                            viewModel.closeStorageDestination()
                        }
                    )

                    // spec-38 T2 (#254): the «Приватність мережі» destination —
                    // the route choice, reached from the same ⋮ overflow menu.
                    privacySettingsOpen -> NetworkPrivacyScreen(
                        viewModel = viewModel,
                        onBackClick = {
                            libraryOverflowFocusReturnPending = true
                            viewModel.closePrivacySettings()
                        }
                    )

                    recommendationSettingsOpen -> RecommendationSettingsScreen(
                        viewModel = viewModel,
                        onBackClick = {
                            libraryOverflowFocusReturnPending = true
                            viewModel.closeRecommendationSettings()
                        }
                    )

                    // spec-40 #275 (t1): the «Профіль» destination — the
                    // silent listener identity's visible surface, reached
                    // from the same ⋮ overflow menu.
                    profileOpen -> ProfileScreen(
                        identity = viewModel.listenerIdentityModule,
                        onBackClick = {
                            libraryOverflowFocusReturnPending = true
                            viewModel.closeProfileSettings()
                        },
                        hiddenAuthors = hiddenAuthors,
                        onUnhideAuthor = { viewModel.unhideAuthor(it) },
                        progressSyncSettings = viewModel.progressSyncSettingsModule,
                        crashReporting = App.instance.crashReporting
                    )

                    // Genre (category) page ("Аудіокниги жанру:").
                    selectedGenre != null -> GenreScreen(
                        viewModel = viewModel,
                        onBackClick = {
                            secondaryBookRoute = SecondaryBookRouteFrame()
                            viewModel.closeGenre()
                        },
                        onBookClick = { id ->
                            secondaryBookRoute = SecondaryBookRouteFrame(
                                parent = SecondaryBookParent.GENRE,
                                originBookId = id,
                                detailBookId = id
                            )
                            viewModel.selectBook(id)
                        },
                        restoreFocusBookId = secondaryBookRoute.originBookId.takeIf {
                            secondaryBookRoute.parent == SecondaryBookParent.GENRE
                        },
                        onBookFocusRestored = { restoredId ->
                            if (secondaryBookRoute.parent == SecondaryBookParent.GENRE &&
                                secondaryBookRoute.originBookId == restoredId
                            ) {
                                secondaryBookRoute = SecondaryBookRouteFrame()
                            }
                        },
                        listState = genreBookListState
                    )

                    // ТОП 100 АудіоКниг (`/top-100.html`).
                    selectedTop100 -> Top100Screen(
                        viewModel = viewModel,
                        onBackClick = {
                            secondaryBookRoute = SecondaryBookRouteFrame()
                            viewModel.closeTop100()
                        },
                        onBookClick = { id ->
                            secondaryBookRoute = SecondaryBookRouteFrame(
                                parent = SecondaryBookParent.TOP_100,
                                originBookId = id,
                                detailBookId = id
                            )
                            viewModel.selectBook(id)
                        },
                        restoreFocusBookId = secondaryBookRoute.originBookId.takeIf {
                            secondaryBookRoute.parent == SecondaryBookParent.TOP_100
                        },
                        onBookFocusRestored = { restoredId ->
                            if (secondaryBookRoute.parent == SecondaryBookParent.TOP_100 &&
                                secondaryBookRoute.originBookId == restoredId
                            ) {
                                secondaryBookRoute = SecondaryBookRouteFrame()
                            }
                        },
                        listState = top100BookListState
                    )

                    selectedCanonicalAuthor != null -> CanonicalAuthorScreen(
                        author = selectedCanonicalAuthor!!,
                        works = canonicalAuthorWorks,
                        isLoading = isCanonicalAuthorLoading,
                        loadFailed = canonicalAuthorLoadFailed,
                        onBackClick = { viewModel.closeCanonicalAuthor() },
                        onWorkClick = viewModel::openCanonicalAuthorWork,
                        personBookmarks = viewModel.personBookmarks
                    )

                    authorsIndexOpen -> {
                        // The full 10k-capable alphabetical projection is cold:
                        // collect it only while its destination is visible.
                        val canonicalAuthors by viewModel.sourceCatalog.authors.collectAsState(initial = emptyList())
                        val authorList = authorsIndexResults ?: canonicalAuthors
                        AuthorsIndexScreen(
                            authors = authorList,
                            onBackClick = { viewModel.closeAuthorsIndex() },
                            onAuthorClick = { author ->
                                val idx = authorList.indexOfFirst { it.id == author.id }
                                    .coerceAtLeast(0)
                                viewModel.openCanonicalAuthor(author, idx)
                            },
                            initialScrollIndex = viewModel.authorsIndexScrollIndex.collectAsState().value
                        )
                    }

                    // One person's books (opened from Виконавці/Автори index).
                    selectedPerson != null -> PersonBooksScreen(
                        viewModel = viewModel,
                        personBookmarks = viewModel.personBookmarks,
                        onBackClick = {
                            secondaryBookRoute = SecondaryBookRouteFrame()
                            viewModel.closePersonBooks()
                        },
                        onBookClick = { id ->
                            secondaryBookRoute = SecondaryBookRouteFrame(
                                parent = SecondaryBookParent.PERSON,
                                originBookId = id,
                                detailBookId = id,
                                parentName = selectedPerson?.name.orEmpty(),
                                parentPath = selectedPerson?.path.orEmpty(),
                                parentRole = selectedPerson?.role
                            )
                            viewModel.selectBook(id)
                        },
                        restoreFocusBookId = secondaryBookRoute.originBookId.takeIf {
                            secondaryBookRoute.parent == SecondaryBookParent.PERSON
                        },
                        onBookFocusRestored = { restoredId ->
                            if (secondaryBookRoute.parent == SecondaryBookParent.PERSON &&
                                secondaryBookRoute.originBookId == restoredId
                            ) {
                                secondaryBookRoute = SecondaryBookRouteFrame()
                            }
                        },
                        listState = personBookListState
                    )

                    // Виконавці or Автори index.
                    selectedPeopleKind != null -> PeopleScreen(
                        viewModel = viewModel,
                        onBackClick = { viewModel.closePeople() },
                        onBookClick = { id -> viewModel.selectBook(id) },
                        onPersonClick = { person ->
                            peopleFocusReturnPath = person.path
                            viewModel.openPersonBooks(person)
                        },
                        restoreFocusPersonPath = peopleFocusReturnPath,
                        onPersonFocusRestored = { restoredPath ->
                            if (peopleFocusReturnPath == restoredPath) {
                                peopleFocusReturnPath = null
                            }
                        },
                        listState = peopleListState
                    )

                    selectedBookId != null -> BookDetailScreen(
                        viewModel = viewModel,
                        // ADR-0008 batch 4 (#159): the modules come in as
                        // parameters from the composition root.
                        listeningState = viewModel.listeningState,
                        offlineDownloads = viewModel.offlineDownloads,
                        // ADR-0011: the «Інші начитки» block reads the Work's
                        // other rendition cards from the module.
                        libraryEntries = viewModel.libraryEntries,
                        playerModalVisible = fullPlayerContentPresent || fullPlayerModalActive,
                        personBookmarks = viewModel.personBookmarks,
                        onBackClick = {
                            bookDetailChildRouteOpen = false
                            bookDetailChildOrigin = null
                            bookDetailChildEditionId = null
                            viewModel.selectBook(null)
                        },
                        returnFocusOrigin = bookDetailChildFocusOrigin
                            ?.takeIf { bookDetailChildEditionId == selectedBookId },
                        fullPlayerModalActive = fullPlayerModalActive,
                        onChildRouteOpened = { origin ->
                            bookDetailChildRouteOpen = true
                            bookDetailChildOrigin = origin.name
                            bookDetailChildEditionId = selectedBookId
                        },
                        onReturnFocusRestored = { origin ->
                            if (bookDetailChildOrigin == origin.name) {
                                bookDetailChildOrigin = null
                                bookDetailChildEditionId = null
                            }
                        }
                    )

                    else -> when (selectedTab) {
                        // Spec-9: first tab is the listening panel, not the storefront.
                        SelectedTab.LISTEN -> ListenScreen(
                            viewModel = viewModel,
                            // ADR-0008 batch 3 (#158): the modules come in as
                            // parameters from the composition root. spec-28
                            // (#192): discovery left the tab, so only the
                            // library module is read here.
                            libraryEntries = viewModel.libraryEntries,
                            onBookClick = { id -> viewModel.selectBook(id) },
                            onPlayClick = { book ->
                                viewModel.playAudiobook(book)
                                viewModel.setShowFullPlayer(true)
                            },
                            onBrowseClick = { viewModel.selectTab(SelectedTab.EXPLORE) },
                            onImportClick = { viewModel.selectTab(SelectedTab.LIBRARY) }
                        )
                        SelectedTab.EXPLORE ->                        HomeScreen(
                            durationEnrichment = viewModel.durationEnrichment,
                            chapterDurationProbe = viewModel.chapterDurationProbe,
                            updateChecker = viewModel.updateChecker,
                            viewModel = viewModel,
                            // ADR-0008 batches 2 + contract (#156, #160): the
                            // modules come in as parameters from the
                            // composition root.
                            libraryEntries = viewModel.libraryEntries,
                            sourceCatalog = viewModel.sourceCatalog,
                            onBookClick = { id -> viewModel.selectBook(id) },
                            onPlayClick = { book ->
                                viewModel.playAudiobook(book)
                                viewModel.setShowFullPlayer(true)
                            },
                            // Spec-13 T3 + spec-15 T2: the «Більше книг на
                            // Sluhay» exit CTA (spec-28 #192, moved from
                            // Listen) renders only in debug builds — in
                            // release the same row would open an in-app
                            // browser that cannot exist.
                            onOpenWebSource = if (BuildConfig.DEBUG) {
                                {
                                    viewModel.openWebSource(
                                        sourceId = "sluhay",
                                        homeUrl = "https://sluhay.com/",
                                        displayName = "Sluhay"
                                    )
                                }
                            } else {
                                null
                            }
                        )
                        SelectedTab.LIBRARY -> LibraryScreen(
                            viewModel = viewModel,
                            // ADR-0008 batch 1 (#154): the screen receives the
                            // modules it reads from as parameters, wired here
                            // from the single adapter (the ViewModel's public
                            // module fields — the playerManager precedent).
                            libraryEntries = viewModel.libraryEntries,
                            listeningState = viewModel.listeningState,
                            onBookClick = { id ->
                                libraryBookFocusReturnId = id
                                viewModel.selectBook(id)
                            },
                            onPlayClick = { book ->
                                viewModel.playAudiobook(book)
                                viewModel.setShowFullPlayer(true)
                            },
                            onBrowseClick = { viewModel.selectTab(SelectedTab.EXPLORE) },
                            restoreFocusBookId = libraryBookFocusReturnId,
                            onBookFocusRestored = { restoredId ->
                                if (libraryBookFocusReturnId == restoredId) {
                                    libraryBookFocusReturnId = null
                                }
                            },
                            restoreOverflowFocus = libraryOverflowFocusReturnPending,
                            onOverflowFocusRestored = {
                                libraryOverflowFocusReturnPending = false
                            }
                        )
                        else ->                        HomeScreen(
                            durationEnrichment = viewModel.durationEnrichment,
                            chapterDurationProbe = viewModel.chapterDurationProbe,
                            updateChecker = viewModel.updateChecker,
                            viewModel = viewModel,
                            // ADR-0008 batches 2 + contract (#156, #160): the
                            // modules come in as parameters from the
                            // composition root.
                            libraryEntries = viewModel.libraryEntries,
                            sourceCatalog = viewModel.sourceCatalog,
                            onBookClick = { id -> viewModel.selectBook(id) },
                            onPlayClick = { book ->
                                viewModel.playAudiobook(book)
                                viewModel.setShowFullPlayer(true)
                            }
                        )
                    }
                }
            }
        }

    // Full Screen Player Overlay
    fullPlayerTransition.AnimatedVisibility(
        visible = { it },
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = Modifier.accessibilityPane(stringResource(R.string.pane_player))
    ) {
        DisposableEffect(Unit) {
            fullPlayerContentPresent = true
            onDispose { fullPlayerContentPresent = false }
        }
        PlayerScreen(
            viewModel = viewModel,
            // ADR-0008 batch 4 (#159): the module comes in as a parameter from
            // the composition root.
            libraryEntries = viewModel.libraryEntries,
            onDismiss = { viewModel.setShowFullPlayer(false) }
        )
    }
    }
}

@Composable
internal fun CrashReportConsentPrompt(
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(R.string.crash_reports_prompt_title)) },
        text = { Text(stringResource(R.string.crash_reports_prompt_body)) },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(stringResource(R.string.crash_reports_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text(stringResource(R.string.crash_reports_deny))
            }
        }
    )
}

internal fun shouldHideAppBackgroundForFullPlayerTransition(
    currentState: Boolean,
    targetState: Boolean
): Boolean = currentState || targetState

/**
 * Bottom navigation bar. Extracted from [AudiobookApp] so the spec #8 T4
 * acceptance (exactly two tabs, no WebView/Bookmarks tabs) is unit-testable
 * without dragging in the whole app.
 */
@Composable
fun AppBottomBar(
    selectedTab: SelectedTab,
    bookDetailOpen: Boolean = false,
    onSelect: (SelectedTab) -> Unit
) {
    NavigationBar(
        // MD3: the navigation bar is a tonal container (surfaceContainer),
        // one step above the screen surface.
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .testTag("bottom_navigation_bar")
    ) {
        // Spec-9: exactly Слухати · Огляд · Медіатека (the listening panel
        // first). Test tags stay stable for the screens that moved (tab_explore
        // / tab_library) so existing UI tests keep working.
        NavigationBarItem(
            selected = selectedTab == SelectedTab.LISTEN && !bookDetailOpen,
            onClick = { onSelect(SelectedTab.LISTEN) },
            icon = { Icon(imageVector = Icons.Default.Headphones, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_listen)) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier.testTag("tab_listen")
        )

        NavigationBarItem(
            selected = selectedTab == SelectedTab.EXPLORE && !bookDetailOpen,
            onClick = { onSelect(SelectedTab.EXPLORE) },
            icon = { Icon(imageVector = Icons.Default.Explore, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_explore)) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier.testTag("tab_explore")
        )

        NavigationBarItem(
            selected = selectedTab == SelectedTab.LIBRARY && !bookDetailOpen,
            onClick = { onSelect(SelectedTab.LIBRARY) },
            icon = { Icon(imageVector = Icons.Default.LibraryMusic, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_library)) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier.testTag("tab_library")
        )
    }
}
