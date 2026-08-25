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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.ui.theme.AppDimens

@Composable
fun MiniPlayerBar(
    playerState: PlayerState,
    onPlayPauseClick: () -> Unit,
    onSkipNextClick: () -> Unit,
    onBarClick: () -> Unit,
    modifier: Modifier = Modifier
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(AppDimens.RadiusPanel))
                // Floating bar: tonally elevated above the scrolling content
                // (surfaceContainer cards) and consistent with the nav bar.
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(AppDimens.RadiusPanel))
                .testTag("mini_player_bar")
        ) {
            // Linear Progress Bar at the very top of Mini Player
            val progress = if (playerState.durationMs > 0) {
                (playerState.currentPositionMs.toFloat() / playerState.durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clearAndSetSemantics { },
                color = MaterialTheme.colorScheme.primary,
                // Theme-aware track (MD3: never a raw white on the tonal bar).
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )

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
                        .semantics { stateDescription = summaryState }
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
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface
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
                            text = chapterTitle,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

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
        }
    }
}
