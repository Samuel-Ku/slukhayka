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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.res.stringResource
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
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.icons.PaperInkIcon
import com.slukhayka.audiobooks.ui.icons.PaperInkIcons
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
                        style = PaperInkType.MiniTitle,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (titleTestTag != null) Modifier.testTag(titleTestTag) else Modifier,
                    )
                    if (offline) {
                        Spacer(modifier = Modifier.width(6.dp))
                        // 16 dp is the audit's floor for a readable state
                        // mark: the tray-with-check is a state beside the title,
                        // not a target, and the offline state has its own a11y
                        // string.
                        PaperInkIcon(
                            imageVector = PaperInkIcons.Downloaded,
                            contentDescription = null,
                            size = 16.dp,
                            tint = colorScheme.primary,
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
            // F14 — this is a BUTTON, not a selectable tab: `selectable(
            // selected = false)` made TalkBack announce «не вибрано» for a play
            // control. The progress is real state and now says so, too.
            val clampedProgress = progress.coerceIn(0f, 1f)
            val progressPercent = (clampedProgress * 100).toInt()
            val progressDescription = stringResource(R.string.listen_progress_percent, progressPercent)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onPlayPauseClick)
                    .then(if (playTestTag != null) Modifier.testTag(playTestTag) else Modifier)
                    .semantics {
                        contentDescription = playContentDescription
                        stateDescription = progressDescription
                        progressBarRangeInfo = ProgressBarRangeInfo(clampedProgress, 0f..1f)
                    },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { clampedProgress },
                    // F14 — 32 dp, not the whole 48 dp target: the ring is an
                    // indicator around the glyph, not a second button. The
                    // track moves off `surfaceVariant` (1.11:1 on paper) to
                    // `outline` (LineStrong, 3.28:1) so «скільки прослухано»
                    // is actually visible.
                    modifier = Modifier.size(32.dp).testTag("mini_player_progress"),
                    color = colorScheme.tertiary,
                    trackColor = colorScheme.outline,
                    strokeWidth = 3.dp,
                )
                PaperInkIcon(
                    imageVector = if (isPlaying) PaperInkIcons.Pause else PaperInkIcons.Play,
                    contentDescription = null,
                    // 21 dp is the prototype's `.mp-play svg`, measured — one dp
                    // above the inline token, inside the 48 dp target.
                    size = 21.dp,
                    tint = colorScheme.onSurface,
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
    // F13: from 130 % the single row of four starts to ellipsize the longest
    // label («Налаштування» is one word and cannot wrap), so the bar switches
    // to two rows of two exactly where the old label cap used to bite.
    val perRow = if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
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
                                    // Selected: the muted verdigris FILL, not a
                                    // tonal container — the audit measured
                                    // `primaryContainer` on paper at 1.09:1
                                    // (1.49:1 in ink), so the state was
                                    // invisible (WCAG 1.4.11 wants 3:1).
                                    if (selected) colorScheme.primary else Color.Transparent,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                tint = if (selected) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(23.dp),
                            )
                        }
                        // F13 — the label follows the reader's text size. The
                        // old 1.3× cap clipped the scale itself, which §7 of
                        // the direction forbids; the two-row layout above
                        // (now from >1.3×) is what makes 200 % fit.
                        Text(
                            text = item.label,
                            style = PaperInkType.Micro,
                            color = if (selected) colorScheme.onSurface else colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                            modifier = if (item.testTag != null) {
                                Modifier.testTag("${item.testTag}_label")
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
        }
    }
}
