package com.slukhayka.audiobooks.ui.achievements

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.achievements.AchievementCatalog
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

        for (id in ids) {
            val notice = achievementNotice(context, id)
            assertTrue("нагорода «$id» дала порожній текст", notice.isNotBlank())
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
}
