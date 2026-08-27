package com.slukhayka.audiobooks.ui.screens

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.slukhayka.audiobooks.BuildConfig
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.BookmarkEntity
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.player.SleepTimerNotice
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.BookCoverImage
import com.slukhayka.audiobooks.ui.components.BookCoverSemantics
import com.slukhayka.audiobooks.ui.components.CastButton
import com.slukhayka.audiobooks.ui.components.PlayerDebugOverlay
import com.slukhayka.audiobooks.ui.components.RestoreFocusAfterModal
import com.slukhayka.audiobooks.ui.components.SleepTimerSheet
import com.slukhayka.audiobooks.ui.components.SpeedSheet
import com.slukhayka.audiobooks.ui.components.formatSpeed
import com.slukhayka.audiobooks.ui.components.formatSpeedForSpeech
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.displayNarrator
import com.slukhayka.audiobooks.ui.library.effectiveChapterDurations
import com.slukhayka.audiobooks.ui.theme.AppDimens
import com.slukhayka.audiobooks.ui.theme.TabularTimerStyle

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

enum class PlayerQuickTool {
    Speed,
    Timer,
    Bookmark,
    Chapters,
    Bookmarks
}



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

/** The one predictable target for returning to the most recently created bookmark. */
fun lastCreatedBookmark(bookmarks: List<BookmarkEntity>): BookmarkEntity? =
    bookmarks.maxWithOrNull(compareBy({ it.createdAt }, { it.id }))

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
    val lastBookmarkTarget = remember(bookmarks) { lastCreatedBookmark(bookmarks) }
    val castReady = remember(playerState.currentStreamUrl) {
        runCatching { App.instance.castController.isCastAvailable() }.getOrDefault(false) &&
            playerState.currentStreamUrl.let { it.startsWith("https://") || it.startsWith("http://") }
    }

    var activeTool by rememberSaveable { mutableStateOf<PlayerQuickTool?>(null) }
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
    val playerContext = LocalContext.current
    val fadeWarningFeedback = stringResource(R.string.a11y_timer_fade_warning)
    LaunchedEffect(pendingFeedback) {
        pendingFeedback?.let { message ->
            // Wait for the bottom sheet's dismissal animation before surfacing
            // the confirmation, so the snackbar never renders beneath it.
            kotlinx.coroutines.delay(300)
            snackbarHostState.showSnackbar(message)
            pendingFeedback = null
        }
    }
    LaunchedEffect(viewModel.playerManager, fadeWarningFeedback, playerContext) {
        viewModel.playerManager.sleepTimerNotices.collect { notice ->
            pendingFeedback = when (notice) {
                SleepTimerNotice.FadeWarning -> fadeWarningFeedback
                is SleepTimerNotice.Extended -> playerContext.getString(
                    R.string.a11y_timer_extended,
                    MainViewModel.formatTime(notice.remainingSeconds.toLong())
                )
            }
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
        PlayerModalUnderlay(
            activeTool = activeTool,
            modifier = Modifier.fillMaxSize()
        ) {
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
            onSpeed = { activeTool = PlayerQuickTool.Speed },
            onTimer = { activeTool = PlayerQuickTool.Timer },
            onBookmark = { activeTool = PlayerQuickTool.Bookmark },
            onChapters = { activeTool = PlayerQuickTool.Chapters },
            onRetryPlayback = {
                viewModel.playerManager.prepareChapter(
                    playerState.currentChapterIndex,
                    playerState.currentPositionMs,
                    autoPlay = true
                )
            },
            activeTool = activeTool,
            castReady = castReady,
            lastBookmarkTarget = lastBookmarkTarget,
            onJumpToBookmark = viewModel::jumpToBookmark,
            onShowAllBookmarks = { activeTool = PlayerQuickTool.Bookmarks }
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

    when (activeTool) {
        PlayerQuickTool.Timer -> SleepTimerSheet(
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
                else null
                activeTool = null
            },
            onExtendTimer = {
                if (viewModel.playerManager.extendSleepTimerBy15Minutes() > 0) {
                    activeTool = null
                }
            },
            onDismiss = { activeTool = null }
        )
        PlayerQuickTool.Speed -> SpeedSheet(
            currentSpeed = playerState.playbackSpeed,
            onSpeedChange = viewModel.playerManager::setPlaybackSpeed,
            onSaveForBook = {
                viewModel.playerManager.savePreferredSpeed(playerState.playbackSpeed)
                activeTool = null
            },
            onSetDefault = {
                viewModel.playerManager.setDefaultSpeed(playerState.playbackSpeed)
                activeTool = null
            },
            onDismiss = { activeTool = null }
        )
        PlayerQuickTool.Bookmark -> BookmarkBottomSheet(
            timestampSeconds = playerState.currentPositionMs / 1000L,
            chapterTitle = currentChapterTitle,
            onDismiss = { activeTool = null },
            onSave = {
                viewModel.addBookmarkAtCurrentPosition(it)
                // Spec-27 (#207): the feedback names the position where the
                // bookmark landed (US-19) — «Закладку додано на 2:35:44».
                pendingFeedback = "Закладку додано на ${MainViewModel.formatTime(playerState.currentPositionMs / 1000L)}"
                activeTool = null
            }
        )
        PlayerQuickTool.Chapters -> ChapterBottomSheet(
            chapters = playerState.chapters,
            selectedIndex = playerState.currentChapterIndex,
            onSelect = { index ->
                viewModel.playerManager.selectChapter(index)
                activeTool = null
            },
            onDismiss = { activeTool = null }
        )
        PlayerQuickTool.Bookmarks -> BookmarksListSheet(
            workTitle = book.title,
            bookmarks = bookmarks.sortedWith(compareBy({ it.chapterIndex }, { it.timestampSeconds })),
            onSelect = { bookmark ->
                viewModel.jumpToBookmark(bookmark)
                activeTool = null
            },
            onDelete = { bookmark ->
                scope.launch { viewModel.listeningState.deleteBookmark(bookmark.id) }
            },
            onDismiss = { activeTool = null }
        )
        null -> Unit
    }
}

/**
 * Owns every non-modal player surface as one accessibility background.
 * Snackbar and debug feedback must disappear from TalkBack traversal with
 * the player body while any quick-tool sheet is modal.
 */
@Composable
internal fun PlayerModalUnderlay(
    activeTool: PlayerQuickTool?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .testTag("player_modal_underlay")
            .accessibilityModalBackground(activeTool != null),
        content = content
    )
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
    onRetryPlayback: () -> Unit,
    modifier: Modifier = Modifier,
    activeTool: PlayerQuickTool? = null,
    castReady: Boolean = false,
    lastBookmarkTarget: BookmarkEntity? = null,
    onJumpToBookmark: (BookmarkEntity) -> Unit = {},
    onShowAllBookmarks: () -> Unit = {}
) {
    val background = MaterialTheme.colorScheme.background
    val tint = artworkAccent ?: MaterialTheme.colorScheme.primary
    val largeFont = LocalDensity.current.fontScale >= 2f
    val contentScrollState = rememberScrollState()
    val playerContextFocusRequester = remember { FocusRequester() }
    val speedFocusRequester = remember { FocusRequester() }
    val timerFocusRequester = remember { FocusRequester() }
    val bookmarkFocusRequester = remember { FocusRequester() }
    val chaptersFocusRequester = remember { FocusRequester() }
    val bookmarksFocusRequester = remember { FocusRequester() }
    var restoreTool by remember { mutableStateOf<PlayerQuickTool?>(null) }
    val playerNarrator = book.displayNarrator
    val editionDescription = if (playerNarrator.isNotBlank()) {
        stringResource(R.string.a11y_player_edition, playerNarrator)
    } else {
        stringResource(R.string.a11y_player_edition_unknown)
    }
    val playerContextDescription = stringResource(
        R.string.a11y_player_context,
        book.title,
        book.displayAuthor,
        editionDescription,
        currentChapterTitle
    )

    LaunchedEffect(playerContextFocusRequester) {
        playerContextFocusRequester.requestFocus()
    }
    LaunchedEffect(activeTool) {
        if (activeTool != null) {
            restoreTool = activeTool
        }
    }
    val toolReturnFocusRequester = when (restoreTool) {
        PlayerQuickTool.Speed -> speedFocusRequester
        PlayerQuickTool.Timer -> timerFocusRequester
        PlayerQuickTool.Bookmark -> bookmarkFocusRequester
        PlayerQuickTool.Chapters -> chaptersFocusRequester
        PlayerQuickTool.Bookmarks -> if (lastBookmarkTarget != null) {
            bookmarksFocusRequester
        } else {
            playerContextFocusRequester
        }
        null -> null
    }
    RestoreFocusAfterModal(
        modalVisible = activeTool != null,
        returnFocusRequester = toolReturnFocusRequester,
        onFocusRestored = { restoreTool = null }
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("full_player_screen")
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
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PlayerTopBar(
                bookTitle = book.title,
                isFavorite = book.isFavorite,
                isOffline = playerState.isOfflineMode,
                onDismiss = onDismiss,
                onToggleFavorite = onToggleFavorite,
                onToggleDebug = onToggleDebug
            )

            // Spec-24 T1: the player fits ONE screen — the scroll wrapper is
            // gone and the cover block is the flexible element (weight-based,
            // aspect preserved, capped), so every control below stays visible.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (largeFont) Modifier.verticalScroll(contentScrollState)
                        else Modifier
                    )
                    .fillMaxWidth()
                    .padding(horizontal = AppDimens.PageSides),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // The cover absorbs the leftover vertical space: when it is
                // tight the cover shrinks (the aspect-ratio sizing honours the
                // bounded height), never pushing the transport row off-screen.
                val coverMaxHeight = if (lastBookmarkTarget != null) 288.dp else 336.dp
                Box(
                    modifier = (if (largeFont) {
                        Modifier.height(208.dp)
                    } else {
                        Modifier.weight(1f)
                    })
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    // Soft glow behind the cover — tinted with the artwork accent
                    // (or the brand accent before the cover loads) so the shadow
                    // hue matches the scene instead of reading as flat black.
                    // Sized and painted in dp so the falloff is density-stable.
                    val glowRadiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { 300.dp.toPx() }
                    // The glow mirrors the cover's (real) aspect ratio instead
                    // of a hard-coded square, so the halo hugs the artwork.
                    // aspectRatio honours the heightIn cap (so the cover can
                    // never blow up on tablets) and shrinks the WIDTH to match
                    // when vertical space is tight — a tall cover is scaled
                    // (never cropped), and the whole column always fits.
                    val coverAspect = artworkAspect.coerceIn(0.6f, 1.6f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .heightIn(max = coverMaxHeight)
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
                        // Test seam: the tight-viewport snapshot measures the
                        // cover to pin that it shrinks while keeping its aspect
                        // ratio (spec-24 T6).
                        modifier = Modifier
                            .widthIn(max = 272.dp)
                            .heightIn(max = coverMaxHeight)
                            .fillMaxWidth(0.76f)
                            .aspectRatio(coverAspect)
                            .testTag("player_cover")
                    ) {
                        BookCoverImage(
                            book = book,
                            semantics = BookCoverSemantics.Decorative,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            onImageLoaded = onArtworkLoaded
                        )
                    }
                }

                Spacer(Modifier.height(AppDimens.SpaceSm))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("player_context")
                        .focusRequester(playerContextFocusRequester)
                        .focusable()
                        .clearAndSetSemantics {
                            contentDescription = playerContextDescription
                            heading()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = if (largeFont) 3 else 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(AppDimens.SpaceXs))
                    Text(
                        text = book.displayAuthor,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (largeFont) 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Spec-20 T2: narrator renders only when real — the fabricated
                    // "4read Voice Narrator" placeholder is scrubbed away. The
                    // real narrator lands once the book page fetch back-fills it.
                    if (playerNarrator.isNotBlank()) {
                        Text(
                            text = "Читає $playerNarrator",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (largeFont) 2 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(AppDimens.SpaceSm))
                    Text(
                        text = currentChapterTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (largeFont) 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (playerState.lastErrorMsg.isNotBlank()) {
                    Spacer(Modifier.height(AppDimens.SpaceMd))
                    PlayerPlaybackError(
                        bookTitle = book.title,
                        detail = playerState.lastErrorMsg,
                        onRetryPlayback = onRetryPlayback
                    )
                }

                Spacer(Modifier.height(AppDimens.SpaceLg))
                DualProgress(
                    progress = progress,
                    chapterPositionSeconds = playerState.currentPositionMs / 1000L,
                    chapterDurationSeconds = playerState.durationMs / 1000L,
                    onSeek = onSeek,
                    onBookSeek = onBookSeek,
                    bookmarkTarget = lastBookmarkTarget,
                    bookmarkFocusRequester = bookmarksFocusRequester,
                    onJumpToBookmark = onJumpToBookmark,
                    onShowAllBookmarks = onShowAllBookmarks
                )

                Spacer(Modifier.height(AppDimens.SpaceMd))
                TransportControls(
                    bookTitle = book.title,
                    currentChapterTitle = currentChapterTitle,
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
                    castReady = castReady,
                    onSpeed = onSpeed,
                    onTimer = onTimer,
                    onBookmark = onBookmark,
                    onChapters = onChapters,
                    speedFocusRequester = speedFocusRequester,
                    timerFocusRequester = timerFocusRequester,
                    bookmarkFocusRequester = bookmarkFocusRequester,
                    chaptersFocusRequester = chaptersFocusRequester
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
    bookTitle: String,
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
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.a11y_player_close),
                modifier = Modifier.size(30.dp)
            )
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
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.a11y_player_actions, bookTitle)
                )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DualProgress(
    progress: PlayerProgressUi,
    chapterPositionSeconds: Long,
    chapterDurationSeconds: Long,
    onSeek: (Float) -> Unit,
    onBookSeek: (Float) -> Unit,
    bookmarkTarget: BookmarkEntity? = null,
    bookmarkFocusRequester: FocusRequester,
    onJumpToBookmark: (BookmarkEntity) -> Unit = {},
    onShowAllBookmarks: () -> Unit = {}
) {
    val chapterPositionLabel = stringResource(R.string.a11y_player_chapter_position)
    val bookPositionLabel = stringResource(R.string.a11y_player_book_position)
    val chapterTimeDescription = if (chapterDurationSeconds > 0L) {
        stringResource(
            R.string.a11y_player_time_of,
            MainViewModel.formatTime(chapterPositionSeconds),
            MainViewModel.formatTime(chapterDurationSeconds)
        )
    } else {
        stringResource(R.string.a11y_player_duration_unknown)
    }
    val bookTimeDescription = if (progress.bookDurationSeconds > 0L) {
        stringResource(
            R.string.a11y_player_time_of,
            MainViewModel.formatTime(progress.bookPositionSeconds),
            MainViewModel.formatTime(progress.bookDurationSeconds)
        )
    } else {
        stringResource(R.string.a11y_player_duration_unknown)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .testTag("chapter_progress_visual_row")
                .semantics { hideFromAccessibility() },
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Розділ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Spec-22 T2: tabular (monospace) digits — the timer ticks without
            // shifting its advance width, so the layout never jitters.
            Text(
                if (chapterDurationSeconds > 0L) {
                    "${MainViewModel.formatTime(chapterPositionSeconds)}  /  ${MainViewModel.formatTime(chapterDurationSeconds)}"
                } else {
                    stringResource(R.string.a11y_player_duration_unknown)
                },
                style = TabularTimerStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = progress.chapterFraction,
            onValueChange = onSeek,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = chapterPositionLabel
                    stateDescription = chapterTimeDescription
                }
                .testTag("player_progress_slider"),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
            )
        )
        Row(
            Modifier
                .fillMaxWidth()
                .testTag("book_progress_visual_row")
                .semantics { hideFromAccessibility() },
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Книга", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (progress.bookDurationSeconds > 0L) {
                    "${MainViewModel.formatTime(progress.bookPositionSeconds)}  /  ${MainViewModel.formatTime(progress.bookDurationSeconds)}"
                } else {
                    stringResource(R.string.a11y_player_duration_unknown)
                },
                style = TabularTimerStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        BookProgressTrack(
            progress = progress,
            positionLabel = bookPositionLabel,
            timeDescription = bookTimeDescription,
            onSeek = onBookSeek,
            bookmarkTarget = bookmarkTarget,
            bookmarkFocusRequester = bookmarkFocusRequester,
            onJumpToBookmark = onJumpToBookmark,
            onShowAllBookmarks = onShowAllBookmarks
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookProgressTrack(
    progress: PlayerProgressUi,
    positionLabel: String,
    timeDescription: String,
    onSeek: (Float) -> Unit,
    bookmarkTarget: BookmarkEntity?,
    bookmarkFocusRequester: FocusRequester,
    onJumpToBookmark: (BookmarkEntity) -> Unit,
    onShowAllBookmarks: () -> Unit
) {
    val active = MaterialTheme.colorScheme.primary
    val activeMarker = MaterialTheme.colorScheme.onPrimary
    val inactiveMarker = MaterialTheme.colorScheme.onSurfaceVariant
    val bookmarkColor = MaterialTheme.colorScheme.tertiary
    Box(modifier = Modifier.fillMaxWidth().testTag("book_progress_track")) {
        Slider(
            value = progress.bookFraction,
            onValueChange = onSeek,
            enabled = progress.bookDurationSeconds > 0L,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = if (bookmarkTarget != null) 56.dp else 0.dp)
                .semantics {
                    contentDescription = positionLabel
                    stateDescription = timeDescription
                }
                .testTag("book_progress_slider"),
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
                .testTag("book_progress_markers")
                .semantics { hideFromAccessibility() }
        ) {
            val centerY = size.height / 2f
            progress.chapterMarkers.forEach { marker ->
                val color = if (marker <= progress.bookFraction) activeMarker.copy(alpha = 0.72f) else inactiveMarker
                drawMarker(marker, color, size.height, 1.5.dp.toPx())
            }
            progress.bookmarkMarkers.forEach { marker ->
                val color = if (marker <= progress.bookFraction) bookmarkColor else bookmarkColor.copy(alpha = 0.55f)
                drawCircle(color, radius = 5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * marker, centerY))
            }
        }
        if (bookmarkTarget != null) {
            val bookmarkTime = MainViewModel.formatTime(bookmarkTarget.timestampSeconds)
            val jumpDescription = stringResource(R.string.a11y_player_bookmark_jump, bookmarkTime)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(AppDimens.TouchTarget)
                    .focusRequester(bookmarkFocusRequester)
                    .focusable()
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = { onJumpToBookmark(bookmarkTarget) },
                        onLongClick = onShowAllBookmarks
                    )
                    .clearAndSetSemantics {
                        contentDescription = jumpDescription
                        onClick {
                            onJumpToBookmark(bookmarkTarget)
                            true
                        }
                        onLongClick {
                            onShowAllBookmarks()
                            true
                        }
                    }
                    .testTag("jump_to_last_bookmark"),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
    bookTitle: String,
    currentChapterTitle: String,
    isPlaying: Boolean,
    onPreviousChapter: () -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onNextChapter: () -> Unit
) {
    val previousDescription = stringResource(
        R.string.a11y_player_previous_chapter,
        bookTitle,
        currentChapterTitle
    )
    val backDescription = stringResource(R.string.a11y_player_seek_back, bookTitle, currentChapterTitle)
    val playDescription = stringResource(
        if (isPlaying) R.string.a11y_player_pause else R.string.a11y_player_play,
        bookTitle,
        currentChapterTitle
    )
    val playbackStateDescription = stringResource(
        if (isPlaying) R.string.a11y_player_state_playing else R.string.a11y_player_state_paused
    )
    val forwardDescription = stringResource(R.string.a11y_player_seek_forward, bookTitle, currentChapterTitle)
    val nextDescription = stringResource(
        R.string.a11y_player_next_chapter,
        bookTitle,
        currentChapterTitle
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TransportIcon(Icons.Default.SkipPrevious, previousDescription, onPreviousChapter)
        // Spec-27 (#207, BUG-012): the seek buttons carry a visible label
        // («15 с»/«30 с») below the icon. Mirroring Replay keeps both seek
        // controls visually paired without baking the number into either icon.
        SeekButton(Icons.Default.Replay, backDescription, "15 с", onBack)
        FilledIconButton(
            onClick = onPlayPause,
            modifier = Modifier
                .size(72.dp)
                .semantics {
                    contentDescription = playDescription
                    stateDescription = playbackStateDescription
                }
                .testTag("player_play_pause_button"),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
        }
        SeekButton(
            icon = Icons.Default.Replay,
            description = forwardDescription,
            label = "30 с",
            onClick = onForward,
            mirrorIcon = true
        )
        TransportIcon(Icons.Default.SkipNext, nextDescription, onNextChapter)
    }
}

@Composable
private fun TransportIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(AppDimens.TouchTarget)
            .semantics { contentDescription = description }
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun SeekButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    label: String,
    onClick: () -> Unit,
    mirrorIcon: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(AppDimens.TouchTarget)
                .semantics { contentDescription = description }
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .then(if (mirrorIcon) Modifier.scale(scaleX = -1f, scaleY = 1f) else Modifier)
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics { }
        )
    }
}

@Composable
private fun QuickTools(
    speed: Float,
    timerMinutes: Int,
    castReady: Boolean,
    onSpeed: () -> Unit,
    onTimer: () -> Unit,
    onBookmark: () -> Unit,
    onChapters: () -> Unit,
    speedFocusRequester: FocusRequester,
    timerFocusRequester: FocusRequester,
    bookmarkFocusRequester: FocusRequester,
    chaptersFocusRequester: FocusRequester
) {
    val speedLabel = stringResource(R.string.a11y_player_tool_speed)
    val speedVisualLabel = stringResource(R.string.player_tool_speed_label)
    val speedVisualValue = stringResource(R.string.player_speed_value, formatSpeed(speed))
    val speedState = stringResource(
        R.string.a11y_player_speed_value,
        formatSpeedForSpeech(speed)
    )
    val timerLabel = stringResource(R.string.a11y_player_tool_timer)
    val timerVisualLabel = stringResource(R.string.player_tool_timer_label)
    val timerState = when {
        timerMinutes > 0 -> pluralStringResource(
            R.plurals.a11y_player_timer_minutes,
            timerMinutes,
            timerMinutes
        )
        timerMinutes == -1 -> stringResource(R.string.a11y_player_timer_end_of_chapter)
        else -> stringResource(R.string.a11y_player_timer_off)
    }
    val timerVisualValue = when {
        timerMinutes > 0 -> stringResource(R.string.player_timer_minutes_value, timerMinutes)
        timerMinutes == -1 -> stringResource(R.string.player_timer_end_value)
        else -> null
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        QuickTool(
            Icons.Default.Speed,
            speedLabel,
            speedVisualLabel,
            speedVisualValue,
            speedState,
            "speed_chip",
            speedFocusRequester,
            onSpeed
        )
        QuickTool(
            Icons.Default.Bedtime,
            timerLabel,
            timerVisualLabel,
            timerVisualValue,
            timerState,
            "sleep_timer_chip",
            timerFocusRequester,
            onTimer
        )
        CastButton(castReady = castReady)
        QuickTool(
            Icons.Default.BookmarkAdd,
            stringResource(R.string.a11y_player_tool_bookmark),
            stringResource(R.string.player_tool_bookmark_label),
            null,
            null,
            "add_bookmark_chip",
            bookmarkFocusRequester,
            onBookmark
        )
        QuickTool(
            Icons.Default.FormatListNumbered,
            stringResource(R.string.a11y_player_tool_chapters),
            stringResource(R.string.player_tool_chapters_label),
            null,
            null,
            "chapters_chip",
            chaptersFocusRequester,
            onChapters
        )
    }
}

@Composable
private fun RowScope.QuickTool(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accessibilityLabel: String,
    visualLabel: String,
    visualValue: String?,
    accessibilityState: String?,
    testTag: String,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    val toolDescription = accessibilityState?.let {
        stringResource(R.string.a11y_player_tool_state, accessibilityLabel, it)
    } ?: accessibilityLabel
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 72.dp)
            .focusRequester(focusRequester)
            // A physical Android FocusRequester needs an explicit target;
            // clickable alone is not sufficient for modal return focus.
            .focusable()
            .clip(RoundedCornerShape(AppDimens.RadiusCard))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = toolDescription
            }
            .padding(vertical = AppDimens.SpaceSm)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(AppDimens.SpaceXs))
        Text(
            visualValue ?: visualLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .testTag("${testTag}_value")
                .semantics { hideFromAccessibility() }
        )
        if (visualValue != null) {
            Text(
                visualLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .testTag("${testTag}_label")
                    .semantics { hideFromAccessibility() }
            )
        }
    }
}

@Composable
private fun PlayerPlaybackError(
    bookTitle: String,
    detail: String,
    onRetryPlayback: () -> Unit
) {
    val errorTitle = stringResource(R.string.a11y_player_error_title)
    val retryLabel = stringResource(R.string.a11y_player_retry, bookTitle)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(AppDimens.SpaceMd)) {
            Text(
                text = errorTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .testTag("player_playback_error")
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
            if (!detail.contains("недоступна", ignoreCase = true)) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            OutlinedButton(
                onClick = onRetryPlayback,
                modifier = Modifier
                    .heightIn(min = AppDimens.TouchTarget)
                    .semantics {
                        contentDescription = retryLabel
                    }
            ) {
                Text(retryLabel, modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookmarksListSheet(
    workTitle: String,
    bookmarks: List<BookmarkEntity>,
    onSelect: (BookmarkEntity) -> Unit,
    onDelete: (BookmarkEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val headingFocusRequester = remember { FocusRequester() }
    var bookmarkToDelete by remember { mutableStateOf<BookmarkEntity?>(null) }
    var deleteOriginFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }
    var returnToHeadingAfterDelete by remember { mutableStateOf(false) }
    val paneTitle = stringResource(R.string.a11y_player_bookmarks_pane)
    RestoreFocusAfterModal(
        modalVisible = bookmarkToDelete != null,
        returnFocusRequester = if (returnToHeadingAfterDelete) {
            headingFocusRequester
        } else {
            deleteOriginFocusRequester
        },
        fallbackFocusRequester = headingFocusRequester,
        onFocusRestored = {
            deleteOriginFocusRequester = null
            returnToHeadingAfterDelete = false
        }
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .accessibilityPane(paneTitle)
            .accessibilityModalBackground(bookmarkToDelete != null)
            .testTag("bookmarks_sheet")
    ) {
        LaunchedEffect(headingFocusRequester) {
            withFrameNanos { }
            headingFocusRequester.requestFocus()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppDimens.PageSides, vertical = AppDimens.SpaceSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = paneTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .focusRequester(headingFocusRequester)
                    .focusable()
                    .semantics { heading() }
                    .testTag("bookmarks_sheet_heading")
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(AppDimens.TouchTarget)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.a11y_player_bookmarks_close)
                )
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = AppDimens.SpaceLg)
        ) {
            itemsIndexed(bookmarks, key = { _, bookmark -> bookmark.id }) { _, bookmark ->
                val timestamp = MainViewModel.formatTime(bookmark.timestampSeconds)
                val deleteFocusRequester = remember(bookmark.id) { FocusRequester() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = AppDimens.TouchTarget)
                        .clickable { onSelect(bookmark) }
                        .padding(horizontal = AppDimens.PageSides, vertical = AppDimens.SpaceSm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(AppDimens.SpaceSm))
                    Column(Modifier.weight(1f)) {
                        Text(bookmark.chapterTitle, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (bookmark.note.isBlank()) timestamp else "$timestamp · ${bookmark.note}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            deleteOriginFocusRequester = deleteFocusRequester
                            returnToHeadingAfterDelete = false
                            bookmarkToDelete = bookmark
                        },
                        modifier = Modifier
                            .size(AppDimens.TouchTarget)
                            .focusRequester(deleteFocusRequester)
                            .testTag("bookmarks_sheet_delete_${bookmark.id}")
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(
                                R.string.a11y_player_bookmark_delete,
                                bookmark.chapterTitle,
                                timestamp
                            ),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    bookmarkToDelete?.let { bookmark ->
        BookmarkDeleteConfirmation(
            workTitle = workTitle,
            bookmark = bookmark,
            onConfirm = {
                // The destructive action removes its own launcher. Return to
                // the sheet heading rather than trying to focus a stale row.
                returnToHeadingAfterDelete = true
                onDelete(bookmark)
                bookmarkToDelete = null
            },
            onDismiss = {
                returnToHeadingAfterDelete = false
                bookmarkToDelete = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookmarkBottomSheet(
    timestampSeconds: Long,
    chapterTitle: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var note by rememberSaveable { mutableStateOf("") }
    val headingFocusRequester = remember { FocusRequester() }
    val paneTitle = stringResource(R.string.a11y_bookmark_pane)
    val closeDescription = stringResource(R.string.a11y_bookmark_close)
    val positionDescription = stringResource(
        R.string.a11y_bookmark_context,
        chapterTitle,
        MainViewModel.formatTime(timestampSeconds)
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .accessibilityPane(paneTitle)
            .testTag("bookmark_sheet")
    ) {
        LaunchedEffect(headingFocusRequester) {
            withFrameNanos { }
            headingFocusRequester.requestFocus()
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = AppDimens.SpaceXl, vertical = AppDimens.SpaceMd)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    paneTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .focusRequester(headingFocusRequester)
                        .focusable()
                        .semantics { heading() }
                        .testTag("bookmark_sheet_heading")
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(AppDimens.TouchTarget)) {
                    Icon(Icons.Default.Close, contentDescription = closeDescription)
                }
            }
            Spacer(Modifier.height(AppDimens.SpaceXs))
            Text(
                "$chapterTitle · ${MainViewModel.formatTime(timestampSeconds)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag("bookmark_position_context")
                    .clearAndSetSemantics { contentDescription = positionDescription }
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
internal fun ChapterBottomSheet(
    chapters: List<ChapterEntity>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val headingFocusRequester = remember { FocusRequester() }
    val paneTitle = stringResource(R.string.a11y_chapter_pane)
    val closeDescription = stringResource(R.string.a11y_chapter_close)
    val currentDescription = stringResource(R.string.a11y_chapter_current)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .accessibilityPane(paneTitle)
            .testTag("chapter_sheet")
    ) {
        LaunchedEffect(headingFocusRequester) {
            withFrameNanos { }
            headingFocusRequester.requestFocus()
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = AppDimens.SpaceXl)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Розділи",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .focusRequester(headingFocusRequester)
                        .focusable()
                        .semantics { heading() }
                        .testTag("chapter_sheet_heading")
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(AppDimens.TouchTarget)) {
                    Icon(Icons.Default.Close, contentDescription = closeDescription)
                }
            }
            Spacer(Modifier.height(AppDimens.SpaceMd))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .selectableGroup()
            ) {
                itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
                    val isCurrent = index == selectedIndex
                    val visibleDuration = if (chapter.durationSeconds > 0L) {
                        MainViewModel.formatTime(chapter.durationSeconds)
                    } else {
                        stringResource(R.string.a11y_player_duration_unknown)
                    }
                    val spokenDuration = if (chapter.durationSeconds > 0L) {
                        visibleDuration
                    } else {
                        stringResource(R.string.a11y_duration_unknown_value)
                    }
                    val chapterDescription = stringResource(
                        R.string.a11y_chapter_option,
                        chapter.title,
                        spokenDuration,
                        if (isCurrent) " $currentDescription" else ""
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = AppDimens.TouchTarget)
                            .selectable(
                                selected = isCurrent,
                                role = Role.RadioButton,
                                onClick = { onSelect(index) }
                            )
                            .semantics { contentDescription = chapterDescription }
                            .padding(vertical = AppDimens.SpaceMd)
                            .testTag("chapter_option_$index"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isCurrent) {
                            Icon(Icons.Default.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(AppDimens.SpaceMd))
                        }
                        Text(
                            chapter.title,
                            modifier = Modifier
                                .weight(1f)
                                .clearAndSetSemantics { },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Text(
                            visibleDuration,
                            // Spec-22 T2: tabular figures for durations — no
                            // digit-width drift in the chapter list either.
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .testTag("chapter_option_duration_$index")
                                .semantics { hideFromAccessibility() }
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
