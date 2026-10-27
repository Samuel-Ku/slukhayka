package com.slukhayka.audiobooks.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.ui.displayAuthor
import com.slukhayka.audiobooks.ui.theme.AppDimens

private val RowCoverSize = 64.dp

/**
 * #1235 — the width under which the canonical row stops sharing its one line
 * between the title and the non-text slots.
 *
 * A wide window's list pane is 0.4 of its content width: 208 dp at the 600 dp
 * breakpoint (after the 80 dp rail) and 192 dp at 840 dp when the mini-player
 * column takes its 360 dp. At those widths the 64 dp cover, the 36 dp rank and
 * the 48 dp play target left the title a strip of a few letters. The row is
 * still ONE component and one merged TalkBack node — only its layout responds:
 * the title gets the whole text column, and [leading]/[badges]/[trailing] move
 * to the row's own second line (the owner's decision on #1208, delivered by
 * #1235).
 */
val NarrowBookRowMaxWidth = 240.dp

/**
 * #1235 — does the canonical row lay itself out for a narrow list pane?
 *
 * [com.slukhayka.audiobooks.ui.components.WideDetailPane] provides it from the
 * pane's measured width, so every BookRow inside the pane follows the real
 * container rather than the window (the same rule ADR-0017 asks a delivery to
 * show). Defaults to false: a full-width list keeps the canonical one-line row
 * byte for byte.
 */
val LocalBookRowCompact = staticCompositionLocalOf { false }

/** h:mm:ss / mm:ss — the same duration line every row slot renders. */
internal fun formatRowDuration(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hrs > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", mins, secs)
    }
}

/**
 * v1.4 C3 (ADR-0033) — the ONE vertical-list row: a flat row with a 64 dp
 * cover and dividers instead of card borders (ADR-0018 wrote «divider not
 * border»; this implements it). Slots: [leading] (rank badge, avatar),
 * [badges] (inline with the title), [trailing] (▶, ⋮), [stats] (known
 * duration/chapters only — ADR-0014), [progress] hairline and [footnote]
 * (the Work-feed action status under the row — the honest action progress).
 *
 * The a11y contract rides inside: one merged row node carrying
 * [stateDescription]/[contentDescription], trailing actions as separate
 * 48 dp targets, and a trailing [HorizontalDivider] as the separator.
 *
 * #1235 — in a container narrower than [NarrowBookRowMaxWidth] the same row
 * gives the title the text column and puts [leading], [badges] and [trailing]
 * on a second line inside the SAME clickable/merged node. That is a layout of
 * the canonical row, not a second row component (ADR-0018 / ADR-0033), and it
 * is what keeps a 192/208 dp list pane readable instead of squeezing the title
 * behind the rank, the cover and the play action.
 */
@Composable
fun BookRow(
    title: String,
    modifier: Modifier = Modifier,
    book: AudiobookEntity? = null,
    coverUrl: String? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    genre: String? = null,
    author: String? = null,
    stats: String? = null,
    badges: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    progress: Float? = null,
    progressTestTag: String? = null,
    stateDescription: String? = null,
    contentDescription: String? = null,
    /**
     * v1.4 E4 (#571) — the informational-selector state. A row that is not
     * clickable itself but whose identity is «the one in effect» (the book
     * page's current source) can say so without gaining an action. Opt-in:
     * every other caller leaves it null and the row carries no `selected`
     * semantics at all, so nothing grows a selection affordance by accident.
     * Distinct from a selector: [selected] states a fact, an absent [onClick]
     * keeps the promise that nothing can be switched from this row.
     */
    selected: Boolean? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    footnote: (@Composable ColumnScope.() -> Unit)? = null,
    /**
     * v1.5 review — overlays the cover's top-end corner (the offline badge).
     * Opt-in: only the Медіатека row draws it, every other caller is unchanged.
     */
    coverBadge: (@Composable BoxScope.() -> Unit)? = null,
    /**
     * v1.5 review — renders [footnote] inside the text column instead of under
     * the row. The Медіатека needs it so the 64 dp cover (and the trailing
     * action) centre against the WHOLE block, «Залишилось …» included; a
     * footnote under the row left the cover sitting visibly too high.
     */
    footnoteInColumn: Boolean = false,
    testTag: String? = null
) {
    val compact = LocalBookRowCompact.current
    val horizontalPadding = if (compact) AppDimens.SpaceSm else AppDimens.PageSides
    val titleMaxLines = if (compact) 2 else 1
    // The title keeps a per-row tag when the caller already named the row, so a
    // test can read the text column's own bounds instead of the merged row's.
    val titleTag = testTag?.let { "${it}_title" }

    Column(modifier = modifier.fillMaxWidth()) {
        // The clickable + merged a11y node wraps BOTH lines in compact mode, so
        // TalkBack still announces one row and the rank/play slots stay inside
        // its bounds instead of becoming separate focus stops.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    if (stateDescription != null) this.stateDescription = stateDescription
                    if (contentDescription != null) this.contentDescription = contentDescription
                    if (selected != null) this.selected = selected
                }
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .then(
                    when {
                        onClick != null && onLongClick != null -> Modifier.combinedClickable(
                            onClickLabel = onClickLabel,
                            onLongClickLabel = null,
                            onClick = onClick,
                            onLongClick = onLongClick
                        )
                        onClick != null -> Modifier.clickable(
                            onClickLabel = onClickLabel,
                            onClick = onClick
                        )
                        else -> Modifier
                    }
                )
        ) {
            val paperInk = com.slukhayka.audiobooks.ui.theme.LocalPaperInkDesign.current
            val dense = com.slukhayka.audiobooks.ui.theme.LocalContentDensity.current ==
                com.slukhayka.audiobooks.ui.theme.ContentDensity.COMPACT
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(
                        min = when {
                            paperInk && dense -> 88.dp
                            paperInk -> 112.dp
                            else -> 76.dp
                        }
                    )
                    .padding(
                        horizontal = if (paperInk) {
                            com.slukhayka.audiobooks.ui.theme.PaperInkDimens.SpacePage
                        } else {
                            horizontalPadding
                        },
                        vertical = if (paperInk) 10.dp else AppDimens.SpaceSm,
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!compact) leading?.invoke(this)
                val paperCoverModifier = if (paperInk && dense) {
                    Modifier.width(50.dp).height(75.dp)
                } else if (paperInk) {
                    Modifier.width(58.dp).height(87.dp)
                } else {
                    Modifier.size(RowCoverSize)
                }
                if (book != null) {
                    Box(modifier = paperCoverModifier) {
                        BookCoverImage(
                            book = book,
                            semantics = BookCoverSemantics.Decorative,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(AppDimens.RadiusCover)),
                            contentScale = ContentScale.Crop
                        )
                        coverBadge?.invoke(this)
                    }
                    Spacer(modifier = Modifier.width(AppDimens.SpaceMd))
                } else if (paperInk) {
                    // A paper row always shows its cover slot: a catalogue row
                    // without artwork gets the direction's paper tile instead of
                    // an empty gap that made feed rows look like a different
                    // component.
                    Box(modifier = paperCoverModifier) {
                        com.slukhayka.audiobooks.ui.components.paperink.PaperInkCoverFlat(
                            title = title,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(com.slukhayka.audiobooks.ui.theme.PaperInkDimens.RadiusSmall)),
                            radius = com.slukhayka.audiobooks.ui.theme.PaperInkDimens.RadiusSmall,
                        )
                        coverBadge?.invoke(this)
                    }
                    Spacer(modifier = Modifier.width(AppDimens.SpaceMd))
                } else if (!coverUrl.isNullOrBlank()) {
                    // Catalogue rows (search, work feed) carry a cover URL, not a
                    // library entity — the same slot, artwork or placeholder.
                    Box(modifier = paperCoverModifier) {
                        CatalogCoverImage(
                            coverImageUrl = coverUrl,
                            title = title,
                            semantics = BookCoverSemantics.Decorative,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(AppDimens.RadiusCover))
                        )
                        coverBadge?.invoke(this)
                    }
                    Spacer(modifier = Modifier.width(AppDimens.SpaceMd))
                }
                Column(modifier = Modifier.weight(1f)) {
                    if (!genre.isNullOrBlank()) {
                        Text(
                            text = genre,
                            style = if (paperInk) {
                                com.slukhayka.audiobooks.ui.theme.PaperInkType.TrackingLabel
                            } else {
                                MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                            },
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (compact) {
                        // The title owns the whole text column: the rank, the
                        // badges and the play action are on the second line.
                        Text(
                            text = title,
                            style = if (paperInk) {
                                com.slukhayka.audiobooks.ui.theme.PaperInkType.RowTitle
                            } else {
                                MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            },
                            maxLines = titleMaxLines,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (titleTag != null) {
                                        Modifier.testTag(titleTag)
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // The title yields before the badges do: without this the
                            // badges got whatever width was left over and a long title
                            // squeezed «Очікує» into a one-letter-wide column (#843).
                            Text(
                                text = title,
                                style = if (paperInk) {
                                    com.slukhayka.audiobooks.ui.theme.PaperInkType.RowTitle
                                } else {
                                    MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                },
                                maxLines = titleMaxLines,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (titleTag != null) {
                                            Modifier.testTag(titleTag)
                                        } else {
                                            Modifier
                                        }
                                    )
                            )
                            badges?.invoke(this)
                        }
                    }
                    if (!author.isNullOrBlank()) {
                        Text(
                            text = author,
                            style = if (paperInk) {
                                com.slukhayka.audiobooks.ui.theme.PaperInkType.Meta
                            } else {
                                MaterialTheme.typography.bodySmall
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!stats.isNullOrBlank()) {
                        Text(
                            text = stats,
                            style = if (paperInk) {
                                com.slukhayka.audiobooks.ui.theme.PaperInkType.Micro
                            } else {
                                MaterialTheme.typography.labelSmall
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (progress != null && progress > 0f) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .padding(top = AppDimens.SpaceXs)
                                .fillMaxWidth()
                                .height(PosterProgressHairlineHeight)
                                .clip(RoundedCornerShape(AppDimens.RadiusProgress))
                                .then(
                                    if (progressTestTag != null) {
                                        Modifier.testTag(progressTestTag)
                                    } else {
                                        Modifier
                                    }
                                ),
                            color = if (paperInk) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                            // An M3 dot at the track's end reads as a second,
                            // unexplained marker on a paper row.
                            drawStopIndicator = {},
                        )
                    }
                    if (footnoteInColumn) footnote?.invoke(this)
                }
                if (!compact) trailing?.invoke(this)
            }
            if (compact) {
                val hasSecondarySlots = leading != null || badges != null || trailing != null
                if (hasSecondarySlots) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = horizontalPadding,
                                end = horizontalPadding,
                                bottom = AppDimens.SpaceSm
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        leading?.invoke(this)
                        if (leading != null && badges != null) {
                            Spacer(modifier = Modifier.width(AppDimens.SpaceSm))
                        }
                        badges?.invoke(this)
                        Spacer(modifier = Modifier.weight(1f))
                        trailing?.invoke(this)
                    }
                }
            }
        }
        if (!footnoteInColumn) footnote?.invoke(this)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/**
 * Library-entity convenience overload — the flat library-list row (the
 * library-row contract, preserved verbatim: the `book_item_<id>` tag,
 * the availability state description, the "4read Каталог" placeholder genre
 * skip, and the haptic-tick play action as the trailing 48 dp target).
 */
@Composable
fun BookRow(
    book: AudiobookEntity,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val availabilityState = stringResource(
        if (book.isDownloaded) R.string.a11y_available_offline
        else R.string.a11y_connection_required
    )
    val openLabel = stringResource(R.string.a11y_open_work, book.title)
    // Only the values we actually know — catalogue books start with
    // 0 chapters / 0 duration until their page is fetched (ADR-0014).
    val chaptersLabel = if (book.totalChapters > 0) {
        pluralStringResource(R.plurals.chapter_count, book.totalChapters, book.totalChapters)
    } else {
        null
    }
    val durationLabel = if (book.totalDurationSeconds > 0L) {
        formatRowDuration(book.totalDurationSeconds)
    } else {
        null
    }
    val statsLabel = when {
        chaptersLabel != null && durationLabel != null -> "$chaptersLabel • $durationLabel"
        chaptersLabel != null -> chaptersLabel
        durationLabel != null -> durationLabel
        else -> null
    }
    BookRow(
        title = book.title,
        modifier = modifier,
        book = book,
        // "4read Каталог" is the placeholder genre for catalogue books —
        // skip it so every list row isn't labelled "4read".
        genre = book.genre.takeIf {
            it.isNotBlank() && !it.contains("4read", ignoreCase = true)
        },
        author = book.displayAuthor.takeIf { it.isNotBlank() },
        stats = statsLabel,
        badges = {
            if (book.isDownloaded) {
                Spacer(modifier = Modifier.width(AppDimens.SpaceXs))
                Icon(
                    imageVector = Icons.Default.CloudDone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        },
        trailing = {
            IconButton(
                onClick = {
                    // Spec-22 T3: a light tick on playback start.
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPlayClick()
                },
                modifier = Modifier
                    .size(AppDimens.TouchTarget)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.a11y_play_work, book.title),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        stateDescription = availabilityState,
        onClick = onClick,
        onClickLabel = openLabel,
        testTag = "book_item_${book.id}"
    )
}
