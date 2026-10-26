package com.slukhayka.audiobooks.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.ui.components.paperink.PaperInkMiniPlayerBar
import com.slukhayka.audiobooks.ui.theme.AppDimens
import com.slukhayka.audiobooks.ui.theme.PaperInkThemedContent

/**
 * How much of the bar's width a leftward swipe must cover to close the player.
 *
 * Shorter than the platform default (half the width): the bar is a persistent
 * surface the listener wants out of the way, not a list row they are deciding
 * about, so the gesture answers intent rather than distance.
 */
private const val MiniPlayerDismissWidthFraction = 0.35f

/**
 * #1205 — how wide the leading column must be to hold the mini-player under the
 * rail.
 *
 * 360 dp is not a new number: it is the narrow phone this bar's own snapshot
 * test names as the floor its control row survives (`MiniPlayerBarSnapshotTest`
 * — «360 dp is the narrow phone the control row has to survive»), and it keeps
 * the 64 dp the title box is asserted to hold there. Of the bar's width, 260 dp
 * is fixed chrome (48 dp cover + 24 dp outer and inner horizontal padding +
 * 20 dp spacers + three 48 dp targets, ADR-0044); the rest is the title. A
 * column narrower than this would not «fit» the bar, it would clip the title
 * out of it — the one thing the owner's decision refuses to do.
 */
val MiniPlayerColumnWidth: Dp = 360.dp

/**
 * #1205 — the mini-player's ONE gate: does this state give the bar anything to
 * draw?
 *
 * `MiniPlayerBar` returns early without a current book, and the composition root
 * has to ask the SAME question BEFORE it widens the leading column for the bar —
 * a column widened for a bar that draws nothing is a hole in the navigation
 * column. Both callers ask here, so the two answers cannot drift apart.
 */
fun miniPlayerHasBook(playerState: PlayerState): Boolean = playerState.currentBook != null

@Composable
fun MiniPlayerBar(
    playerState: PlayerState,
    onPlayPauseClick: () -> Unit,
    onBarClick: () -> Unit,
    // Closing the player: the bar leaves the screen and the host pauses
    // playback. The visible door is the leftward swipe; TalkBack gets the same
    // action as a custom action on the summary node.
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewedBookId: String? = null
) {
    // The one gate (#1205), asked in the same words by the composition root
    // before it widens the leading column for this bar.
    if (!miniPlayerHasBook(playerState)) return
    val book = checkNotNull(playerState.currentBook)
    val chapterTitle = if (
        playerState.chapters.isNotEmpty() &&
        playerState.currentChapterIndex in playerState.chapters.indices
    ) {
        playerState.chapters[playerState.currentChapterIndex].title
    } else {
        stringResource(R.string.a11y_chapter_number, playerState.currentChapterIndex + 1)
    }
    val playbackState = stringResource(
        if (playerState.isPlaying) R.string.a11y_playing else R.string.a11y_paused
    )
    val summaryState = listOfNotNull(
        playbackState,
        stringResource(R.string.a11y_available_offline).takeIf { playerState.isOfflineMode }
    ).joinToString(". ")
    val closeDescription = stringResource(R.string.a11y_mini_player_close)
    val playDescription = stringResource(
        if (playerState.isPlaying) R.string.a11y_pause_work else R.string.a11y_play_work,
        book.title
    )
    val progress = if (playerState.durationMs > 0) {
        (playerState.currentPositionMs.toFloat() / playerState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val meta = if (viewedBookId != null && viewedBookId != book.id) {
        stringResource(R.string.mini_player_other_book_chapter, chapterTitle)
    } else {
        chapterTitle
    }

    // Issue #808: one dismissal state behind every way out — the leftward
    // swipe and the TalkBack custom action on the summary node. The bar slides
    // out to the left, then the host takes it away (and pauses playback).
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * MiniPlayerDismissWidthFraction }
    )
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) onCloseClick()
    }

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        SwipeToDismissBox(
            state = dismissState,
            // Nothing waits behind the bar: closing it is a way out, not a
            // reveal of another destination.
            backgroundContent = {},
            // Leftward only — the bar is swiped off the way it reads.
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // The bar follows the window's scheme (chrome, not a night surface)
            // and uses the floating 64 dp / ring-progress primitive.
            PaperInkThemedContent {
                PaperInkMiniPlayerBar(
                    title = book.title,
                    meta = meta,
                    progress = progress,
                    isPlaying = playerState.isPlaying,
                    onPlayPauseClick = onPlayPauseClick,
                    playContentDescription = playDescription,
                    modifier = Modifier.testTag("mini_player_bar"),
                    cover = {
                        BookCoverImage(
                            book = book,
                            semantics = BookCoverSemantics.Decorative,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(AppDimens.RadiusInner)),
                            contentScale = ContentScale.Crop,
                            // The bar is chrome on any scheme: a missing cover is
                            // a quiet paper block, not the genre's neon tile.
                            placeholderAccentOverride = MaterialTheme.colorScheme.surfaceVariant,
                            placeholderContentColorOverride = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onBarClick = onBarClick,
                    summaryStateDescription = summaryState,
                    closeActionDescription = closeDescription,
                    onCloseAction = onCloseClick,
                    offline = playerState.isOfflineMode,
                    titleTestTag = "mini_player_title",
                    summaryTestTag = "mini_player_summary",
                    playTestTag = "mini_player_play_pause",
                )
            }
        }
    }
}
