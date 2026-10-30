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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
import com.slukhayka.audiobooks.ui.icons.PaperInkIcon
import com.slukhayka.audiobooks.ui.icons.PaperInkIconSizes
import com.slukhayka.audiobooks.ui.icons.PaperInkIcons
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
 *
 * Horizontal page inset is the CALLER's: the heading carries none of its own, so
 * it can line up with whatever it heads (a shelf owns its inset through
 * `contentPadding`). A caller that forgets it renders text at x=0 — see #1615.
 */
@Composable
fun PaperInkSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = PaperInkType.SectionTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = PaperInkType.Micro,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
    /**
     * Real artwork for the cover slot, or null for the paper letter placeholder.
     *
     * F29 of the 2026-10-28 device audit: the «Продовжити слухати» hero — the
     * main card of the app — drew a letter tile while the same Work had real
     * art on every other surface. The primitive stays typography-agnostic: the
     * caller owns image loading/resolution and this card owns the slot, its
     * size (104×156) and its radius, so the shelf rhythm cannot drift.
     */
    cover: (@Composable () -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    // #1675 — at 200 % text the hero used to be a FIXED-height card: the
    // growing title/author/narrator ran past the right edge, were clipped by
    // the card and slid under the play button (QA on device, font_scale 2.0).
    // The card now grows from the same minimum height, reserves the play corner
    // for text and gives meta lines an honest second line.
    val largeText = LocalDensity.current.fontScale >= 1.5f
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // #1675 — normal type keeps the EXACT fixed height the golden
                // pins; at 200 % the card grows instead of clipping.
                .then(
                    if (largeText) Modifier.heightIn(min = PaperInkDimens.HeroHeight)
                    else Modifier.height(PaperInkDimens.HeroHeight)
                )
        ) {
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
                if (cover != null) {
                    // The artwork slot keeps the card's OWN size and radius so
                    // the shelf rhythm cannot drift with the image source.
                    Box(
                        modifier = Modifier
                            .width(PaperInkDimens.CoverHeroWidth)
                            .height(PaperInkDimens.CoverHeroHeight)
                            .clip(RoundedCornerShape(PaperInkDimens.RadiusSmall))
                            .testTag("hero_cover_art"),
                    ) {
                        cover()
                    }
                } else {
                    // No art: the pre-fix path is kept EXACTLY, so a Work that
                    // has no cover cannot change the card's pixels (the golden
                    // stays the witness of the fallback).
                    PaperInkCover(
                        title = title,
                        modifier = Modifier
                            .width(PaperInkDimens.CoverHeroWidth)
                            .height(PaperInkDimens.CoverHeroHeight)
                            // The title below states the book; the letter is
                            // decoration. The tag MUST come before the clear:
                            // `clearAndSetSemantics` wipes the semantics set on
                            // the same node, so tagging after it left the
                            // fallback unaddressable (CI caught it).
                            .testTag("hero_cover_placeholder")
                            .clearAndSetSemantics { },
                        radius = PaperInkDimens.RadiusSmall,
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // #1675 — the play button overlays the bottom corner;
                        // reserving its gutter keeps meta text out from under it.
                        .then(
                            if (largeText) Modifier.padding(end = 72.dp, bottom = 56.dp)
                            else Modifier
                        )
                ) {
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
                        maxLines = if (largeText) 3 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("hero_title"),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = author,
                        style = PaperInkType.Meta,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = if (largeText) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("hero_author"),
                    )
                    if (narrator.isNotBlank()) {
                        Text(
                            text = narrator,
                            style = PaperInkType.Micro,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = if (largeText) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("hero_narrator"),
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (progress != null) {
                        Row(
                            modifier = Modifier.padding(end = if (largeText) 0.dp else 64.dp),
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
                        maxLines = if (largeText) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("hero_chapter"),
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
                    .testTag("hero_play_button")
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
                PaperInkIcon(
                    imageVector = PaperInkIcons.Play,
                    contentDescription = null,
                    size = PaperInkIconSizes.Inline,
                    tint = colorScheme.onTertiary,
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
    progressTestTag: String? = null,
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
                    // The five-bar wave at 16 dp: below that the bars merge,
                    // and this is a state mark, not the wordmark.
                    PaperInkIcon(
                        imageVector = PaperInkIcons.Wave,
                        contentDescription = null,
                        size = 16.dp,
                        tint = colorScheme.onTertiaryContainer,
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
                    PaperInkIcon(
                        imageVector = PaperInkIcons.Close,
                        contentDescription = null,
                        size = PaperInkIconSizes.Inline,
                        tint = colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (progress != null && progress > 0f) {
            Spacer(modifier = Modifier.height(8.dp))
            PaperInkProgress(
                progress = progress,
                modifier = if (progressTestTag != null) Modifier.testTag(progressTestTag) else Modifier,
            )
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
    // The click belongs to the WHOLE row: the caller's testTag lands on the
    // same node, so the merged text (title, author, chapter) is announced and
    // findable, exactly as the old Card did.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (showDivider) {
            HorizontalDivider(color = colorScheme.outlineVariant)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PaperInkDimens.BookRowHeight)
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
                    PaperInkIcon(
                        imageVector = PaperInkIcons.Play,
                        contentDescription = null,
                        size = PaperInkIconSizes.Inline,
                        tint = colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}
