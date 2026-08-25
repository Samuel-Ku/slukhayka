package com.slukhayka.audiobooks.accessibility

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.SelectedTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Foundation tracer through the real activity and its fresh-install Listen
 * landing. Deeper listener journeys are added by the integration ticket.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34)
class MainActivityAccessibilityTest {

    @get:Rule
    val notificationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun showDeterministicListenLanding() {
        runBlocking(Dispatchers.IO) {
            AudiobookDatabase.getDatabase(composeTestRule.activity).clearAllTables()
        }
        composeTestRule.activity.runOnUiThread {
            ViewModelProvider(composeTestRule.activity)[MainViewModel::class.java]
                .selectTab(SelectedTab.LISTEN)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun listenLandingPassesAutomatedAccessibilityChecks() {
        composeTestRule.waitUntilExactlyOneExists(
            hasTestTag("listen_screen"),
            timeoutMillis = 10_000
        )

        composeTestRule.enableAccessibilityChecks()
        composeTestRule.onRoot().tryPerformAccessibilityChecks()
    }
}
