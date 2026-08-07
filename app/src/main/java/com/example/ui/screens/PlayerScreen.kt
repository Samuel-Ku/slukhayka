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
import com.example.ui.components.SpeedSheet
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
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showAddBookmarkDialog by remember { mutableStateOf(false) }
    var showChapterSelectSheet by remember { mutableStateOf(false) }
    var showDebugOverlay by remember {
        // Phase 2.5 hotfix (CR-003): PlayerDebugOverlay was rendering by default
        // every time the PlayerScreen opened, shipping 296 lines of debug UI
        // (monospace status panels, copy-URL, "Retry Audio Load") to release.
        mutableStateOf(com.example.BuildConfig.DEBUG)
    }

    val currentChapterTitle = if (playerState.chapters.isNotEmpty() && playerState.currentChapterIndex in playerState.chapters.indices) {
        playerState.chapters[playerState.currentChapterIndex].title
    } else "Chapter ${playerState.currentChapterIndex + 1}"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PLAYING AUDIOBOOK", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text("${book.genre} • ${playerState.audioEngineMode}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
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
                            tint = if (showDebugOverlay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { viewModel.toggleFavorite(book.id, !book.isFavorite) }) {
                        Icon(
                            imageVector = if (book.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (book.isFavorite) Color(0xFFFF4081) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showChapterSelectSheet = true }) {
                        Icon(imageVector = Icons.Default.FormatListNumbered, contentDescription = "Chapter List", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
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
                    .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(24.dp)),
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
                            color = MaterialTheme.colorScheme.secondary,
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
                                    tint = MaterialTheme.colorScheme.onSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "OFFLINE",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSecondary
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
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = currentChapterTitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "By ${book.author}",
                    style = MaterialTheme.typography.bodySmall,                            color = MaterialTheme.colorScheme.onSurfaceVariant

                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Scrubber Slider
            Column(modifier = Modifier.fillMaxWidth()) {
                val currentSec = playerState.currentPositionMs / 1000L
                val durationSec = (playerState.durationMs / 1000L).coerceAtLeast(1L)
                // Guard against a 0-duration chapter (locally-imported books start
                // with durationSeconds = 0): 0/0 is NaN and Material3 Slider
                // crashes with "Cannot round NaN value" (observed on device).
                val sliderValue = if (playerState.durationMs > 0L) {
                    (playerState.currentPositionMs.toFloat() / playerState.durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f

                Slider(
                    value = sliderValue,
                    onValueChange = { percent ->
                        val targetMs = (percent * playerState.durationMs).toLong()
                        viewModel.playerManager.seekTo(targetMs)
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "-${MainViewModel.formatTime((durationSec - currentSec).coerceAtLeast(0L))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Previous Chapter
                IconButton(onClick = { viewModel.playerManager.previousChapter() }) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous Chapter",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Play / Pause
                IconButton(
                    onClick = { viewModel.playerManager.togglePlayPause() },
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .testTag("player_play_pause_button")
                ) {
                    Icon(
                        imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(42.dp)
                    )
                }

                // Next Chapter
                IconButton(onClick = { viewModel.playerManager.nextChapter() }) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Chapter",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Skip +30s
                IconButton(onClick = { viewModel.playerManager.skipForward(30) }) {
                    Icon(
                        imageVector = Icons.Default.Forward30,
                        contentDescription = "Forward 30s",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Position-history undo (wayfinder #25): after a big accidental
            // seek, offer a one-tap jump back to where the listener was.
            if (playerState.canUndoSeek) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .testTag("undo_seek_row"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Повернутися до ${MainViewModel.formatTime(playerState.undoFromPositionMs / 1000L)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { viewModel.playerManager.undoLastSeek() }) {
                        Text("Повернутися")
                    }
                }
            }

            // Secondary Controls (Speed, Bookmark, Sleep Timer)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Speed Selector Button — opens the speed sheet (wayfinder #26).
                AssistChip(
                    onClick = { showSpeedSheet = true },
                    label = { Text("${playerState.playbackSpeed}x", fontWeight = FontWeight.Bold) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, labelColor = MaterialTheme.colorScheme.primary),
                    border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.testTag("speed_chip")
                )

                // Add Bookmark
                AssistChip(
                    onClick = { showAddBookmarkDialog = true },
                    label = { Text("Bookmark") },
                    leadingIcon = { Icon(imageVector = Icons.Default.BookmarkAdd, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, labelColor = MaterialTheme.colorScheme.onSurface),
                    border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.testTag("add_bookmark_chip")
                )

                // Sleep Timer
                val timerActive = playerState.sleepTimerMinutes > 0
                AssistChip(
                    onClick = { showSleepTimerSheet = true },
                    label = {
                        Text(
                            if (timerActive) "${playerState.sleepTimerRemainingSeconds / 60}m" else "Sleep Timer",
                            color = if (timerActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = if (timerActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = if (timerActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant),
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

    if (showSpeedSheet) {
        SpeedSheet(
            currentSpeed = playerState.playbackSpeed,
            onSpeedChange = { speed -> viewModel.playerManager.setPlaybackSpeed(speed) },
            onSaveForBook = { viewModel.playerManager.savePreferredSpeed(playerState.playbackSpeed) },
            onSetDefault = { viewModel.playerManager.setDefaultSpeed(playerState.playbackSpeed) },
            onDismiss = { showSpeedSheet = false }
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Select Chapter",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
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
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = MainViewModel.formatTime(chapter.durationSeconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
                    .background(if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
    }
}
