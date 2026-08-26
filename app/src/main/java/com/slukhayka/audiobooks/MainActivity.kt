package com.slukhayka.audiobooks

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.SelectedTab
import com.slukhayka.audiobooks.ui.components.MiniPlayerBar
import com.slukhayka.audiobooks.ui.screens.BookDetailScreen
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

// FragmentActivity (not plain ComponentActivity): spec-40 #276 (t2) —
// androidx.biometric's BiometricPrompt attaches to a FragmentActivity, and
// the recovery-code gate in ⚙️ Профіль needs it. Compose is unaffected.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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
    val showFullPlayer by viewModel.showFullPlayer.collectAsState()
    val playerState by viewModel.playerState.collectAsState()

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

    // Handle system back press
    BackHandler(enabled = showFullPlayer || selectedBookId != null ||
        selectedWebSource != null || selectedSeries != null || seriesIndexOpen || collectionsIndexOpen ||
        storageDestinationOpen || privacySettingsOpen || recommendationSettingsOpen || profileOpen || selectedGenre != null ||
        selectedTop100 || selectedPeopleKind != null || selectedPerson != null) {
        if (showFullPlayer) {
            viewModel.setShowFullPlayer(false)
        } else if (selectedWebSource != null) {
            viewModel.closeWebSource()
        } else if (selectedSeries != null) {
            // A series opened FROM the index keeps the index underneath:
            // back closes the series page first, then the index.
            viewModel.closeSeries()
        } else if (seriesIndexOpen) {
            viewModel.closeSeriesIndex()
        } else if (collectionsIndexOpen) {
            viewModel.closeCollectionsIndex()
        } else if (storageDestinationOpen) {
            viewModel.closeStorageDestination()
        } else if (privacySettingsOpen) {
            viewModel.closePrivacySettings()
        } else if (recommendationSettingsOpen) {
            viewModel.closeRecommendationSettings()
        } else if (profileOpen) {
            viewModel.closeProfileSettings()
        } else if (selectedGenre != null) {
            viewModel.closeGenre()
        } else if (selectedTop100) {
            viewModel.closeTop100()
        } else if (selectedPerson != null) {
            viewModel.closePersonBooks()
        } else if (selectedPeopleKind != null) {
            viewModel.closePeople()
        } else if (selectedBookId != null) {
            viewModel.selectBook(null)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                Column {
                    // Floating Persistent Mini Player
                    MiniPlayerBar(
                        playerState = playerState,
                        onPlayPauseClick = { viewModel.playerManager.togglePlayPause() },
                        onSkipNextClick = { viewModel.playerManager.nextChapter() },
                        onBarClick = { viewModel.setShowFullPlayer(true) }
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
                    // (sluhay.com first). Fullscreen pushed destination,
                    // debug-only (spec-15 T2): a release build routes the same
                    // action to the system browser in the ViewModel, so this
                    // surface is never reachable there.
                    BuildConfig.DEBUG && selectedWebSource != null -> WebSourceBrowserScreen(
                        viewModel = viewModel,
                        sourceId = selectedWebSource!!.sourceId,
                        homeUrl = selectedWebSource!!.homeUrl,
                        displayName = selectedWebSource!!.displayName,
                        onClose = { viewModel.closeWebSource() }
                    )

                    // Series (cycle) page (spec #8 ticket T8).
                    selectedSeries != null -> SeriesScreen(
                        viewModel = viewModel,
                        onBackClick = { viewModel.closeSeries() },
                        onBookClick = { id -> viewModel.selectBook(id) }
                    )

                    // spec-28 (#189): the «Серії» index — every series from
                    // the catalogue sections; tapping one pushes the existing
                    // series page on top of this index.
                    seriesIndexOpen -> SeriesIndexScreen(
                        viewModel = viewModel,
                        onBackClick = { viewModel.closeSeriesIndex() },
                        onSeriesClick = { series -> viewModel.openSeries(series.title, series.url) }
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
                        onBackClick = { viewModel.closeStorageDestination() }
                    )

                    // spec-38 T2 (#254): the «Приватність мережі» destination —
                    // the route choice, reached from the same ⋮ overflow menu.
                    privacySettingsOpen -> NetworkPrivacyScreen(
                        viewModel = viewModel,
                        onBackClick = { viewModel.closePrivacySettings() }
                    )

                    recommendationSettingsOpen -> RecommendationSettingsScreen(
                        viewModel = viewModel,
                        onBackClick = { viewModel.closeRecommendationSettings() }
                    )

                    // spec-40 #275 (t1): the «Профіль» destination — the
                    // silent listener identity's visible surface, reached
                    // from the same ⋮ overflow menu.
                    profileOpen -> ProfileScreen(
                        identity = viewModel.listenerIdentityModule,
                        onBackClick = { viewModel.closeProfileSettings() },
                        hiddenAuthors = hiddenAuthors,
                        onUnhideAuthor = { viewModel.unhideAuthor(it) },
                        progressSyncSettings = viewModel.progressSyncSettingsModule
                    )

                    // Genre (category) page ("Аудіокниги жанру:").
                    selectedGenre != null -> GenreScreen(
                        viewModel = viewModel,
                        onBackClick = { viewModel.closeGenre() },
                        onBookClick = { id -> viewModel.selectBook(id) }
                    )

                    // ТОП 100 АудіоКниг (`/top-100.html`).
                    selectedTop100 -> Top100Screen(
                        viewModel = viewModel,
                        onBackClick = { viewModel.closeTop100() },
                        onBookClick = { id -> viewModel.selectBook(id) }
                    )

                    // One person's books (opened from Виконавці/Автори index).
                    selectedPerson != null -> PersonBooksScreen(
                        viewModel = viewModel,
                        onBackClick = { viewModel.closePersonBooks() },
                        onBookClick = { id -> viewModel.selectBook(id) }
                    )

                    // Виконавці or Автори index.
                    selectedPeopleKind != null -> PeopleScreen(
                        viewModel = viewModel,
                        onBackClick = { viewModel.closePeople() },
                        onBookClick = { id -> viewModel.selectBook(id) },
                        onPersonClick = { person -> viewModel.openPersonBooks(person) }
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
                        onBackClick = { viewModel.selectBook(null) }
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
                            onBookClick = { id -> viewModel.selectBook(id) },
                            onPlayClick = { book ->
                                viewModel.playAudiobook(book)
                                viewModel.setShowFullPlayer(true)
                            },
                            onBrowseClick = { viewModel.selectTab(SelectedTab.EXPLORE) }
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
    AnimatedVisibility(
        visible = showFullPlayer,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })
    ) {
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
            icon = { Icon(imageVector = Icons.Default.Headphones, contentDescription = "Listen") },
            label = { Text("Слухати") },
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
            icon = { Icon(imageVector = Icons.Default.Explore, contentDescription = "Browse") },
            label = { Text("Огляд") },
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
            icon = { Icon(imageVector = Icons.Default.LibraryMusic, contentDescription = "Library") },
            label = { Text("Медіатека") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier.testTag("tab_library")
        )
    }
}
