package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.MainViewModel
import com.example.ui.SelectedTab
import com.example.ui.components.MiniPlayerBar
import com.example.ui.screens.BookDetailScreen
import com.example.ui.screens.GenreScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.ListenScreen
import com.example.ui.screens.PeopleScreen
import com.example.ui.screens.PersonBooksScreen
import com.example.ui.screens.PlayerScreen
import com.example.ui.screens.SeriesScreen
import com.example.ui.screens.Top100Screen
import com.example.ui.screens.WebSourceBrowserScreen
import com.example.ui.theme.AudiobookTheme
import com.example.widget.WidgetIntents

class MainActivity : ComponentActivity() {

    // Spec-17 (#110): the widget's book tap reuses this existing surface — the
    // activity-level VM lets us honor the widget extra outside composition.
    private val appViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleWidgetOpenPlayerIntent(intent)

        // Globally disable hardware bitmaps in Coil to prevent E/ashmem Pinning is deprecated errors
        val imageLoader = coil.ImageLoader.Builder(this)
            .allowHardware(false)
            .build()
        coil.Coil.setImageLoader(imageLoader)

        setContent {
            AudiobookTheme {
                AudiobookApp(viewModel = appViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWidgetOpenPlayerIntent(intent)
    }

    /** Spec-17 (#110): the widget's book tap opens the full player on top. */
    private fun handleWidgetOpenPlayerIntent(intent: Intent) {
        if (intent.getBooleanExtra(WidgetIntents.EXTRA_OPEN_PLAYER, false)) {
            appViewModel.setShowFullPlayer(true)
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
    val selectedSeries by viewModel.selectedSeries.collectAsState()
    val selectedGenre by viewModel.selectedGenre.collectAsState()
    val selectedTop100 by viewModel.selectedTop100.collectAsState()
    val selectedPeopleKind by viewModel.selectedPeopleKind.collectAsState()
    val selectedPerson by viewModel.selectedPerson.collectAsState()

    // Handle system back press
    BackHandler(enabled = showFullPlayer || selectedBookId != null ||
        selectedWebSource != null || selectedSeries != null || selectedGenre != null || selectedTop100 ||
        selectedPeopleKind != null || selectedPerson != null) {
        if (showFullPlayer) {
            viewModel.setShowFullPlayer(false)
        } else if (selectedWebSource != null) {
            viewModel.closeWebSource()
        } else if (selectedSeries != null) {
            viewModel.closeSeries()
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
                        onClose = { viewModel.closeWebSource() }
                    )

                    // Series (cycle) page (spec #8 ticket T8).
                    selectedSeries != null -> SeriesScreen(
                        viewModel = viewModel,
                        onBackClick = { viewModel.closeSeries() },
                        onBookClick = { id -> viewModel.selectBook(id) }
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
                        onBackClick = { viewModel.selectBook(null) }
                    )

                    else -> when (selectedTab) {
                        // Spec-9: first tab is the listening panel, not the storefront.
                        SelectedTab.LISTEN -> ListenScreen(
                            viewModel = viewModel,
                            onBookClick = { id -> viewModel.selectBook(id) },
                            onPlayClick = { book ->
                                viewModel.playAudiobook(book)
                                viewModel.setShowFullPlayer(true)
                            },
                            onBrowseClick = { viewModel.selectTab(SelectedTab.EXPLORE) },
                            onImportClick = { viewModel.selectTab(SelectedTab.LIBRARY) },
                            // Spec-13 T3 + spec-15 T2: the WebView-source
                            // browser entry point (sluhay.com first;
                            // sluhayknigi joins later) renders only in debug
                            // builds — in release the same row would open an
                            // in-app browser that cannot exist.
                            onOpenWebSource = if (BuildConfig.DEBUG) {
                                {
                                    viewModel.openWebSource(
                                        sourceId = "sluhay",
                                        homeUrl = "https://sluhay.com/"
                                    )
                                }
                            } else {
                                null
                            }
                        )
                        SelectedTab.EXPLORE -> HomeScreen(
                            viewModel = viewModel,
                            onBookClick = { id -> viewModel.selectBook(id) },
                            onPlayClick = { book ->
                                viewModel.playAudiobook(book)
                                viewModel.setShowFullPlayer(true)
                            }
                        )
                        SelectedTab.LIBRARY -> LibraryScreen(
                            viewModel = viewModel,
                            onBookClick = { id -> viewModel.selectBook(id) },
                            onPlayClick = { book ->
                                viewModel.playAudiobook(book)
                                viewModel.setShowFullPlayer(true)
                            },
                            onBrowseClick = { viewModel.selectTab(SelectedTab.EXPLORE) }
                        )
                        else -> HomeScreen(
                            viewModel = viewModel,
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
