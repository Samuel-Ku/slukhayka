package com.example.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.player.PlayerState
import com.example.ui.MainViewModel
import com.example.ui.components.BookmarkDialog
import com.example.ui.components.PlayerDebugOverlay
import com.example.ui.components.SleepTimerSheet
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val playerState by viewModel.playerState.collectAsState()
    val book = playerState.currentBook ?: return

    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showAddBookmarkDialog by remember { mutableStateOf(false) }
    var showChapterSelectSheet by remember { mutableStateOf(false) }
    var showDebugOverlay by remember { mutableStateOf(true) }

    val currentChapterTitle = if (playerState.chapters.isNotEmpty() && playerState.currentChapterIndex in playerState.chapters.indices) {
        playerState.chapters[playerState.currentChapterIndex].title
    } else "Chapter ${playerState.currentChapterIndex + 1}"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PLAYING AUDIOBOOK", style = MaterialTheme.typography.labelSmall, color = CyberPrimary)
                        Text("${book.genre} • ${playerState.audioEngineMode}", style = MaterialTheme.typography.bodySmall, color = CyberSecondary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("close_player_button")) {
                        Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "Minimize Player", modifier = Modifier.size(32.dp))
                    }
                },
                actions = {
                    IconButton(onClick = { showDebugOverlay = !showDebugOverlay }) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "Debug Overlay",
                            tint = if (showDebugOverlay) CyberPrimary else CyberTextSecondary
                        )
                    }
                    IconButton(onClick = { viewModel.toggleFavorite(book.id, !book.isFavorite) }) {
                        Icon(
                            imageVector = if (book.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (book.isFavorite) Color(0xFFFF4081) else CyberTextSecondary
                        )
                    }
                    IconButton(onClick = { showChapterSelectSheet = true }) {
                        Icon(imageVector = Icons.Default.FormatListNumbered, contentDescription = "Chapter List", tint = CyberPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CyberBg)
            )
        },
        containerColor = CyberBg
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .testTag("full_player_screen"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
            Spacer(modifier = Modifier.height(10.dp))

            // Cover Image
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .border(2.dp, CyberPrimary.copy(alpha = 0.6f), RoundedCornerShape(24.dp)),
                elevation = CardDefaults.cardElevation(12.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    com.example.ui.components.BookCoverImage(
                        book = book,
                        contentDescription = book.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    if (playerState.isOfflineMode) {
                        Surface(
                            color = CyberSecondary,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudDone,
                                    contentDescription = null,
                                    tint = CyberOnSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "OFFLINE",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = CyberOnSecondary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Animated Waveform Visualizer
            AudioWaveformVisualizer(isPlaying = playerState.isPlaying)

            Spacer(modifier = Modifier.height(12.dp))

            // Title & Author Text
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = CyberTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = currentChapterTitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = CyberPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "By ${book.author}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberTextSecondary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Scrubber Slider
            Column(modifier = Modifier.fillMaxWidth()) {
                val currentSec = playerState.currentPositionMs / 1000L
                val durationSec = (playerState.durationMs / 1000L).coerceAtLeast(1L)
                val sliderValue = (playerState.currentPositionMs.toFloat() / playerState.durationMs.toFloat()).coerceIn(0f, 1f)

                Slider(
                    value = sliderValue,
                    onValueChange = { percent ->
                        val targetMs = (percent * playerState.durationMs).toLong()
                        viewModel.playerManager.seekTo(targetMs)
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = CyberPrimary,
                        activeTrackColor = CyberPrimary,
                        inactiveTrackColor = CyberCardBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("player_progress_slider")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = MainViewModel.formatTime(currentSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTextSecondary
                    )
                    Text(
                        text = "-${MainViewModel.formatTime((durationSec - currentSec).coerceAtLeast(0L))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Playback Action Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Skip -15s
                IconButton(onClick = { viewModel.playerManager.skipBackward(15) }) {
                    Icon(
                        imageVector = Icons.Default.Replay10,
                        contentDescription = "Rewind 15s",
                        tint = CyberTextPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Previous Chapter
                IconButton(onClick = { viewModel.playerManager.previousChapter() }) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous Chapter",
                        tint = CyberTextPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Play / Pause
                IconButton(
                    onClick = { viewModel.playerManager.togglePlayPause() },
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(CyberPrimary)
                        .testTag("player_play_pause_button")
                ) {
                    Icon(
                        imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                        tint = CyberOnPrimary,
                        modifier = Modifier.size(42.dp)
                    )
                }

                // Next Chapter
                IconButton(onClick = { viewModel.playerManager.nextChapter() }) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Chapter",
                        tint = CyberTextPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Skip +30s
                IconButton(onClick = { viewModel.playerManager.skipForward(30) }) {
                    Icon(
                        imageVector = Icons.Default.Forward30,
                        contentDescription = "Forward 30s",
                        tint = CyberTextPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Secondary Controls (Speed, Bookmark, Sleep Timer)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Speed Selector Button
                val nextSpeed = when (playerState.playbackSpeed) {
                    0.5f -> 0.8f
                    0.8f -> 1.0f
                    1.0f -> 1.25f
                    1.25f -> 1.5f
                    1.5f -> 1.75f
                    1.75f -> 2.0f
                    2.0f -> 2.5f
                    2.5f -> 3.0f
                    else -> 0.5f
                }

                AssistChip(
                    onClick = { viewModel.playerManager.setPlaybackSpeed(nextSpeed) },
                    label = { Text("${playerState.playbackSpeed}x", fontWeight = FontWeight.Bold) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = CyberCardBg, labelColor = CyberPrimary),
                    border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = CyberCardBorder),
                    modifier = Modifier.testTag("speed_chip")
                )

                // Add Bookmark
                AssistChip(
                    onClick = { showAddBookmarkDialog = true },
                    label = { Text("Bookmark") },
                    leadingIcon = { Icon(imageVector = Icons.Default.BookmarkAdd, contentDescription = null, tint = CyberSecondary) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = CyberCardBg, labelColor = CyberTextPrimary),
                    border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = CyberCardBorder),
                    modifier = Modifier.testTag("add_bookmark_chip")
                )

                // Sleep Timer
                val timerActive = playerState.sleepTimerMinutes > 0
                AssistChip(
                    onClick = { showSleepTimerSheet = true },
                    label = {
                        Text(
                            if (timerActive) "${playerState.sleepTimerRemainingSeconds / 60}m" else "Sleep Timer",
                            color = if (timerActive) CyberSecondary else CyberTextPrimary
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = if (timerActive) CyberSecondary else CyberTextSecondary
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(containerColor = CyberCardBg),
                    border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = if (timerActive) CyberSecondary else CyberCardBorder),
                    modifier = Modifier.testTag("sleep_timer_chip")
                )
            }
        }

        if (showDebugOverlay) {
            PlayerDebugOverlay(
                playerState = playerState,
                onClose = { showDebugOverlay = false },
                onRetryPlayback = {
                    viewModel.playerManager.prepareChapter(
                        playerState.currentChapterIndex,
                        playerState.currentPositionMs,
                        autoPlay = true
                    )
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
            )
        }
    }
}

    if (showSleepTimerSheet) {
        SleepTimerSheet(
            currentTimerMinutes = playerState.sleepTimerMinutes,
            onSelectTimer = { minutes -> viewModel.playerManager.setSleepTimer(minutes) },
            onDismiss = { showSleepTimerSheet = false }
        )
    }

    if (showAddBookmarkDialog) {
        BookmarkDialog(
            timestampSeconds = playerState.currentPositionMs / 1000L,
            chapterTitle = currentChapterTitle,
            onDismiss = { showAddBookmarkDialog = false },
            onSave = { note ->
                viewModel.addBookmarkAtCurrentPosition(note)
            }
        )
    }

    if (showChapterSelectSheet) {
        ModalBottomSheet(
            onDismissRequest = { showChapterSelectSheet = false },
            containerColor = CyberCardBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Select Chapter",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = CyberTextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))
                playerState.chapters.forEachIndexed { index, chapter ->
                    val isSelected = index == playerState.currentChapterIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.playerManager.selectChapter(index)
                                showChapterSelectSheet = false
                            }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = if (isSelected) CyberPrimary else CyberTextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = MainViewModel.formatTime(chapter.durationSeconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberTextSecondary
                        )
                    }
                    Divider(color = CyberCardBorder.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
fun AudioWaveformVisualizer(isPlaying: Boolean) {
    val transition = rememberInfiniteTransition(label = "waveform")
    val heights = List(16) { index ->
        if (isPlaying) {
            transition.animateFloat(
                initialValue = 12f,
                targetValue = (24..48).random().toFloat(),
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 300 + (index * 50), easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar_$index"
            ).value
        } else {
            12f
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        heights.forEach { h ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isPlaying) CyberPrimary else CyberCardBorder)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
    }
}
