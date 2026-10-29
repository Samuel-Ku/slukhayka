package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.achievements.AchievementBoard
import com.slukhayka.audiobooks.data.achievements.AchievementCatalog
import com.slukhayka.audiobooks.data.achievements.AchievementDefinition
import com.slukhayka.audiobooks.data.achievements.AchievementProgress
import com.slukhayka.audiobooks.data.achievements.ListenerTitle
import com.slukhayka.audiobooks.ui.achievements.AchievementPresentation
import com.slukhayka.audiobooks.ui.achievements.achievementName
import com.slukhayka.audiobooks.ui.components.EmptyState
import com.slukhayka.audiobooks.ui.icons.PaperInkIcon
import com.slukhayka.audiobooks.ui.icons.PaperInkIconSizes
import com.slukhayka.audiobooks.ui.icons.PaperInkIcons
import com.slukhayka.audiobooks.ui.theme.ColorContrast
import com.slukhayka.audiobooks.ui.theme.PaperInkDimens
import com.slukhayka.audiobooks.ui.theme.PaperInkPalette
import com.slukhayka.audiobooks.ui.theme.PaperInkThemedContent
import com.slukhayka.audiobooks.ui.theme.PaperInkType

/**
 * #704 (T6) — the «Досягнення» screen.
 *
 * What to show is decided by [AchievementBoard], not here: the screen renders
 * the lists it is handed, so the rule «приховані не розкриваються до здобуття»
 * has exactly one owner and is tested without pixels.
 *
 * F18 of the 2026-10-28 device audit rebuilt the body on the direction's
 * vocabulary: an ink hero panel with the earned/total ring, a REGISTRY of 56 dp
 * award rows (medal + name + status) instead of a plain text list, and the
 * current-week streak strip. The showcase (#705), the pin control and every
 * test tag the screen already promised are preserved as they were.
 *
 * Layout questions live in [AchievementPresentation]; this file owns pixels and
 * resources. [progress] is the same snapshot the evaluator reads, so a row can
 * only ever show the number the award itself is judged by (ADR-0014).
 */
@Composable
fun AchievementsScreen(
    board: AchievementBoard,
    title: ListenerTitle,
    showcase: List<String>,
    onBackClick: () -> Unit,
    onTogglePin: (String) -> Unit = {},
    /**
     * #705 (T7) — the showcase's public life. All three default to the honest
     * "not published, and not publishable", so a caller that does not wire them
     * gets a screen that offers nothing rather than one that offers a dead end.
     */
    showcasePublished: Boolean = false,
    showcasePublishable: Boolean = false,
    onPublishShowcase: () -> Unit = {},
    onWithdrawShowcase: () -> Unit = {},
    /** F18 — the snapshot the status lines read; zeros until the caller wires it. */
    progress: AchievementProgress = AchievementProgress(),
    /** F18 — the catalogue size the hero ring is measured against. */
    totalAwards: Int = AchievementCatalog.definitions.size,
    /** F18 — the current-week strip; [AchievementPresentation.Week.Empty] hides it. */
    week: AchievementPresentation.Week = AchievementPresentation.Week.Empty,
) {
    PaperInkThemedContent {
        SettingsDestinationScaffold(
            destination = SettingsDestination.Achievements,
            onBackClick = onBackClick
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(PaperInkDimens.SpacePage)
                    .testTag("achievements_screen")
            ) {
                if (board.isEmpty) {
                    EmptyState(
                        title = stringResource(R.string.achievements_empty_title),
                        body = stringResource(R.string.achievements_empty_body),
                        modifier = Modifier.testTag("achievements_empty_state")
                    )
                    return@Column
                }

                AchievementHeroPanel(
                    hero = AchievementPresentation.hero(board.earned.size, totalAwards),
                    tier = stringResource(R.string.achievements_tier, title.label),
                )

                if (showcase.isNotEmpty()) {
                    SectionHeading(stringResource(R.string.achievements_showcase), "achievements_showcase_heading")
                    // The showcase rows carry no pin control: the pins live on the
                    // earned registry below, where taking an award off is the same
                    // gesture as putting it on.
                    AchievementRegistry(
                        definitions = showcase.mapNotNull { id -> board.earned.firstOrNull { it.id == id } },
                        tagPrefix = "achievements_showcase_item",
                        earned = true,
                        progress = progress,
                        pinned = showcase.toSet(),
                        onTogglePin = null,
                    )
                    // #705 (T7) — the showcase is local until the listener says
                    // otherwise, so the choice is offered right here, next to what
                    // it is about. Without a curator profile there is nowhere for
                    // it to appear, and the screen says that instead of offering an
                    // action that could only refuse.
                    when {
                        !showcasePublishable -> Text(
                            text = stringResource(R.string.showcase_publish_unavailable),
                            style = PaperInkType.Meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .testTag("showcase_publish_unavailable")
                        )

                        showcasePublished -> TextButton(
                            onClick = onWithdrawShowcase,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("showcase_withdraw")
                        ) { Text(stringResource(R.string.showcase_withdraw_action)) }

                        else -> TextButton(
                            onClick = onPublishShowcase,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("showcase_publish")
                        ) { Text(stringResource(R.string.showcase_publish_action)) }
                    }
                }

                if (board.earned.isNotEmpty()) {
                    SectionHeading(stringResource(R.string.achievements_earned), "achievements_earned_heading")
                    AchievementRegistry(
                        definitions = board.earned,
                        tagPrefix = "achievements_earned_item",
                        earned = true,
                        progress = progress,
                        pinned = showcase.toSet(),
                        onTogglePin = onTogglePin,
                    )
                }

                if (board.upcoming.isNotEmpty()) {
                    SectionHeading(stringResource(R.string.achievements_upcoming), "achievements_upcoming_heading")
                    AchievementRegistry(
                        definitions = board.upcoming,
                        tagPrefix = "achievements_upcoming_item",
                        earned = false,
                        progress = progress,
                        pinned = emptySet(),
                        onTogglePin = null,
                    )
                }

                if (week.isVisible) {
                    StreakBlock(week)
                }
            }
        }
    }
}

/**
 * The ink hero: the listener's title, how many awards are earned out of the
 * catalogue, and the ring that states the same share as a percentage.
 *
 * The panel is ink in BOTH app themes — the prototype's one deliberate
 * exception to "the paper page is light", because a statistic that anchors the
 * screen reads better as a printed plate than as another tinted card. In the
 * ink theme it steps up to the raised night surface so it does not disappear
 * into the page.
 */
@Composable
private fun AchievementHeroPanel(
    hero: AchievementPresentation.Hero,
    tier: String,
) {
    val inkTheme = isInkTheme()
    val panel = if (inkTheme) PaperInkPalette.NightHigh else PaperInkPalette.NightSurface
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("achievements_hero"),
        color = panel,
        shape = RoundedCornerShape(PaperInkDimens.RadiusXl),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // The lamp again: the one decorative light, and it belongs to sound.
            //
            // `matchParentSize` is what keeps the DECORATION out of the layout:
            // a 220 dp glow measured as a sibling would set the panel's height to
            // 220 dp and leave a plate of empty ink under two lines of type (the
            // first recorded golden showed exactly that). With it, the panel is
            // as tall as the words inside and the glow still fills the corner.
            Box(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .size(PaperInkDimens.AchievementHeroGlowSize)
                        .offset(x = (-40).dp, y = (-60).dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    PaperInkPalette.LampNight.copy(alpha = 0.22f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
            }
            Row(
                modifier = Modifier.padding(PaperInkDimens.SpaceXl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tier,
                        style = PaperInkType.Label,
                        color = PaperInkPalette.DustNight,
                        modifier = Modifier.testTag("achievements_tier"),
                    )
                    Spacer(modifier = Modifier.height(PaperInkDimens.SpaceSm))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = hero.earned.toString(),
                            style = PaperInkType.StatXL,
                            color = PaperInkPalette.Chalk,
                            modifier = Modifier.testTag("achievements_hero_count"),
                        )
                        Spacer(modifier = Modifier.width(PaperInkDimens.SpaceMd))
                        Text(
                            // "із N відзнак" — the count above and this line are ONE
                            // sentence, so the plural is written on the catalogue
                            // size rather than invented as a second number.
                            text = pluralStringResource(
                                R.plurals.achievements_hero_caption,
                                hero.total,
                                hero.total,
                            ),
                            style = PaperInkType.Meta,
                            color = PaperInkPalette.DustNight,
                            modifier = Modifier
                                .padding(bottom = 2.dp)
                                .testTag("achievements_hero_caption"),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(PaperInkDimens.SpaceLg))
                AchievementRing(hero)
            }
        }
    }
}

/**
 * The hero's share as a ring with the percentage in the middle.
 *
 * Drawn on a Canvas rather than as a Material indicator so the ring is the
 * prototype's plate, not an animated spinner. The semantics carry the same
 * number the sighted listener sees — a decoration that hides the only number on
 * the panel would be a screen-reader hole.
 */
@Composable
private fun AchievementRing(hero: AchievementPresentation.Hero) {
    val description = stringResource(
        R.string.achievements_ring_a11y,
        hero.percent,
        hero.earned,
        hero.total,
    )
    Box(
        modifier = Modifier
            .size(PaperInkDimens.AchievementRingSize)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(hero.fraction, 0f..1f)
            }
            .testTag("achievements_hero_ring"),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = PaperInkDimens.AchievementRingStroke.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = PaperInkPalette.Chalk.copy(alpha = 0.12f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
            if (hero.fraction > 0f) {
                drawArc(
                    color = PaperInkPalette.LampNight,
                    startAngle = -90f,
                    sweepAngle = 360f * hero.fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            text = "${hero.percent}%",
            style = PaperInkType.Timer,
            color = PaperInkPalette.LampNight,
            modifier = Modifier
                .align(Alignment.Center)
                .clearAndSetSemantics { },
        )
    }
}

/** One section's heading — the same two lines the old screen promised. */
@Composable
private fun SectionHeading(text: String, tag: String) {
    Spacer(modifier = Modifier.height(PaperInkDimens.SpaceSection))
    Text(
        text = text,
        style = PaperInkType.SectionTitle,
        // A heading is what a screen reader needs to move between sections.
        modifier = Modifier.semantics { heading() }.testTag(tag)
    )
    Spacer(modifier = Modifier.height(PaperInkDimens.SpaceSm))
}

/**
 * The registry: one row per award.
 *
 * A row is 56 dp (the pin's own 48 dp target plus 4 dp of air above and below;
 * a row without a pin keeps the 56 dp as a floor). The medal is 40 dp so the
 * row reads as a list of badges rather than a table of labels.
 */
@Composable
private fun AchievementRegistry(
    definitions: List<AchievementDefinition>,
    tagPrefix: String,
    earned: Boolean,
    progress: AchievementProgress,
    pinned: Set<String>,
    onTogglePin: ((String) -> Unit)?,
) {
    // One divider BETWEEN rows, like every other paper list: a trailing rule
    // under the last row would read as a separator from the next section.
    definitions.forEachIndexed { index, definition ->
        AchievementRegistryRow(
            definition = definition,
            earned = earned,
            progress = progress,
            isPinned = definition.id in pinned,
            onTogglePin = onTogglePin,
            tag = "${tagPrefix}_${definition.id}",
        )
        if (index != definitions.lastIndex) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun AchievementRegistryRow(
    definition: AchievementDefinition,
    earned: Boolean,
    progress: AchievementProgress,
    isPinned: Boolean,
    onTogglePin: ((String) -> Unit)?,
    tag: String,
) {
    val context = LocalContext.current
    val name = achievementName(context, definition.id)
        ?: stringResource(R.string.achievement_awarded_generic)
    val colorScheme = MaterialTheme.colorScheme
    val inkTheme = isInkTheme()
    val medalBackground = when {
        !earned && inkTheme -> PaperInkPalette.NightHigh
        !earned -> PaperInkPalette.Track
        inkTheme -> PaperInkPalette.LampSoftNight
        else -> PaperInkPalette.LampSoft
    }
    val medalTint = when {
        !earned -> colorScheme.onSurfaceVariant
        inkTheme -> PaperInkPalette.OnLampSoftNight
        else -> PaperInkPalette.OnLampSoft
    }
    val glyph = if (earned) AchievementPresentation.medalGlyph(definition) else PaperInkIcons.Lock
    val status = if (earned) {
        stringResource(R.string.achievements_status_earned)
    } else {
        val rowProgress = AchievementPresentation.progress(progress, definition)
        when (rowProgress.unit) {
            AchievementPresentation.Unit.HOURS -> pluralStringResource(
                R.plurals.achievements_progress_hours,
                rowProgress.targetHours.toInt(),
                rowProgress.currentHours,
                rowProgress.targetHours,
            )

            AchievementPresentation.Unit.COUNT -> stringResource(
                R.string.achievements_progress_count,
                rowProgress.current,
                rowProgress.target,
            )
        }
    }

    Row(
        modifier = Modifier
            // The tag is the row's IDENTITY, so it goes OUTERMOST: a tag after
            // `padding` measured the padded CONTENT (48 dp — CI measured it) and
            // spoke for a row that is 56 dp tall. The wrapper-tag order is the
            // one `PaperInkBookRow` already uses.
            .testTag(tag)
            .fillMaxWidth()
            .heightIn(min = PaperInkDimens.AchievementRowHeight)
            .padding(vertical = PaperInkDimens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(PaperInkDimens.AchievementMedalSize)
                .clip(CircleShape)
                .background(medalBackground),
            contentAlignment = Alignment.Center,
        ) {
            PaperInkIcon(
                imageVector = glyph,
                // Decorative: the award's name and status are the row's words.
                contentDescription = null,
                size = PaperInkIconSizes.Inline,
                tint = medalTint,
            )
        }
        Spacer(modifier = Modifier.width(PaperInkDimens.SpaceMd))
        Text(
            text = name,
            style = PaperInkType.Tab,
            color = if (earned) colorScheme.onSurface else colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(PaperInkDimens.SpaceSm))
        Text(
            text = status,
            style = PaperInkType.Label,
            color = if (earned) colorScheme.primary else colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
        if (earned && onTogglePin != null) {
            Spacer(modifier = Modifier.width(PaperInkDimens.SpaceXs))
            AchievementPinButton(
                isPinned = isPinned,
                awardName = name,
                onToggle = { onTogglePin(definition.id) },
                testTag = "achievements_pin_${definition.id}",
            )
        }
    }
}

/**
 * The streak: how many days in a row are still alive, and which days of THIS
 * week carried listening.
 *
 * Cells are 40 dp tall with the direction's small radius. The `today` cell is
 * marked with a lamp BORDER rather than a solid lamp fill: the prototype's
 * solid fill puts 11 sp text on `#A96A12` at 4.2:1, and a 2 dp border makes the
 * same "lamp = today" statement while clearing the contrast contract.
 */
@Composable
private fun StreakBlock(week: AchievementPresentation.Week) {
    val colorScheme = MaterialTheme.colorScheme
    Spacer(modifier = Modifier.height(PaperInkDimens.SpaceSection))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("achievements_streak"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.achievements_streak_title),
            style = PaperInkType.RowTitle,
            color = colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = pluralStringResource(
                R.plurals.achievements_streak_run,
                week.streak,
                week.streak,
            ),
            style = PaperInkType.Label,
            color = colorScheme.primary,
        )
    }
    Spacer(modifier = Modifier.height(PaperInkDimens.SpaceSm))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("achievements_streak_days"),
        horizontalArrangement = Arrangement.spacedBy(PaperInkDimens.SpaceSm),
    ) {
        for (index in 0 until AchievementPresentation.Week.DAYS_IN_WEEK) {
            StreakDayCell(
                weekdayLabel = stringResource(weekdayLabel(index)),
                listened = week.flags.getOrElse(index) { false },
                isToday = index == week.todayIndex,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StreakDayCell(
    weekdayLabel: String,
    listened: Boolean,
    isToday: Boolean,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val inkTheme = isInkTheme()
    val listenedLabel = stringResource(R.string.achievements_day_listened)
    val todayLabel = stringResource(R.string.achievements_day_today)
    val description = buildString {
        append(weekdayLabel)
        append(", ")
        append(if (listened) listenedLabel else "—")
        if (isToday) {
            append(", ")
            append(todayLabel)
        }
    }
    val background = when {
        listened && inkTheme -> PaperInkPalette.LampSoftNight
        listened -> PaperInkPalette.LampSoft
        // The track is a LIGHT paper role; on the ink page it would be a bright
        // tile, so the night surface takes its place.
        inkTheme -> PaperInkPalette.NightHigh
        else -> PaperInkPalette.Track
    }
    val textColor = when {
        listened && inkTheme -> PaperInkPalette.OnLampSoftNight
        listened -> PaperInkPalette.OnLampSoft
        else -> colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .height(PaperInkDimens.AchievementWeekCellHeight)
            .clip(RoundedCornerShape(PaperInkDimens.RadiusSmall))
            .background(background)
            .then(
                if (isToday) {
                    Modifier.border(
                        width = 2.dp,
                        color = PaperInkPalette.Lamp,
                        shape = RoundedCornerShape(PaperInkDimens.RadiusSmall),
                    )
                } else {
                    Modifier
                }
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = weekdayLabel,
            style = PaperInkType.Micro,
            color = textColor,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** The seven compact weekday labels, Monday first. */
private fun weekdayLabel(index: Int): Int = when (index) {
    0 -> R.string.achievements_weekday_1
    1 -> R.string.achievements_weekday_2
    2 -> R.string.achievements_weekday_3
    3 -> R.string.achievements_weekday_4
    4 -> R.string.achievements_weekday_5
    5 -> R.string.achievements_weekday_6
    else -> R.string.achievements_weekday_7
}

/** Ink when the screen's own ground is dark — the same test the theme seam uses. */
@Composable
private fun isInkTheme(): Boolean =
    ColorContrast.relativeLuminance(MaterialTheme.colorScheme.background) < 0.5
