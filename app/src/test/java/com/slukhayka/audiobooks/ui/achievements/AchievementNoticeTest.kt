package com.slukhayka.audiobooks.ui.achievements

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.achievements.AchievementCatalog
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #700/#701 — EVERY award in the catalogue must produce a notice.
 *
 * This test exists because its absence let a real crash reach CI. The notice
 * used to assume any id it did not recognise was an `hours_*` one:
 *
 * ```
 * val hours = requireNotNull(id.removePrefix("hours_").toIntOrNull())
 * ```
 *
 * That held while the catalogue had only the first-steps awards and the hour
 * ladder. Once book, speed, door, language and mechanism awards were added,
 * earning any of them threw — and no test noticed, because the achievements
 * tests never call `achievementNotice`.
 *
 * Walking the CATALOGUE rather than a hand-written list is the point: an award
 * added later is covered automatically, so this guard cannot rot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AchievementNoticeTest {

    @Test
    fun `every catalogued award produces a non-blank notice`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ids = AchievementCatalog.definitions.map { it.id }

        assertTrue("каталог не має бути порожнім", ids.isNotEmpty())

        val generic = context.getString(R.string.achievement_awarded_generic)

        for (id in ids) {
            val notice = achievementNotice(context, id)
            assertTrue("нагорода «$id» дала порожній текст", notice.isNotBlank())
            // A catalogue award must have a REAL name. Falling back to the
            // generic line means nobody wrote one — which is exactly the gap
            // this test was extended to catch after 22 awards shipped unnamed.
            assertNotEquals("нагорода «$id» не має назви — показується загальний рядок", generic, notice)
        }
    }

    /**
     * The hour ladder still reads as a proper plural — the fix must not have
     * flattened a case that already worked.
     */
    @Test
    fun `an hour award still names its hours`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notice = achievementNotice(context, "hours_10")

        assertTrue("годинна нагорода мусить згадувати своє число: $notice", notice.contains("10"))
    }

    /**
     * An id nobody ever defined must not crash either: notices are shown from
     * persisted rows, and a row can outlive the definition that made it.
     */
    @Test
    fun `an unknown award id degrades instead of throwing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertTrue(achievementNotice(context, "no_such_award").isNotBlank())
        assertTrue(achievementNotice(context, "hours_").isNotBlank())
        assertTrue(achievementNotice(context, "hours_notanumber").isNotBlank())
    }

    /**
     * #705 (T7) — the SAME catalogue, answering the opposite question.
     *
     * The notice may fall back to the generic name because it is a private
     * toast. The publisher may NOT: a public profile must never claim an award
     * it cannot name, so an unnameable id has to be null here and be dropped.
     */
    @Test
    fun `an unnameable award has no name for the publisher, only a fallback for the notice`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertNull(achievementName(context, "no_such_award"))
        assertNull(achievementName(context, "hours_"))
        assertNull(achievementName(context, "hours_notanumber"))
        // The interesting one: a numeric suffix this build has no rule for.
        // `no_such_award` and `hours_` bail out earlier, at the number parse, so
        // only this case reaches the final fallback and proves it is a null.
        assertNull(achievementName(context, "mystery_10"))
        assertNull(achievementName(context, "first_book_extra_3"))

        // A real award is named by both, and the notice keeps its prefix.
        val name = achievementName(context, "hours_10")
        assertNotNull(name)
        assertTrue(achievementNotice(context, "hours_10").contains(name!!))
    }
}
