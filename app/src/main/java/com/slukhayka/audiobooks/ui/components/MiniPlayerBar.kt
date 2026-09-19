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
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.ui.theme.AppDimens

/**
 * How much of the bar's width a leftward swipe must cover to close the player.
 *
 * Shorter than the platform default (half the width): the bar is a persistent
 * surface the listener wants out of the way, not a list row they are deciding
 * about, so the gesture answers intent rather than distance.
 */
private const val MiniPlayerDismissWidthFraction = 0.35f

@Composable
fun MiniPlayerBar(
    playerState: PlayerState,
    onPlayPauseClick: () -> Unit,
    onSkipNextClick: () -> Unit,
    onBarClick: () -> Unit,
    // Closing the player: the bar leaves the screen and the host pauses
    // playback. Issue #808 draws no cross here — a fifth 48 dp target would
    // squeeze the title — so the gesture is the visible door and the same
    // action is exposed to TalkBack as a custom action on the summary node
    // below. Both paths run through onCloseClick.
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Issue #808: previous chapter is one of the bar's three transport
    // controls. It replaces the cast affordance, which now lives only on the
    // full player — the bar is about this chapter, not about devices.
    onPreviousClick: () -> Unit = {},
    viewedBookId: String? = null
) {
    val book = playerState.currentBook ?: return
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

    // Issue #808: one dismissal state behind every way out — the leftward
    // swipe and the TalkBack custom action on the summary node below. The bar
    // slides out to the left, then the host takes it away (and pauses playback).
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * MiniPlayerDismissWidthFraction }
    )
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) onCloseClick()
    }

    // Phase 2.5 hotfix (compile warning at MiniPlayerBar.kt:46): the parent
    // already early-returns when currentBook is null, so the AnimatedVisibility
    // gate was always-true. Render the bar directly; keep the slide animation
    // for nicer transitions when a book is set then cleared.
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
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppDimens.RadiusPanel))
                    // Floating bar: tonally elevated above the scrolling content
                    // (surfaceContainer cards) and consistent with the nav bar.
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppDimens.RadiusPanel))
                    .testTag("mini_player_bar")
            ) {
                // #885 — the prototype draws the progress line along the BOTTOM
                // edge of the bar, right under the title row.
                val progress = if (playerState.durationMs > 0) {
                    (playerState.currentPositionMs.toFloat() / playerState.durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = AppDimens.TouchTarget)
                            .semantics {
                                stateDescription = summaryState
                                // Issue #808: the bar has no cross, so TalkBack
                                // — which cannot perform the swipe — gets the
                                // same close through the actions menu.
                                customActions = listOf(
                                    CustomAccessibilityAction(closeDescription) { onCloseClick(); true }
                                )
                            }
                            .clickable(role = Role.Button, onClick = onBarClick)
                            .testTag("mini_player_summary"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BookCoverImage(
                            book = book,
                            semantics = BookCoverSemantics.Decorative,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(AppDimens.RadiusInner)),
                            contentScale = ContentScale.Crop
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = book.title,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    // Issue #808: one line, not two — the bar's
                                    // height no longer jumps between books.
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    // The control row is full at 360 dp; the
                                    // snapshot test measures this box to prove
                                    // the title keeps real room (and the cover
                                    // placeholder draws the same title, so the
                                    // tag is what disambiguates them).
                                    modifier = Modifier.testTag("mini_player_title")
                                )
                                if (playerState.isOfflineMode) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Default.CloudDone,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }

                            Text(
                                text = if (viewedBookId != null && viewedBookId != book.id) {
                                    stringResource(R.string.mini_player_other_book_chapter, chapterTitle)
                                } else chapterTitle,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Issue #808: three transport controls — previous chapter,
                    // play/pause, next chapter. The bar has no cast button (the
                    // full player owns it) and no cross (the swipe closes it),
                    // so the row stays at four 48 dp targets and the title keeps
                    // real room at 360 dp.
                    IconButton(
                        onClick = onPreviousClick,
                        modifier = Modifier
                            .size(AppDimens.TouchTarget)
                            .testTag("mini_player_previous")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = stringResource(R.string.a11y_previous_chapter_work, book.title),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Play/Pause Button
                    IconButton(
                        onClick = onPlayPauseClick,
                        modifier = Modifier
                            .size(AppDimens.TouchTarget)
                            .testTag("mini_player_play_pause")
                    ) {
                        Icon(
                            imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            // Spec-27 (#204): Ukrainian everywhere — the EN
                            // «Pause»/«Play» descs were a leftover from the
                            // localization pass (2026-08-17).
                            contentDescription = stringResource(
                                if (playerState.isPlaying) R.string.a11y_pause_work else R.string.a11y_play_work,
                                book.title
                            ),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Skip Next Button
                    IconButton(
                        onClick = onSkipNextClick,
                        modifier = Modifier
                            .size(AppDimens.TouchTarget)
                            .testTag("mini_player_next")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = stringResource(R.string.a11y_next_chapter_work, book.title),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                // #885 — the tag lives on a wrapper Box: the indicator itself uses
                // clearAndSetSemantics (it is decoration), and that call would wash
                // the tag away, leaving the geometry test with nothing to measure.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .testTag("mini_player_progress")
                ) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxSize()
                            .clearAndSetSemantics { },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    )
                }
            }
        }
    }
}
