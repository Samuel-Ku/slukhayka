package com.slukhayka.audiobooks.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.player.PlayerState
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.theme.*

@Composable
fun PlayerDebugOverlay(
    playerState: PlayerState,
    onClose: () -> Unit,
    onRetryPlayback: (() -> Unit)? = null,
    // wayfinder #52 session telemetry: recent ring-buffer events, the metrics
    // one-liner, and the full journal payload for the copy button.
    events: List<String> = emptyList(),
    metricsSummary: String = "",
    journalExport: String = "",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(true) }
    val workTitle = playerState.currentBook?.title.orEmpty()
    val expandDescription = stringResource(
        if (isExpanded) R.string.a11y_debug_collapse else R.string.a11y_debug_expand
    )
    val closeDescription = stringResource(R.string.a11y_debug_close)
    val copyStreamDescription = stringResource(R.string.a11y_debug_copy_stream, workTitle)
    val copyJournalDescription = stringResource(R.string.a11y_debug_copy_journal, workTitle)
    val retryDescription = stringResource(R.string.a11y_debug_retry_audio, workTitle)

    Card(
        colors = CardDefaults.cardColors(containerColor = AppDebugPanel.copy(alpha = 0.95f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        shape = RoundedCornerShape(AppDimens.RadiusPanel),
        modifier = modifier
            .fillMaxWidth()
            .testTag("player_debug_overlay")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.debug_overlay_title),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier
                            .size(AppDimens.TouchTarget)
                            .semantics { contentDescription = expandDescription }
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(AppDimens.TouchTarget)
                            .semantics { contentDescription = closeDescription }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    // Status indicators grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        DebugStatusBadge(
                            label = "STATE",
                            value = if (playerState.isPlaying) "PLAYING" else if (playerState.isBuffering) "BUFFERING" else "IDLE / PAUSED",
                            color = if (playerState.isPlaying) AppDebugOk else if (playerState.isBuffering) AppDebugWarn else AppDebugError
                        )

                        DebugStatusBadge(
                            label = "BUFFERING",
                            value = if (playerState.isBuffering) "YES" else "NO",
                            color = if (playerState.isBuffering) AppDebugWarn else AppDebugOk
                        )

                        DebugStatusBadge(
                            label = "ENGINE",
                            value = playerState.audioEngineMode.take(16),
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Media Source URL
                    Surface(
                        color = AppDebugPanelInner,
                        shape = RoundedCornerShape(AppDimens.RadiusInner),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "SOURCE URL BEING LOADED:",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Source URL", playerState.currentStreamUrl)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, R.string.debug_stream_copied, Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier
                                        .size(AppDimens.TouchTarget)
                                        .semantics { contentDescription = copyStreamDescription }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = playerState.currentStreamUrl.ifBlank { "Неможливо отримати пряме посилання (Stream URL Empty)" },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                ),
                                color = if (playerState.currentStreamUrl.isNotBlank()) MaterialTheme.colorScheme.primary else AppDebugError,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Detailed metrics
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Position: ${MainViewModel.formatTime(playerState.currentPositionMs / 1000L)} / ${MainViewModel.formatTime((playerState.durationMs / 1000L).coerceAtLeast(0L))}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = "Chapter: ${playerState.currentChapterIndex + 1}/${playerState.chapters.size.coerceAtLeast(1)}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (playerState.lastErrorMsg.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "⚠️ LOG: ${playerState.lastErrorMsg}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = AppDebugError
                            )
                        )
                    }

                    if (events.isNotEmpty() || metricsSummary.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = AppDebugPanelInner,
                            shape = RoundedCornerShape(AppDimens.RadiusInner),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "SESSION TELEMETRY",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    IconButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText(
                                                "Playback journal",
                                                metricsSummary + "\n" + journalExport
                                            )
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(context, R.string.debug_journal_copied, Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier
                                            .size(AppDimens.TouchTarget)
                                            .semantics { contentDescription = copyJournalDescription }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                if (metricsSummary.isNotBlank()) {
                                    Text(
                                        text = metricsSummary,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp
                                        ),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                events.forEach { event ->
                                    Text(
                                        text = event,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            // Spec-22 T2: no user-facing text below 11sp.
                                            fontSize = 11.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Controls in overlay
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onRetryPlayback != null) {
                            Button(
                                onClick = onRetryPlayback,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(AppDimens.RadiusInner),
                                modifier = Modifier
                                    .heightIn(min = AppDimens.TouchTarget)
                                    .semantics { contentDescription = retryDescription }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.debug_retry_audio), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugStatusBadge(
    label: String,
    value: String,
    color: Color
) {
    Surface(
        color = color.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(AppDimens.RadiusXs)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    // Spec-22 T2: no user-facing text below 11sp.
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = color
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = color
            )
        }
    }
}
