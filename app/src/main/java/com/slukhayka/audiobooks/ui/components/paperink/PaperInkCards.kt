package com.slukhayka.audiobooks.ui.components.paperink

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.ui.theme.PaperInkDimens
import com.slukhayka.audiobooks.ui.theme.PaperInkShapes
import com.slukhayka.audiobooks.ui.theme.PaperInkType

/** The root-tab title: 34/36 Onest ExtraBold, the one loud line on a screen. */
@Composable
fun PaperInkScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = PaperInkType.ScreenTitle,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/**
 * The shelf/block heading of the direction: 21/26 Onest Bold on the paper ground
 * with an optional quiet action on the right. No all-caps, no tracking games.
 */
@Composable
fun PaperInkSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = title,
            style = PaperInkType.SectionTitle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onActionClick != null) {
            Text(
                text = action,
                style = PaperInkType.Label,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(PaperInkDimens.RadiusMicro))
                    .clickable(onClick = onActionClick)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * The semantic progress line. One component per meaning:
 * a shelf/hero bar is 3 dp and never has a thumb (it is not a slider).
 */
@Composable
fun PaperInkProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = PaperInkDimens.ShelfProgressLine,
    color: Color = MaterialTheme.colorScheme.tertiary,
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .fillMaxHeight()
                .background(color),
        )
    }
}

/**
 * «Продовжити» — the one card on the listening screen. A book lying on paper
 * with a warm lamp under it: cover 104×156, Literata title, exact remaining
 * time and a 52 dp play target. The height is a MINIMUM (193 dp): a long title
 * grows the card instead of being clipped.
 *
 * [onClick] makes the whole card open the book; [playContentDescription] carries
 * the contextual "resume THIS book" name for a screen reader.
 */
@Composable
fun PaperInkHeroCard(
    title: String,
    author: String,
    narrator: String,
    chapterLabel: String,
    remainingLabel: String,
    progress: Float?,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
    eyebrow: String = "Продовжити",
    progressLabel: String? = null,
    onClick: (() -> Unit)? = null,
    playContentDescription: String? = null,
    cardContentDescription: String? = null,
    eyebrowHeading: Boolean = false,
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(
                if (cardContentDescription != null) {
                    Modifier.semantics { contentDescription = cardContentDescription }
                } else {
                    Modifier
                },
            ),
        shape = PaperInkShapes.extraLarge,
        color = colorScheme.surface,
        border = BorderStroke(1.dp, colorScheme.outlineVariant),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(PaperInkDimens.HeroHeight)) {
            // The lamp: the only decorative glow, and it belongs to sound.
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .offset(x = (-32).dp, y = (-48).dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                colorScheme.tertiary.copy(alpha = 0.20f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            Row(
                modifier = Modifier.fillMaxSize().padding(16.dp),
            ) {
                PaperInkCover(
                    title = title,
                    modifier = Modifier
                        .width(PaperInkDimens.CoverHeroWidth)
                        .height(PaperInkDimens.CoverHeroHeight)
                        // The title below states the book; the letter is decoration.
                        .clearAndSetSemantics { },
                    radius = PaperInkDimens.RadiusSmall,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Text(
                        text = eyebrow,
                        style = PaperInkType.Label,
                        color = colorScheme.onSurfaceVariant,
                        modifier = if (eyebrowHeading) Modifier.semantics { heading() } else Modifier,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = title,
                        style = PaperInkType.BookTitleHero,
                        color = colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = author,
                        style = PaperInkType.Meta,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (narrator.isNotBlank()) {
                        Text(
                            text = narrator,
                            style = PaperInkType.Micro,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (progress != null) {
                        Row(
                            modifier = Modifier.padding(end = 64.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PaperInkProgress(progress = progress, modifier = Modifier.weight(1f))
                            if (progressLabel != null) {
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = progressLabel,
                                    style = PaperInkType.Timer,
                                    color = colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = chapterLabel,
                        style = PaperInkType.Timer,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (remainingLabel.isNotBlank()) {
                        Text(text = remainingLabel, style = PaperInkType.Micro, color = colorScheme.onSurfaceVariant)
                    }
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(PaperInkDimens.PrimaryButtonHeight)
                    .clip(CircleShape)
                    .background(colorScheme.tertiary)
                    .clickable(onClick = onPlayClick)
                    .then(
                        if (playContentDescription != null) {
                            Modifier.semantics { contentDescription = playContentDescription }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = colorScheme.onTertiary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/**
 * A shelf poster: 132×198 cover, UI-sans title (the serif is reserved for the
 * display sizes), 3 dp progress, and a solid "playing" pill — never a
 * translucent badge over artwork.
 */
@Composable
fun PaperInkPosterCard(
    title: String,
    author: String,
    meta: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    nowPlaying: Boolean = false,
    onClick: (() -> Unit)? = null,
    onNotInterested: (() -> Unit)? = null,
    dismissContentDescription: String? = null,
    stateDescription: String? = null,
    testTag: String? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .width(PaperInkDimens.CoverShelfWidth)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .then(
                if (stateDescription != null) {
                    Modifier.semantics { this.stateDescription = stateDescription }
                } else {
                    Modifier
                },
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(
            modifier = Modifier
                .width(PaperInkDimens.CoverShelfWidth)
                .height(PaperInkDimens.CoverShelfHeight),
        ) {
            PaperInkCover(
                title = title,
                modifier = Modifier.fillMaxSize().clearAndSetSemantics { },
                radius = PaperInkDimens.RadiusMedium,
            )
            if (nowPlaying) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(colorScheme.tertiaryContainer)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(text = "слухаєш", style = PaperInkType.Micro, color = colorScheme.onTertiaryContainer)
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = title,
            style = PaperInkType.RowTitle,
            color = colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = author,
            style = PaperInkType.Meta,
            color = colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = meta,
                style = PaperInkType.Micro,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onNotInterested != null) {
                // The dismissed action stays OFF the artwork (the reference
                // design has no «×» over the cover), but it remains a real
                // visible target with its contextual name.
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onNotInterested)
                        .then(
                            if (dismissContentDescription != null) {
                                Modifier.semantics { contentDescription = dismissContentDescription }
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        if (progress != null && progress > 0f) {
            Spacer(modifier = Modifier.height(8.dp))
            PaperInkProgress(progress = progress)
        }
    }
}

/**
 * A library row on paper, 112 dp: cover back as the anchor, one metadata split,
 * progress 3 dp and the source as a micro label on the right — no stacked
 * cards, no "×" over the art.
 */
@Composable
fun PaperInkBookRow(
    title: String,
    author: String,
    series: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    remainingLabel: String? = null,
    sourceLabel: String? = null,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    onPlayClick: (() -> Unit)? = null,
    playContentDescription: String? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        if (showDivider) {
            HorizontalDivider(color = colorScheme.outlineVariant)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PaperInkDimens.BookRowHeight)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 12.dp),
        ) {
            PaperInkCoverFlat(
                title = title,
                modifier = Modifier
                    .width(PaperInkDimens.CoverRowWidth)
                    .height(PaperInkDimens.CoverRowHeight)
                    .clearAndSetSemantics { },
                radius = PaperInkDimens.RadiusSmall,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = PaperInkType.RowTitle,
                    color = colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = author,
                    style = PaperInkType.Meta,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = series,
                    style = PaperInkType.Micro,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (progress != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    PaperInkProgress(progress = progress)
                }
                if (remainingLabel != null || sourceLabel != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = remainingLabel.orEmpty(),
                            style = PaperInkType.Timer,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (sourceLabel != null) {
                            Text(text = sourceLabel, style = PaperInkType.Micro, color = colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (onPlayClick != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(PaperInkDimens.TouchTarget)
                        .clip(CircleShape)
                        .background(colorScheme.primaryContainer)
                        .clickable(onClick = onPlayClick)
                        .then(
                            if (playContentDescription != null) {
                                Modifier.semantics { contentDescription = playContentDescription }
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
