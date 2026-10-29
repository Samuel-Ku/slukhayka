package com.slukhayka.audiobooks.ui.achievements

import com.slukhayka.audiobooks.data.achievements.AchievementCatalog
import com.slukhayka.audiobooks.data.achievements.AchievementDefinition
import com.slukhayka.audiobooks.data.achievements.AchievementMetric
import com.slukhayka.audiobooks.data.achievements.AchievementProgress
import com.slukhayka.audiobooks.data.achievements.ListeningRhythm
import com.slukhayka.audiobooks.ui.icons.PaperInkIcons
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F18 — the «Досягнення» screen's decisions, checked as data.
 *
 * These tests exist because the interesting failures on that screen are not
 * pixel failures: an award can show the wrong medal, a row can show a progress
 * number the award is not judged by, or the hero can claim a percentage the
 * catalogue does not support. None of that needs a device to be wrong, so none
 * of it needs a device to be caught.
 */
class AchievementPresentationTest {

    private val hour = 3_600_000L

    private fun definition(
        id: String,
        group: String,
        metric: AchievementMetric,
        threshold: Long,
        genreId: String? = null,
    ) = AchievementDefinition(id, group, 1, metric, threshold, genreId = genreId)

    // --- the medal ---------------------------------------------------------

    @Test
    fun `an earned medal follows its group, not its id`() {
        assertEquals(PaperInkIcons.Check.name, AchievementPresentation.medalGlyph(definition("x", "first_steps", AchievementMetric.EXPLICIT_BOOKS, 1)).name)
        assertEquals(PaperInkIcons.Timer.name, AchievementPresentation.medalGlyph(definition("x", "hours", AchievementMetric.LISTENING_MILLIS, hour)).name)
        assertEquals(PaperInkIcons.Book.name, AchievementPresentation.medalGlyph(definition("x", "books", AchievementMetric.COMPLETED_BOOKS, 1)).name)
        assertEquals(PaperInkIcons.Heart.name, AchievementPresentation.medalGlyph(definition("x", "habits", AchievementMetric.BOOKMARKS, 50)).name)
        assertEquals(PaperInkIcons.Library.name, AchievementPresentation.medalGlyph(definition("x", "series", AchievementMetric.COMPLETED_SERIES, 1)).name)
        assertEquals(PaperInkIcons.Replay.name, AchievementPresentation.medalGlyph(definition("x", "relisten", AchievementMetric.RELISTENS, 1)).name)
        assertEquals(PaperInkIcons.Flame.name, AchievementPresentation.medalGlyph(definition("x", "regularity", AchievementMetric.BEST_DAY_MILLIS, 3 * hour)).name)
        assertEquals(PaperInkIcons.Downloaded.name, AchievementPresentation.medalGlyph(definition("x", "offline", AchievementMetric.OFFLINE_MILLIS, hour)).name)
        assertEquals(PaperInkIcons.Globe.name, AchievementPresentation.medalGlyph(definition("x", "doors", AchievementMetric.USED_SOURCE_DOORS, 4)).name)
        assertEquals(PaperInkIcons.Translate.name, AchievementPresentation.medalGlyph(definition("x", "languages", AchievementMetric.KNOWN_LANGUAGES, 2)).name)
        assertEquals(PaperInkIcons.Grid.name, AchievementPresentation.medalGlyph(definition("x", "genres", AchievementMetric.DISTINCT_GENRES, 8)).name)
        assertEquals(PaperInkIcons.Refresh.name, AchievementPresentation.medalGlyph(definition("x", "mechanisms", AchievementMetric.CROSS_RESOLVES, 1)).name)
        assertEquals(PaperInkIcons.Headphones.name, AchievementPresentation.medalGlyph(definition("x", "cast", AchievementMetric.CAST_MILLIS, hour)).name)
    }

    @Test
    fun `an unknown group falls back to the generic medal instead of crashing`() {
        // A stored award can outlive the group it was written under (the
        // catalogue is code, the rows are data): the screen must still draw it.
        assertEquals(
            PaperInkIcons.Medal.name,
            AchievementPresentation.medalGlyph(definition("legacy", "a_group_this_build_never_had", AchievementMetric.COMPLETED_BOOKS, 1)).name,
        )
    }

    @Test
    fun `every award in the real catalogue wears a glyph the icon family owns`() {
        val names = PaperInkIcons.All.map { it.name }.toSet()
        AchievementCatalog.definitions.forEach { award ->
            assertTrue(
                "«${award.id}» (${award.group}) resolved to a glyph outside PaperInkIcons",
                AchievementPresentation.medalGlyph(award).name in names,
            )
        }
    }

    // --- the status line ---------------------------------------------------

    @Test
    fun `a count award reports its count against its own threshold`() {
        val award = definition("books_25", "books", AchievementMetric.COMPLETED_BOOKS, 25)
        val row = AchievementPresentation.progress(AchievementProgress(completedBooks = 11), award)

        assertEquals(11L, row.current)
        assertEquals(25L, row.target)
        assertEquals(AchievementPresentation.Unit.COUNT, row.unit)
        assertEquals(0.44f, row.fraction, 0.001f)
    }

    @Test
    fun `an hours award reports whole hours and floors the partial one`() {
        val award = AchievementCatalog.definitions.first { it.id == "hours_10" }
        val row = AchievementPresentation.progress(
            AchievementProgress(verifiedListeningMillis = 3 * hour + 59 * 60_000L),
            award,
        )

        assertEquals(AchievementPresentation.Unit.HOURS, row.unit)
        assertEquals(3L, row.currentHours)
        assertEquals(10L, row.targetHours)
    }

    @Test
    fun `a metric that overshoots shows the award's own end, not the raw count`() {
        val award = definition("books_10", "books", AchievementMetric.COMPLETED_BOOKS, 10)
        val row = AchievementPresentation.progress(AchievementProgress(completedBooks = 500), award)

        assertEquals(10L, row.current)
        assertEquals(1f, row.fraction, 0.0001f)
    }

    @Test
    fun `every millisecond metric is spoken in hours and the rest in counts`() {
        val hours = setOf(
            AchievementMetric.LISTENING_MILLIS,
            AchievementMetric.OFFLINE_MILLIS,
            AchievementMetric.CAST_MILLIS,
            AchievementMetric.NIGHT_MILLIS,
            AchievementMetric.LONGEST_SESSION_MILLIS,
            AchievementMetric.LONGEST_OFFLINE_SESSION_MILLIS,
            AchievementMetric.BEST_DAY_MILLIS,
        )
        AchievementMetric.entries.forEach { metric ->
            val expected = if (metric in hours) {
                AchievementPresentation.Unit.HOURS
            } else {
                AchievementPresentation.Unit.COUNT
            }
            assertEquals("$metric unit", expected, AchievementPresentation.unitOf(metric))
        }
    }

    // --- the hero ----------------------------------------------------------

    @Test
    fun `the hero percentage is the catalogue share, rounded`() {
        // 11 of 24 is 45.83% — the prototype's 46% is a ROUNDED share, not a
        // second stat. This pins the arithmetic so it cannot drift into floor.
        val hero = AchievementPresentation.hero(earned = 11, total = 24)

        assertEquals(11, hero.earned)
        assertEquals(24, hero.total)
        assertEquals(46, hero.percent)
        assertEquals(11f / 24f, hero.fraction, 0.0001f)
    }

    @Test
    fun `an empty catalogue cannot divide by zero`() {
        val hero = AchievementPresentation.hero(earned = 0, total = 0)

        assertEquals(0f, hero.fraction, 0f)
        assertEquals(0, hero.percent)
    }

    @Test
    fun `a full catalogue is a hundred percent and nothing overflows`() {
        assertEquals(100, AchievementPresentation.hero(63, 63).percent)
        // Nothing can be earned twice; a bad count still cannot draw a 120% ring.
        assertEquals(1f, AchievementPresentation.hero(64, 63).fraction, 0f)
        assertEquals(0, AchievementPresentation.hero(-1, 63).earned)
    }

    // --- the week ----------------------------------------------------------

    @Test
    fun `the presentation week is Monday first and knows its today`() {
        val today = LocalDate.of(2026, 10, 28) // a Wednesday
        val days = listOf(
            ListeningRhythm.Day(LocalDate.of(2026, 10, 26), 10 * ListeningRhythm.DAY_MILLIS),
            ListeningRhythm.Day(LocalDate.of(2026, 10, 27), 10 * ListeningRhythm.DAY_MILLIS),
            ListeningRhythm.Day(LocalDate.of(2026, 10, 28), 10 * ListeningRhythm.DAY_MILLIS),
        )

        val week = AchievementPresentation.week(days, today)

        assertEquals(listOf(true, true, true, false, false, false, false), week.flags)
        assertEquals(2, week.todayIndex)
        assertEquals(3, week.streak)
        assertTrue(week.isVisible)
    }

    @Test
    fun `a week without a live run is not visible`() {
        val today = LocalDate.of(2026, 10, 28)

        assertFalse(AchievementPresentation.week(emptyList(), today).isVisible)
        assertFalse(AchievementPresentation.Week.Empty.isVisible)
        assertEquals(0, AchievementPresentation.Week.Empty.streak)
    }
}
