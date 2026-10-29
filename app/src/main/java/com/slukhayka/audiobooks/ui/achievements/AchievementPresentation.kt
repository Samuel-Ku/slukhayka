package com.slukhayka.audiobooks.ui.achievements

import androidx.compose.ui.graphics.vector.ImageVector
import com.slukhayka.audiobooks.data.achievements.AchievementDefinition
import com.slukhayka.audiobooks.data.achievements.AchievementMetric
import com.slukhayka.audiobooks.data.achievements.AchievementProgress
import com.slukhayka.audiobooks.data.achievements.ListeningRhythm
import com.slukhayka.audiobooks.ui.icons.PaperInkIcons
import java.time.LocalDate
import kotlin.math.roundToInt

/** Milliseconds in one hour — the unit the hour-shaped awards are spoken in. */
private const val HOUR_MILLIS = 3_600_000L

/**
 * F18 of the 2026-10-28 device audit — the «Досягнення» screen's layout
 * decisions, decided as data.
 *
 * The screen owns pixels; this object owns the questions a golden-less test can
 * answer: which medal an award wears, what its status line says, what share of
 * the catalogue the hero ring shows, and which days of the current week the
 * strip lights up. Nothing here reads a clock, a resource or a database — the
 * caller passes the snapshot and today's date, so every answer is pinnable.
 *
 * The meaning of a metric stays with [AchievementMetric.value]: this file
 * formats what the domain already computed instead of re-deriving it.
 */
object AchievementPresentation {

    /** Unit a metric's numbers are SPOKEN in on the status line. */
    enum class Unit {
        /** Books, days, doors, reviews, genres — the number is its own noun. */
        COUNT,

        /** Millisecond metrics whose every threshold is a whole number of hours. */
        HOURS,
    }

    /** One row's real progress towards its own threshold. */
    data class Progress(val current: Long, val target: Long, val unit: Unit) {
        val fraction: Float
            get() = if (target <= 0L) 0f else (current.toFloat() / target).coerceIn(0f, 1f)

        /** Whole hours of a [Unit.HOURS] metric, floored: 59 minutes is not an hour. */
        val currentHours: Long get() = current / HOUR_MILLIS

        /** Whole hours of a [Unit.HOURS] threshold. */
        val targetHours: Long get() = target / HOUR_MILLIS
    }

    /** The hero panel: how many awards are earned out of the whole catalogue. */
    data class Hero(val earned: Int, val total: Int) {
        val fraction: Float
            get() = if (total <= 0) 0f else (earned.toFloat() / total).coerceIn(0f, 1f)

        val percent: Int get() = (fraction * 100f).roundToInt()
    }

    /**
     * The current calendar week, Monday first, plus the run that is still alive.
     *
     * [todayIndex] is 0 for Monday and 6 for Sunday. [streak] is the CURRENT run
     * (a silent today does not end yesterday's run); [isVisible] is false when
     * there is nothing honest to draw, and the screen then renders no block at
     * all rather than a row of zeros.
     */
    data class Week(val flags: List<Boolean>, val todayIndex: Int, val streak: Int) {
        val isVisible: Boolean get() = streak > 0 && flags.size == DAYS_IN_WEEK

        companion object {
            const val DAYS_IN_WEEK = 7
            val Empty = Week(emptyList(), -1, 0)
        }
    }

    /**
     * Millisecond metrics whose thresholds are written in whole hours in the
     * catalogue. Every other metric counts things, and a day is not an hour.
     */
    private val hourMetrics = setOf(
        AchievementMetric.LISTENING_MILLIS,
        AchievementMetric.OFFLINE_MILLIS,
        AchievementMetric.CAST_MILLIS,
        AchievementMetric.NIGHT_MILLIS,
        AchievementMetric.LONGEST_SESSION_MILLIS,
        AchievementMetric.LONGEST_OFFLINE_SESSION_MILLIS,
        AchievementMetric.BEST_DAY_MILLIS,
    )

    fun unitOf(metric: AchievementMetric): Unit =
        if (metric in hourMetrics) Unit.HOURS else Unit.COUNT

    /** The hero for a board: earn count against the catalogue size. */
    fun hero(earned: Int, total: Int): Hero = Hero(earned = maxOf(earned, 0), total = maxOf(total, 0))

    /** Where a definition stands against the snapshot the evaluator reads. */
    fun progress(progress: AchievementProgress, definition: AchievementDefinition): Progress =
        Progress(
            // A metric can overshoot its threshold (500 books against a 250 award);
            // the row shows the AWARD's progress, so the bar never passes its end.
            current = definition.metric.value(progress, definition.genreId).coerceIn(0L, definition.threshold),
            target = definition.threshold,
            unit = unitOf(definition.metric),
        )

    /**
     * The award's own medal for an EARNED row.
     *
     * The glyph follows the GROUP, not the individual award: a registry of sixty
     * medals reads as a taxonomy ("what kind of listening this was"), while one
     * glyph per award would read as sixty unrelated badges. Locked rows do not
     * use this — they wear [PaperInkIcons.Lock] regardless of group.
     */
    fun medalGlyph(definition: AchievementDefinition): ImageVector = when (definition.group) {
        "first_steps" -> PaperInkIcons.Check
        "hours", "sessions" -> PaperInkIcons.Timer
        "books", "books_shape" -> PaperInkIcons.Book
        "habits" -> PaperInkIcons.Heart
        "relisten" -> PaperInkIcons.Replay
        "series" -> PaperInkIcons.List
        "regularity" -> PaperInkIcons.Flame
        "offline" -> PaperInkIcons.Downloaded
        "doors" -> PaperInkIcons.Globe
        "languages" -> PaperInkIcons.Translate
        "genres" -> PaperInkIcons.Grid
        "mechanisms" -> PaperInkIcons.Refresh
        "cast" -> PaperInkIcons.Headphones
        // A hidden award that is now EARNED is an ordinary award; the surprise was
        // the reveal, not a permanent different look.
        else -> PaperInkIcons.Medal
    }

    /** The current-week strip for [days], as the «Досягнення» screen draws it. */
    fun week(days: List<ListeningRhythm.Day>, today: LocalDate): Week = Week(
        flags = ListeningRhythm.weekFlags(days, today),
        todayIndex = today.dayOfWeek.value - 1,
        streak = ListeningRhythm.currentStreak(days, today),
    )
}
