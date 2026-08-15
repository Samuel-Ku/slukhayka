package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.BuildConfig
import com.example.data.db.AudiobookEntity
import com.example.data.db.BookmarkEntity
import com.example.data.entries.LibraryEntries
import com.example.data.db.ChapterEntity
import com.example.player.PlayerState
import com.example.ui.MainViewModel
import com.example.ui.components.BookCoverImage
import com.example.ui.components.PlayerDebugOverlay
import com.example.ui.components.SleepTimerSheet
import com.example.ui.components.SpeedSheet
import com.example.ui.displayAuthor
import com.example.ui.library.effectiveChapterDurations
import com.example.ui.theme.AppDimens
import com.example.ui.theme.TabularTimerStyle

/** Values shared by the visual progress treatment and its unit tests. */
data class PlayerProgressUi(
    val chapterFraction: Float,
    val bookFraction: Float,
    val bookPositionSeconds: Long,
    val bookDurationSeconds: Long,
    val chapterMarkers: List<Float>,
    val bookmarkMarkers: List<Float>
)

data class BookSeekTarget(val chapterIndex: Int, val positionMs: Long)



fun calculateBookSeekTarget(
    chapters: List<ChapterEntity>,
    currentChapterIndex: Int,
    currentChapterDurationMs: Long,
    fraction: Float,
    bookTotalDurationSeconds: Long = 0L
): BookSeekTarget? {
    if (chapters.isEmpty()) return null
    val durations = effectiveChapterDurations(chapters, currentChapterIndex, currentChapterDurationMs, bookTotalDurationSeconds)
    val totalSeconds = durations.sum()
    if (totalSeconds <= 0L) return null

    val targetSeconds = (fraction.coerceIn(0f, 1f) * totalSeconds)
        .toLong()
        .coerceIn(0L, totalSeconds)
    var elapsed = 0L
    durations.forEachIndexed { index, duration ->
        if (targetSeconds < elapsed + duration || index == durations.lastIndex) {
            return BookSeekTarget(index, (targetSeconds - elapsed).coerceIn(0L, duration) * 1_000L)
        }
        elapsed += duration
    }
    return null
}

/**
 * Converts chapter-relative playback and bookmarks into one book timeline.
 * Zero-duration imports degrade to evenly-spaced chapter markers rather than
 * producing NaN values (the same class of crash guarded against by the old UI).
 */
fun calculatePlayerProgress(
    chapters: List<ChapterEntity>,
    currentChapterIndex: Int,
    currentPositionMs: Long,
    currentChapterDurationMs: Long,
    bookmarks: List<BookmarkEntity>,
    bookTotalDurationSeconds: Long = 0L
): PlayerProgressUi {
    if (chapters.isEmpty()) {
        return PlayerProgressUi(0f, 0f, 0L, 0L, emptyList(), emptyList())
    }

    val selectedIndex = currentChapterIndex.coerceIn(chapters.indices)
    val effectiveDurations = effectiveChapterDurations(chapters, selectedIndex, currentChapterDurationMs, bookTotalDurationSeconds)
    val currentDurationSeconds = effectiveDurations[selectedIndex]
    val totalSeconds = effectiveDurations.sum()
    val positionInChapter = (currentPositionMs / 1000L).coerceIn(0L, currentDurationSeconds)
    val elapsedBefore = effectiveDurations.take(selectedIndex).sum()
    val bookPosition = elapsedBefore + positionInChapter

    val chapterFraction = if (currentDurationSeconds > 0L) {
        positionInChapter.toFloat() / currentDurationSeconds
    } else 0f
    val bookFraction = if (totalSeconds > 0L) bookPosition.toFloat() / totalSeconds else 0f

    val chapterMarkers = if (totalSeconds > 0L) {
        var elapsed = 0L
        effectiveDurations.dropLast(1).map { duration ->
            elapsed += duration
            elapsed.toFloat() / totalSeconds
        }
    } else {
        (1 until chapters.size).map { it.toFloat() / chapters.size }
    }

    val bookmarkMarkers = bookmarks.mapNotNull { bookmark ->
        if (bookmark.chapterIndex !in chapters.indices || totalSeconds <= 0L) return@mapNotNull null
        val before = effectiveDurations.take(bookmark.chapterIndex).sum()
        val within = bookmark.timestampSeconds.coerceIn(0L, effectiveDurations[bookmark.chapterIndex])
        ((before + within).toFloat() / totalSeconds).coerceIn(0f, 1f)
    }

    return PlayerProgressUi(
        chapterFraction = chapterFraction.coerceIn(0f, 1f),
        bookFraction = bookFraction.coerceIn(0f, 1f),
        bookPositionSeconds = bookPosition,
        bookDurationSeconds = totalSeconds,
        chapterMarkers = chapterMarkers,
        bookmarkMarkers = bookmarkMarkers
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: MainViewModel,
    // ADR-0008 batch 4 (#159): the screen receives the module it reads from
    // as a parameter, wired from the composition root — the injection idiom
    // settled by #154. Player state is read from the player manager directly
    // (already a public field); bookmark creation stays on the ViewModel.
    libraryEntries: LibraryEntries,
    onDismiss: () -> Unit
) {
    val playerState by viewModel.playerState.collectAsState()
    // ADR-0008: module flows are read directly — no forwarding StateFlow on
    // the ViewModel.
    val allBookmarks by libraryEntries.allBookmarks.collectAsState(initial = emptyList())
    val book = playerState.currentBook ?: return
    // ADR-0008: suspend module calls from user actions run on the composition
    // scope (same pattern as playerManager's call-through).
    val scope = rememberCoroutineScope()
    val bookmarks = remember(allBookmarks, book.id) { allBookmarks.filter { it.bookId == book.id } }

    var showSleepTimerSheet by rememberSaveable { mutableStateOf(false) }
    var showSpeedSheet by rememberSaveable { mutableStateOf(false) }
    var showBookmarkSheet by rememberSaveable { mutableStateOf(false) }
    var showChapterSheet by rememberSaveable { mutableStateOf(false) }
    var showDebugOverlay by rememberSaveable { mutableStateOf(false) }
    var artworkAccent by remember(book.id) { mutableStateOf<Color?>(null) }
    // Real cover aspect ratio once the artwork loads (defaults to a portrait
    // book cover ~2:3); used to size the cover frame instead of a forced
    // square, so tall covers are NOT cropped — the player has plenty of
    // vertical room, and a cropped cover read as a rendering bug.
    var artworkAspect by remember(book.id) { mutableStateOf(2f / 3f) }

    // Peak-end feedback (design pass): one-shot snackbar confirming the
    // small wins — bookmark saved, sleep timer armed, speed changed. The
    // bottom sheets close silently otherwise.
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingFeedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingFeedback) {
        pendingFeedback?.let { message ->
            // Wait for the bottom sheet's dismissal animation before surfacing
            // the confirmation, so the snackbar never renders beneath it.
            kotlinx.coroutines.delay(300)
            snackbarHostState.showSnackbar(message)
            pendingFeedback = null
        }
    }

    val currentChapter = playerState.chapters.getOrNull(playerState.currentChapterIndex)
    val currentChapterTitle = currentChapter?.title ?: "Розділ ${playerState.currentChapterIndex + 1}"
    val progress = remember(
        playerState.chapters,
        playerState.currentChapterIndex,
        playerState.currentPositionMs,
        playerState.durationMs,
        bookmarks,
        book.totalDurationSeconds
    ) {
        calculatePlayerProgress(
            chapters = playerState.chapters,
            currentChapterIndex = playerState.currentChapterIndex,
            currentPositionMs = playerState.currentPositionMs,
            currentChapterDurationMs = playerState.durationMs,
            bookmarks = bookmarks,
            bookTotalDurationSeconds = book.totalDurationSeconds
        )
    }

    // The player's backdrop is a plain Box.background() (no Material Surface
    // above it), so LocalContentColor would otherwise stay at its framework
    // default (BLACK) and every IconButton/Icon without an explicit tint
    // would render near-invisible black glyphs on the dark backdrop.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(modifier = Modifier.fillMaxSize()) {
            PlayerScreenContent(
            playerState = playerState,
            book = book,
            currentChapterTitle = currentChapterTitle,
            progress = progress,
            artworkAccent = artworkAccent,
            artworkAspect = artworkAspect,
            onArtworkLoaded = {
                artworkAccent = extractArtworkAccent(it)
                val iw = it.intrinsicWidth
                val ih = it.intrinsicHeight
                if (iw > 0 && ih > 0) {
                    // Clamp to sane bounds so a panorama or a postage-stamp
                    // cover cannot blow the layout apart.
                    artworkAspect = (iw.toFloat() / ih).coerceIn(0.6f, 1.6f)
                }
            },
            onDismiss = onDismiss,
            onToggleFavorite = { scope.launch { libraryEntries.toggleFavorite(book.id, !book.isFavorite) } },
            onToggleDebug = { showDebugOverlay = !showDebugOverlay },
            onSeek = { fraction ->
                viewModel.playerManager.seekTo((fraction * playerState.durationMs).toLong())
            },
            onBookSeek = { fraction ->
                calculateBookSeekTarget(
                    chapters = playerState.chapters,
                    currentChapterIndex = playerState.currentChapterIndex,
                    currentChapterDurationMs = playerState.durationMs,
                    fraction = fraction,
                    bookTotalDurationSeconds = book.totalDurationSeconds
                )?.let { target ->
                    if (target.chapterIndex == playerState.currentChapterIndex) {
                        viewModel.playerManager.seekTo(target.positionMs)
                    } else {
                        viewModel.playerManager.prepareChapter(
                            chapterIndex = target.chapterIndex,
                            startPositionMs = target.positionMs,
                            autoPlay = playerState.isPlaying
                        )
                    }
                }
            },
            onPreviousChapter = viewModel.playerManager::previousChapter,
            onBack = { viewModel.playerManager.skipBackward(15) },
            onPlayPause = viewModel.playerManager::togglePlayPause,
            onForward = { viewModel.playerManager.skipForward(30) },
            onNextChapter = viewModel.playerManager::nextChapter,
            onUndoSeek = viewModel.playerManager::undoLastSeek,
            onSpeed = { showSpeedSheet = true },
            onTimer = { showSleepTimerSheet = true },
            onBookmark = { showBookmarkSheet = true },
            onChapters = { showChapterSheet = true }
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(AppDimens.SpaceLg)
                .navigationBarsPadding()
        )

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
                events = viewModel.playerManager.playbackEventLog.recent(6),
                metricsSummary = viewModel.playerManager.playbackMetrics.export(),
                journalExport = viewModel.playerManager.playbackEventLog.export(),
                modifier = Modifier.align(Alignment.TopCenter).padding(AppDimens.SpaceLg)
            )
        }
        }
    }

    if (showSleepTimerSheet) {
        SleepTimerSheet(
            currentTimerMinutes = playerState.sleepTimerMinutes,
            isEndOfChapter = playerState.isSleepTimerEndOfChapter,
            remainingSeconds = playerState.sleepTimerRemainingSeconds,
            // Close-on-select: previously the sheet stayed open until the user
            // dismissed it; the chip already reflects the new value, so closing
            // immediately feels tighter and the Snackbar confirms the change.
            onSelectTimer = { minutes ->
                viewModel.playerManager.setSleepTimer(minutes)
                pendingFeedback = if (minutes == -1) "До кінця розділу"
                else if (minutes > 0) "Таймер на $minutes хв"
                else ""
                showSleepTimerSheet = false
            },
            onDismiss = { showSleepTimerSheet = false }
        )
    }

    if (showSpeedSheet) {
        SpeedSheet(
            currentSpeed = playerState.playbackSpeed,
            onSpeedChange = viewModel.playerManager::setPlaybackSpeed,
            onSaveForBook = { viewModel.playerManager.savePreferredSpeed(playerState.playbackSpeed) },
            onSetDefault = { viewModel.playerManager.setDefaultSpeed(playerState.playbackSpeed) },
            onDismiss = { showSpeedSheet = false }
        )
    }

    if (showBookmarkSheet) {
        BookmarkBottomSheet(
            timestampSeconds = playerState.currentPositionMs / 1000L,
            chapterTitle = currentChapterTitle,
            onDismiss = { showBookmarkSheet = false },
            onSave = {
                viewModel.addBookmarkAtCurrentPosition(it)
                pendingFeedback = "Закладку збережено"
                showBookmarkSheet = false
            }
        )
    }

    if (showChapterSheet) {
        ChapterBottomSheet(
            chapters = playerState.chapters,
            selectedIndex = playerState.currentChapterIndex,
            onSelect = { index ->
                viewModel.playerManager.selectChapter(index)
                showChapterSheet = false
            },
            onDismiss = { showChapterSheet = false }
        )
    }
}

/** Pure player surface, separated from the ViewModel for snapshot and interaction tests. */
@Composable
fun PlayerScreenContent(
    playerState: PlayerState,
    book: AudiobookEntity,
    currentChapterTitle: String,
    progress: PlayerProgressUi,
    artworkAccent: Color?,
    artworkAspect: Float = 2f / 3f,
    onArtworkLoaded: (Drawable) -> Unit,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleDebug: () -> Unit,
    onSeek: (Float) -> Unit,
    onBookSeek: (Float) -> Unit,
    onPreviousChapter: () -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onNextChapter: () -> Unit,
    onUndoSeek: () -> Unit,
    onSpeed: () -> Unit,
    onTimer: () -> Unit,
    onBookmark: () -> Unit,
    onChapters: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background = MaterialTheme.colorScheme.background
    val tint = artworkAccent ?: MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                // The player is a full-screen OVERLAY on top of whatever screen
                // was open (AnimatedVisibility in MainActivity), so its backdrop
                // must be fully opaque — a translucent gradient let the previous
                // screen (book page, list) show through the top of the player,
                // which read as a rendering bug. The accent is blended INTO the
                // background color (lerp) instead of painted with alpha, keeping
                // the soft tinted look with zero see-through.
                Brush.verticalGradient(
                    0f to lerp(background, tint, 0.12f),
                    0.42f to background,
                    1f to background
                )
            )
            .testTag("full_player_screen")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PlayerTopBar(
                isFavorite = book.isFavorite,
                isOffline = playerState.isOfflineMode,
                onDismiss = onDismiss,
                onToggleFavorite = onToggleFavorite,
                onToggleDebug = onToggleDebug
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppDimens.PageSides),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(AppDimens.SpaceSm))
                Box(contentAlignment = Alignment.Center) {
                    // Soft glow behind the cover — tinted with the artwork accent
                    // (or the brand accent before the cover loads) so the shadow
                    // hue matches the scene instead of reading as flat black.
                    // Sized and painted in dp so the falloff is density-stable.
                    val glowRadiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { 300.dp.toPx() }
                    // The glow mirrors the cover's (real) aspect ratio instead
                    // of a hard-coded square, so the halo hugs the artwork.
                    // aspectRatio honours the heightIn cap and shrinks the
                    // WIDTH to match, so a tall cover is scaled (never
                    // cropped) and the transport row stays on screen.
                    val coverAspect = artworkAspect.coerceIn(0.6f, 1.6f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .heightIn(max = 336.dp)
                            .aspectRatio(coverAspect)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        tint.copy(alpha = 0.28f),
                                        tint.copy(alpha = 0.08f),
                                        Color.Transparent
                                    ),
                                    radius = glowRadiusPx
                                )
                            )
                    )
                    Surface(
                        shape = RoundedCornerShape(AppDimens.RadiusHero),
                        tonalElevation = 1.dp,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .widthIn(max = 272.dp)
                            .heightIn(max = 336.dp)
                            .fillMaxWidth(0.76f)
                            .aspectRatio(coverAspect)
                    ) {
                        BookCoverImage(
                            book = book,
                            contentDescription = "Обкладинка: ${book.title}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            onImageLoaded = onArtworkLoaded
                        )
                    }
                }

                Spacer(Modifier.height(AppDimens.SpaceXl))
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(AppDimens.SpaceXs))
                Text(
                    text = book.displayAuthor,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (book.narrator.isNotBlank()) {
                    Text(
                        text = "Читає ${book.narrator}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(AppDimens.SpaceSm))
                Text(
                    text = currentChapterTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(AppDimens.SpaceLg))
                DualProgress(
                    progress = progress,
                    chapterPositionSeconds = playerState.currentPositionMs / 1000L,
                    chapterDurationSeconds = playerState.durationMs / 1000L,
                    onSeek = onSeek,
                    onBookSeek = onBookSeek
                )

                Spacer(Modifier.height(AppDimens.SpaceMd))
                TransportControls(
                    isPlaying = playerState.isPlaying,
                    onPreviousChapter = onPreviousChapter,
                    onBack = onBack,
                    onPlayPause = onPlayPause,
                    onForward = onForward,
                    onNextChapter = onNextChapter
                )

                if (playerState.canUndoSeek) {
                    TextButton(
                        onClick = onUndoSeek,
                        modifier = Modifier
                            .heightIn(min = AppDimens.TouchTarget)
                            .testTag("undo_seek_row")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
                        Spacer(Modifier.width(AppDimens.SpaceSm))
                        Text("Повернутися до ${MainViewModel.formatTime(playerState.undoFromPositionMs / 1000L)}")
                    }
                }

                Spacer(Modifier.height(AppDimens.SpaceMd))
                QuickTools(
                    speed = playerState.playbackSpeed,
                    timerMinutes = playerState.sleepTimerMinutes,
                    onSpeed = onSpeed,
                    onTimer = onTimer,
                    onBookmark = onBookmark,
                    onChapters = onChapters
                )
                Spacer(Modifier.height(AppDimens.SpaceLg))
                // The player is a full-screen OVERLAY rendered outside the host
                // Scaffold, so it gets no bottom inset from it. Pad past the
                // system navigation bar / gesture zone so the quick-tools row
                // is never hidden behind it (3-button nav is ~48dp tall).
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun PlayerTopBar(
    isFavorite: Boolean,
    isOffline: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleDebug: () -> Unit
) {
    var showMenu by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .padding(horizontal = AppDimens.SpaceSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(AppDimens.TouchTarget).testTag("close_player_button")
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Згорнути програвач", modifier = Modifier.size(30.dp))
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "ЗАРАЗ ЗВУЧИТЬ",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (isOffline) {
                Text("Офлайн", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            IconButton(
                onClick = { showMenu = true },
                modifier = Modifier.size(AppDimens.TouchTarget).testTag("player_actions_button")
            ) {
                Icon(Icons.Default.MoreVert, contentDescription = "Дії з аудіокнигою")
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(if (isFavorite) "Прибрати з обраного" else "Додати в обране") },
                    leadingIcon = { Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null) },
                    onClick = { onToggleFavorite(); showMenu = false }
                )
                if (BuildConfig.DEBUG) {
                    DropdownMenuItem(
                        text = { Text("Діагностика відтворення") },
                        leadingIcon = { Icon(Icons.Default.BugReport, null) },
                        onClick = { onToggleDebug(); showMenu = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun DualProgress(
    progress: PlayerProgressUi,
    chapterPositionSeconds: Long,
    chapterDurationSeconds: Long,
    onSeek: (Float) -> Unit,
    onBookSeek: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Розділ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Spec-22 T2: tabular (monospace) digits — the timer ticks without
            // shifting its advance width, so the layout never jitters.
            Text(
                "${MainViewModel.formatTime(chapterPositionSeconds)}  /  ${MainViewModel.formatTime(chapterDurationSeconds)}",
                style = TabularTimerStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = progress.chapterFraction,
            onValueChange = onSeek,
            modifier = Modifier.fillMaxWidth().testTag("player_progress_slider"),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
            )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Книга", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${MainViewModel.formatTime(progress.bookPositionSeconds)}  /  ${MainViewModel.formatTime(progress.bookDurationSeconds)}",
                style = TabularTimerStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        BookProgressTrack(progress, onBookSeek)
    }
}

@Composable
private fun BookProgressTrack(progress: PlayerProgressUi, onSeek: (Float) -> Unit) {
    val active = MaterialTheme.colorScheme.primary
    val activeMarker = MaterialTheme.colorScheme.onPrimary
    val inactiveMarker = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier = Modifier.fillMaxWidth().testTag("book_progress_track")) {
        Slider(
            value = progress.bookFraction,
            onValueChange = onSeek,
            enabled = progress.bookDurationSeconds > 0L,
            modifier = Modifier.fillMaxWidth().testTag("book_progress_slider"),
            colors = SliderDefaults.colors(
                thumbColor = active,
                activeTrackColor = active,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
            )
        )
        Canvas(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(24.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            val centerY = size.height / 2f
            progress.chapterMarkers.forEach { marker ->
                val color = if (marker <= progress.bookFraction) activeMarker.copy(alpha = 0.72f) else inactiveMarker
                drawMarker(marker, color, size.height, 1.5.dp.toPx())
            }
            progress.bookmarkMarkers.forEach { marker ->
                val color = if (marker <= progress.bookFraction) activeMarker else active
                drawCircle(color, radius = 3.5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * marker, centerY))
            }
        }
    }
}

private fun DrawScope.drawMarker(fraction: Float, color: Color, height: Float, width: Float) {
    translate(left = size.width * fraction) {
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(0f, (size.height - height) / 2f),
            end = androidx.compose.ui.geometry.Offset(0f, (size.height + height) / 2f),
            strokeWidth = width,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun TransportControls(
    isPlaying: Boolean,
    onPreviousChapter: () -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onNextChapter: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TransportIcon(Icons.Default.SkipPrevious, "Попередній розділ", onPreviousChapter)
        Rewind15Button(onBack)
        FilledIconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(72.dp).testTag("player_play_pause_button"),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Пауза" else "Відтворити",
                modifier = Modifier.size(40.dp)
            )
        }
        TransportIcon(Icons.Default.Forward30, "Вперед на 30 секунд", onForward)
        TransportIcon(Icons.Default.SkipNext, "Наступний розділ", onNextChapter)
    }
}

@Composable
private fun TransportIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(AppDimens.TouchTarget)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun Rewind15Button(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(AppDimens.TouchTarget)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Replay, contentDescription = "Назад на 15 секунд", modifier = Modifier.size(30.dp))
            Text(
                "15",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun QuickTools(
    speed: Float,
    timerMinutes: Int,
    onSpeed: () -> Unit,
    onTimer: () -> Unit,
    onBookmark: () -> Unit,
    onChapters: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        QuickTool(Icons.Default.Speed, "Швидкість", "${speed}×", "speed_chip", onSpeed)
        QuickTool(
            Icons.Default.Bedtime,
            "Таймер",
            when {
                timerMinutes > 0 -> "$timerMinutes хв"
                timerMinutes == -1 -> "До кінця"
                else -> null
            },
            "sleep_timer_chip",
            onTimer
        )
        QuickTool(Icons.Default.BookmarkAdd, "Закладка", null, "add_bookmark_chip", onBookmark)
        QuickTool(Icons.Default.FormatListNumbered, "Розділи", null, "chapters_chip", onChapters)
    }
}

@Composable
private fun RowScope.QuickTool(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String?,
    testTag: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(AppDimens.RadiusCard))
            .clickable(onClick = onClick)
            .padding(vertical = AppDimens.SpaceSm)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(AppDimens.SpaceXs))
        Text(value ?: label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        if (value != null) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkBottomSheet(
    timestampSeconds: Long,
    chapterTitle: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var note by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AppDimens.SpaceXl, vertical = AppDimens.SpaceMd)) {
            Text("Додати закладку", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(AppDimens.SpaceXs))
            Text(
                "$chapterTitle · ${MainViewModel.formatTime(timestampSeconds)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(AppDimens.SpaceLg))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Нотатка (необов’язково)") },
                modifier = Modifier.fillMaxWidth().testTag("bookmark_note_input"),
                minLines = 2,
                maxLines = 3
            )
            Spacer(Modifier.height(AppDimens.SpaceLg))
            Button(
                onClick = { onSave(note); onDismiss() },
                modifier = Modifier.fillMaxWidth().heightIn(min = AppDimens.TouchTarget).testTag("save_bookmark_button")
            ) { Text("Зберегти закладку") }
            Spacer(Modifier.height(AppDimens.SpaceLg))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterBottomSheet(
    chapters: List<ChapterEntity>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AppDimens.SpaceXl)) {
            Text("Розділи", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(AppDimens.SpaceMd))
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = AppDimens.TouchTarget)
                            .clickable { onSelect(index) }
                            .padding(vertical = AppDimens.SpaceMd),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (index == selectedIndex) {
                            Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(AppDimens.SpaceMd))
                        }
                        Text(
                            chapter.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (index == selectedIndex) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Text(
                            MainViewModel.formatTime(chapter.durationSeconds),
                            // Spec-22 T2: tabular figures for durations — no
                            // digit-width drift in the chapter list either.
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                }
            }
            Spacer(Modifier.height(AppDimens.SpaceXl))
        }
    }
}

/** Average a small software bitmap; the tint is decorative and always low-alpha. */
private fun extractArtworkAccent(drawable: Drawable): Color? = runCatching {
    val bitmap = drawable.toBitmap(width = 24, height = 24, config = Bitmap.Config.ARGB_8888)
    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0L
    for (y in 0 until bitmap.height step 2) {
        for (x in 0 until bitmap.width step 2) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.alpha(pixel) < 128) continue
            red += android.graphics.Color.red(pixel)
            green += android.graphics.Color.green(pixel)
            blue += android.graphics.Color.blue(pixel)
            count++
        }
    }
    if (count == 0L) null else Color((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
}.getOrNull()
