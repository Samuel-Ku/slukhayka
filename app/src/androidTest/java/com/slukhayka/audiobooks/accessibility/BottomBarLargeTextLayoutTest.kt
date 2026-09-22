package com.slukhayka.audiobooks.accessibility

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slukhayka.audiobooks.AppBottomBar
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.testing.TestHostActivity
import com.slukhayka.audiobooks.ui.SelectedTab
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #852 — the bottom bar's labels at 200 % text, without touching phone settings.
 *
 * Split out of [SettingsNavigationTest]: that class drives the real
 * [com.slukhayka.audiobooks.MainActivity] routes, and a test that draws its own
 * tree cannot share an activity whose `onCreate` already set content — the pair
 * produced «No compose hierarchies found in the app». This half uses the
 * content-free [TestHostActivity] (#766 A2) and the rule's own `setContent`.
 */
@RunWith(AndroidJUnit4::class)
class BottomBarLargeTextLayoutTest {

    @get:Rule val rule = createAndroidComposeRule<TestHostActivity>()

    @Test fun largeTextLabelsFitWithoutChangingPhoneSettings() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AudiobookTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp).height(480.dp)) {
                        // #956 — the bar's real four destinations; SETTINGS
                        // has not been one since #860.
                        AppBottomBar(SelectedTab.LISTEN) { }
                    }
                }
            }
        }
        listOf(R.string.nav_listen, R.string.nav_explore, R.string.nav_library, R.string.nav_friends).forEach { res ->
            val label = rule.activity.getString(res)
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("Clipped label: $label", layouts.single().hasVisualOverflow)
        }
    }
}
