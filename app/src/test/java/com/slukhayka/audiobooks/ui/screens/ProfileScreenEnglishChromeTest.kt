package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.identity.FakeListenerIdentity
import com.slukhayka.audiobooks.data.identity.ListenerProfile
import com.slukhayka.audiobooks.testing.EnglishChromeWalk
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1774 — Профіль spoke Ukrainian on a Polish phone because its intro, two
 * headings and the progress-sync pair were literals in the composition.
 *
 * The EN run walks the whole semantics tree ([EnglishChromeWalk]), so a single
 * leftover Ukrainian literal fails the test rather than hiding behind the few
 * labels asserted below. The PL run proves the Polish values really resolve
 * instead of falling back to `values/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS", sdk = [36])
class ProfileScreenEnglishChromeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Latin-only data: any Cyrillic left in the tree is chrome, not content. */
    private val identity = FakeListenerIdentity(
        seededProfile = ListenerProfile(uid = "en-profile", nickname = "Reader")
    )

    @Test
    fun profileChromeSpeaksEnglish() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Box(Modifier.width(411.dp).height(1400.dp)) {
                    ProfileScreen(
                        identity = identity,
                        onBackClick = {},
                        hiddenAuthors = listOf("Tolkien")
                    )
                }
            }
        }

        EnglishChromeWalk.assertNoCyrillic(composeTestRule, "profile")
        composeTestRule.onNodeWithText("Nickname").assertExists()
        composeTestRule.onNodeWithText("Hidden authors").assertExists()
        composeTestRule.onNodeWithText("Progress sync").assertExists()
        composeTestRule.onNodeWithText("Your position mirrors across your devices")
            .assertExists()
    }

    @Test
    @Config(qualifiers = "pl-rPL")
    fun profileChromeSpeaksPolish() {
        composeTestRule.setContent {
            AudiobookTheme(darkTheme = true) {
                Box(Modifier.width(411.dp).height(1400.dp)) {
                    ProfileScreen(
                        identity = identity,
                        onBackClick = {},
                        hiddenAuthors = listOf("Tolkien")
                    )
                }
            }
        }

        EnglishChromeWalk.assertNoCyrillic(composeTestRule, "profile (pl)")
        composeTestRule.onNodeWithText("Pseudonim").assertExists()
        composeTestRule.onNodeWithText("Ukryci autorzy").assertExists()
        composeTestRule.onNodeWithText("Synchronizacja postępu").assertExists()
        composeTestRule.onNodeWithText("Pozycja jest lustrzana na Twoich urządzeniach")
            .assertExists()
    }
}
