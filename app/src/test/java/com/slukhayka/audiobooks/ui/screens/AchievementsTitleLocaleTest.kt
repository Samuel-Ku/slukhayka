package com.slukhayka.audiobooks.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.achievements.AchievementBoard
import com.slukhayka.audiobooks.data.achievements.AchievementDefinition
import com.slukhayka.audiobooks.data.achievements.AchievementMetric
import com.slukhayka.audiobooks.data.achievements.ListenerTitle
import com.slukhayka.audiobooks.testing.EnglishChromeWalk
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1824 — the listener title on «Досягнення» follows the App Locale.
 *
 * The tier line used to interpolate a Ukrainian literal kept inside the
 * `ListenerTitle` enum, so an EN or PL interface showed «Tytuł: Слухач»: the
 * enum carried content, not a resource. These tests read the real screen under
 * each locale and fail on the Ukrainian word itself, which is what a listener
 * sees, rather than on an implementation detail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AchievementsTitleLocaleTest {

    @get:Rule
    val rule = createComposeRule()

    private val award =
        AchievementDefinition("plain", "g", 1, AchievementMetric.COMPLETED_BOOKS, 1)
    private val board = AchievementBoard.of(listOf(award), setOf("plain"))

    private fun render(title: ListenerTitle) {
        rule.setContent {
            AchievementsScreen(
                board = board,
                title = title,
                showcase = emptyList(),
                onBackClick = {}
            )
        }
    }

    @Test
    @Config(qualifiers = "en-rUS", sdk = [36])
    fun `an english interface renders the first listener title in english`() {
        render(ListenerTitle.LISTENER)

        rule.onNodeWithTag("achievements_tier").assertTextEquals("Title: Listener")
    }

    @Test
    @Config(qualifiers = "en-rUS", sdk = [36])
    fun `an english interface renders the top listener title in english`() {
        render(ListenerTitle.LIBRARY_VOICE)

        rule.onNodeWithTag("achievements_tier")
            .assertTextEquals("Title: Voice of the library")
    }

    @Test
    @Config(qualifiers = "pl", sdk = [36])
    fun `a polish interface renders the listener title in polish`() {
        render(ListenerTitle.LISTENER)

        rule.onNodeWithTag("achievements_tier").assertTextEquals("Tytuł: Słuchacz")
    }

    /**
     * Every rung, not just the two the screen tests happen to open: a title
     * that still carries a Ukrainian literal in `values-en` would otherwise
     * reach a listener the moment they reach that rung.
     */
    @Test
    @Config(qualifiers = "en-rUS", sdk = [36])
    fun `every english listener title is english`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        ListenerTitle.entries.forEach { title ->
            val label = context.getString(title.labelRes)
            assertFalse(
                "«$label» still leaks Ukrainian into the EN title ${title.name}",
                EnglishChromeWalk.CYRILLIC.containsMatchIn(label)
            )
        }
    }
}
