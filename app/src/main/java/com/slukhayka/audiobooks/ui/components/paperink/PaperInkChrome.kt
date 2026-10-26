package com.slukhayka.audiobooks.ui.components.paperink

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.theme.PaperInkDimens
import com.slukhayka.audiobooks.ui.theme.PaperInkType

/**
 * The floating mini player of the direction: 64 dp, 24 dp radius, one 48 dp
 * play target whose ring is the progress — no underline that reads as a random
 * rule. The screen decides whether it appears at all: on «Слухати» and on the
 * book page it would duplicate the main action, so those screens hide it.
 */
@Composable
fun PaperInkMiniPlayerBar(
    title: String,
    meta: String,
    progress: Float,
    isPlaying: Boolean,
    onPlayPauseClick: () -> Unit,
    playContentDescription: String,
    modifier: Modifier = Modifier,
    // The screen supplies the real artwork; null falls back to the paper cover.
    cover: (@Composable () -> Unit)? = null,
    onBarClick: (() -> Unit)? = null,
    summaryStateDescription: String? = null,
    // TalkBack cannot swipe: the close action rides the summary node.
    closeActionDescription: String? = null,
    onCloseAction: (() -> Unit)? = null,
    offline: Boolean = false,
    titleTestTag: String? = null,
    summaryTestTag: String? = null,
    playTestTag: String? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().height(PaperInkDimens.MiniPlayerHeight),
        shape = RoundedCornerShape(PaperInkDimens.RadiusLarge),
        color = colorScheme.surface,
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (cover != null) {
                cover()
            } else {
                PaperInkCoverFlat(
                    title = title,
                    modifier = Modifier
                        .width(48.dp)
                        .height(48.dp)
                        .clearAndSetSemantics { },
                    radius = PaperInkDimens.RadiusSmall,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(PaperInkDimens.RadiusMicro))
                    .then(if (onBarClick != null) Modifier.clickable(onClick = onBarClick) else Modifier)
                    .then(if (summaryTestTag != null) Modifier.testTag(summaryTestTag) else Modifier)
                    .then(
                        if (summaryStateDescription != null || onCloseAction != null) {
                            Modifier.semantics {
                                if (summaryStateDescription != null) stateDescription = summaryStateDescription
                                if (closeActionDescription != null && onCloseAction != null) {
                                    customActions = listOf(
                                        CustomAccessibilityAction(closeActionDescription) {
                                            onCloseAction()
                                            true
                                        },
                                    )
                                }
                            }
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = PaperInkType.RowTitle,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (titleTestTag != null) Modifier.testTag(titleTestTag) else Modifier,
                    )
                    if (offline) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.CloudDone,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
                Text(
                    text = meta,
                    style = PaperInkType.Micro,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .selectable(selected = false, role = Role.Button, onClick = onPlayPauseClick)
                    .then(if (playTestTag != null) Modifier.testTag(playTestTag) else Modifier)
                    .semantics { contentDescription = playContentDescription },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxSize(),
                    color = colorScheme.tertiary,
                    trackColor = colorScheme.surfaceVariant,
                    strokeWidth = 3.dp,
                )
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** One tab of [PaperInkNavigationBar]. */
@Immutable
data class PaperInkNavItem(
    val label: String,
    val icon: ImageVector,
    val testTag: String? = null,
)

/**
 * The 4-tab navigation of the direction (ADR-0049 keeps every shipped tab):
 * icon 23 dp inside a 50×30 tonal pill, label always visible at 11 sp.
 * 86 dp total with the system gesture inset lives in [PaperInkDimens]; this
 * component is the visible 64 dp row and the screen adds its own inset padding.
 */
@Composable
fun PaperInkNavigationBar(
    items: List<PaperInkNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    // Four labels at 200 % do not fit one 320 dp row («Слухати» alone is ~90 dp
    // and a single word cannot wrap). Above 160 % the bar becomes two rows of
    // two, growing in height instead of clipping the labels — the accessible
    // answer for a bar, unlike shrinking the reader's text.
    val perRow = if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.6f) {
        2
    } else {
        items.size
    }
    androidx.compose.foundation.layout.Column(
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        items.withIndex().chunked(perRow).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { (index, item) ->
                    val selected = index == selectedIndex
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { onSelect(index) },
                            )
                            .then(if (item.testTag != null) Modifier.testTag(item.testTag) else Modifier)
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(50.dp)
                                .height(30.dp)
                                .clip(CircleShape)
                                .background(
                                    // Unselected tabs carry no pill: a tonal
                                    // box on the same ground would read as a
                                    // shadow the direction does not use.
                                    if (selected) colorScheme.primaryContainer else Color.Transparent,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                tint = if (selected) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(23.dp),
                            )
                        }
                        val metrics = androidx.compose.ui.platform.LocalDensity.current
                        androidx.compose.runtime.CompositionLocalProvider(
                            // Chrome micro-copy is capped: the four Ukrainian
                            // labels are a navigation aid, not reading content,
                            // and an uncapped 22 sp label cannot fit a 320 dp
                            // bar without clipping. The tab still has its icon
                            // and its full TalkBack name.
                            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                                density = metrics.density,
                                fontScale = metrics.fontScale.coerceAtMost(1.3f),
                            ),
                        ) {
                            Text(
                                text = item.label,
                                style = PaperInkType.Micro,
                                color = if (selected) colorScheme.onSurface else colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}
