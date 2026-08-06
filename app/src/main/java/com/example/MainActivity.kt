package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.MainViewModel
import com.example.ui.SelectedTab
import com.example.ui.components.MiniPlayerBar
import com.example.ui.screens.BookDetailScreen
import com.example.ui.screens.FourReadWebScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.PlayerScreen
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
    val selectedTab by viewModel.selectedTab.collectAsState()
    val selectedBookId by viewModel.selectedBookId.collectAsState()
    val showFullPlayer by viewModel.showFullPlayer.collectAsState()
    val playerState by viewModel.playerState.collectAsState()

    // Handle system back press
    BackHandler(enabled = showFullPlayer || selectedBookId != null) {
        if (showFullPlayer) {
            viewModel.setShowFullPlayer(false)
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

                    // Navigation Bar
                    NavigationBar(
                        containerColor = CyberSurface,
                        contentColor = CyberPrimary,
                        modifier = Modifier
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .testTag("bottom_navigation_bar")
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == SelectedTab.EXPLORE && selectedBookId == null,
                            onClick = {
                                viewModel.selectBook(null)
                                viewModel.selectTab(SelectedTab.EXPLORE)
                            },
                            icon = { Icon(imageVector = Icons.Default.Explore, contentDescription = "Explore") },
                            label = { Text("Explore") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = CyberPrimary,
                                selectedTextColor = CyberPrimary,
                                indicatorColor = CyberCardBorder
                            ),
                            modifier = Modifier.testTag("tab_explore")
                        )

                        NavigationBarItem(
                            selected = selectedTab == SelectedTab.FOUR_READ_WEB && selectedBookId == null,
                            onClick = {
                                viewModel.selectBook(null)
                                viewModel.selectTab(SelectedTab.FOUR_READ_WEB)
                            },
                            icon = { Icon(imageVector = Icons.Default.Language, contentDescription = "4read Web") },
                            label = { Text("4read Web") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = CyberPrimary,
                                selectedTextColor = CyberPrimary,
                                indicatorColor = CyberCardBorder
                            ),
                            modifier = Modifier.testTag("tab_4read_web")
                        )

                        NavigationBarItem(
                            selected = selectedTab == SelectedTab.LIBRARY && selectedBookId == null,
                            onClick = {
                                viewModel.selectBook(null)
                                viewModel.selectTab(SelectedTab.LIBRARY)
                            },
                            icon = { Icon(imageVector = Icons.Default.LibraryMusic, contentDescription = "Library") },
                            label = { Text("Offline Library") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = CyberPrimary,
                                selectedTextColor = CyberPrimary,
                                indicatorColor = CyberCardBorder
                            ),
                            modifier = Modifier.testTag("tab_library")
                        )

                        NavigationBarItem(
                            selected = selectedTab == SelectedTab.BOOKMARKS && selectedBookId == null,
                            onClick = {
                                viewModel.selectBook(null)
                                viewModel.selectTab(SelectedTab.BOOKMARKS)
                            },
                            icon = { Icon(imageVector = Icons.Default.Bookmark, contentDescription = "Bookmarks") },
                            label = { Text("Bookmarks") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = CyberPrimary,
                                selectedTextColor = CyberPrimary,
                                indicatorColor = CyberCardBorder
                            ),
                            modifier = Modifier.testTag("tab_bookmarks")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (selectedBookId != null) {
                    BookDetailScreen(
                        viewModel = viewModel,
                        onBackClick = { viewModel.selectBook(null) }
                    )
                } else {
                    when (selectedTab) {
                        SelectedTab.EXPLORE -> HomeScreen(
                            viewModel = viewModel,
                            onBookClick = { id -> viewModel.selectBook(id) },
                            onPlayClick = { book ->
                                viewModel.playAudiobook(book)
                                viewModel.setShowFullPlayer(true)
                            }
                        )
                        SelectedTab.FOUR_READ_WEB -> FourReadWebScreen(
                            viewModel = viewModel,
                            onBookImported = {
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
                        SelectedTab.BOOKMARKS -> LibraryScreen(
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
