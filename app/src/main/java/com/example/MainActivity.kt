package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.MainViewModel
import com.example.ui.SelectedTab
import com.example.ui.components.MiniPlayerBar
import com.example.ui.screens.BookDetailScreen
import com.example.ui.screens.FourReadWebScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.ListenScreen
import com.example.ui.screens.PlayerScreen
import com.example.ui.screens.SeriesScreen
import com.example.ui.theme.AudiobookTheme
import com.example.ui.theme.CyberCardBorder
import com.example.ui.theme.CyberPrimary
import com.example.ui.theme.CyberSurface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Globally disable hardware bitmaps in Coil to prevent E/ashmem Pinning is deprecated errors
        val imageLoader = coil.ImageLoader.Builder(this)
            .allowHardware(false)
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

    val webFallbackUrl by viewModel.webFallbackUrl.collectAsState()
    val selectedSeries by viewModel.selectedSeries.collectAsState()

    // Handle system back press
    BackHandler(enabled = showFullPlayer || selectedBookId != null || webFallbackUrl != null || selectedSeries != null) {
        if (showFullPlayer) {
            viewModel.setShowFullPlayer(false)
        } else if (webFallbackUrl != null) {
            viewModel.closeWebFallback()
        } else if (selectedSeries != null) {
            viewModel.closeSeries()
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
                    // "Open on site" WebView fallback (spec #8 ticket T4): no
                    // longer a tab, only reachable from the book page.
                    webFallbackUrl != null -> FourReadWebScreen(
                        viewModel = viewModel,
                        onBookImported = {
                            viewModel.setShowFullPlayer(true)
                        }
                    )

                    // Series (cycle) page (spec #8 ticket T8).
                    selectedSeries != null -> SeriesScreen(
                        viewModel = viewModel,
                        onBackClick = { viewModel.closeSeries() },
                        onBookClick = { id -> viewModel.selectBook(id) }
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
                            onImportClick = { viewModel.selectTab(SelectedTab.LIBRARY) }
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
                            }
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
        containerColor = CyberSurface,
        contentColor = CyberPrimary,
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
                selectedIconColor = CyberPrimary,
                selectedTextColor = CyberPrimary,
                indicatorColor = CyberCardBorder
            ),
            modifier = Modifier.testTag("tab_listen")
        )

        NavigationBarItem(
            selected = selectedTab == SelectedTab.EXPLORE && !bookDetailOpen,
            onClick = { onSelect(SelectedTab.EXPLORE) },
            icon = { Icon(imageVector = Icons.Default.Explore, contentDescription = "Browse") },
            label = { Text("Огляд") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = CyberPrimary,
                selectedTextColor = CyberPrimary,
                indicatorColor = CyberCardBorder
            ),
            modifier = Modifier.testTag("tab_explore")
        )

        NavigationBarItem(
            selected = selectedTab == SelectedTab.LIBRARY && !bookDetailOpen,
            onClick = { onSelect(SelectedTab.LIBRARY) },
            icon = { Icon(imageVector = Icons.Default.LibraryMusic, contentDescription = "Library") },
            label = { Text("Медіатека") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = CyberPrimary,
                selectedTextColor = CyberPrimary,
                indicatorColor = CyberCardBorder
            ),
            modifier = Modifier.testTag("tab_library")
        )
    }
}
