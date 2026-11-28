package com.slukhayka.audiobooks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.slukhayka.audiobooks.data.watch.SourceWatchNotifier
import com.slukhayka.audiobooks.ui.screens.PLAYBACK_NOTIFICATION_CHANNEL_ID
import com.slukhayka.audiobooks.ui.screens.SettingsScreen
import com.slukhayka.audiobooks.ui.theme.AudiobookTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-0052 §1–4, §8–9 — the settings home for notification control:
 *  - the two EVENT families are real switches, ON by default;
 *  - the two SERVICE families are read-only rows (no switch semantics) that
 *    lead to the Android channel;
 *  - when Android blocks notifications, the section says so instead of showing
 *    a live-looking switch.
 *
 * The resource test at the end guards the copy the section depends on: a
 * listener on EN/PL must not get the Ukrainian fallback (ADR-0033).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationSettingsUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setScreen(
        systemNotificationsEnabled: Boolean = true,
        onSourceAppearanceChange: (Boolean) -> Unit = {},
        onPeopleNewArrivalsChange: (Boolean) -> Unit = {},
        onOpenNotificationSettings: (String?) -> Unit = {},
    ) {
        compose.setContent {
            AudiobookTheme(darkTheme = false) {
                ScreenSurface {
                    SettingsScreen(
                        systemNotificationsEnabled = systemNotificationsEnabled,
                        onNotificationSourceAppearanceChange = onSourceAppearanceChange,
                        onNotificationPeopleNewArrivalsChange = onPeopleNewArrivalsChange,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        onOpen = {},
                    )
                }
            }
        }
    }

    @Test
    fun `both event families are on by default and switch off through the row`() {
        var sourceAppearance: Boolean? = null
        var peopleArrivals: Boolean? = null
        setScreen(
            onSourceAppearanceChange = { sourceAppearance = it },
            onPeopleNewArrivalsChange = { peopleArrivals = it },
        )

        compose.onNodeWithTag("settings_notification_source_appearance")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsOn()
            .performClick()
        compose.onNodeWithTag("settings_notification_people_new_arrivals")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsOn()
            .performClick()

        assertEquals(false, sourceAppearance)
        assertEquals(false, peopleArrivals)
    }

    @Test
    fun `service families are read-only rows that open the system channel`() {
        var openedChannel: String? = null
        setScreen(onOpenNotificationSettings = { openedChannel = it })

        compose.onNodeWithTag("settings_notification_playback_row")
            .performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
        compose.onNodeWithTag("settings_notification_downloads_row")
            .performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))

        compose.onNodeWithTag("settings_notification_playback_channel")
            .performScrollTo()
            .performClick()
        assertEquals(PLAYBACK_NOTIFICATION_CHANNEL_ID, openedChannel)
    }

    @Test
    fun `the event switches link to their own system channels`() {
        val opened = mutableListOf<String?>()
        setScreen(onOpenNotificationSettings = { opened += it })

        compose.onNodeWithTag("settings_notification_source_appearance_channel")
            .performScrollTo()
            .performClick()

        assertEquals(listOf<String?>(SourceWatchNotifier.CHANNEL_ID), opened)
    }

    @Test
    fun `system notifications off shows the honest notice instead of switches`() {
        setScreen(systemNotificationsEnabled = false)

        compose.onNodeWithTag("settings_notifications_disabled_notice")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("settings_notification_source_appearance").assertDoesNotExist()
        compose.onNodeWithTag("settings_notification_people_new_arrivals").assertDoesNotExist()

        // The service families are still explained even when Android is silent.
        compose.onNodeWithTag("settings_notification_playback_row")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the new notification strings exist in ukrainian english and polish`() {
        val root = listOf(File(System.getProperty("user.dir")), File(System.getProperty("user.dir"), "app"))
            .firstOrNull { File(it, "src/main/res").isDirectory }
            ?: error("app root not found from ${System.getProperty("user.dir")}")
        val uk = values(File(root, "src/main/res/values/strings.xml"))
        val en = values(File(root, "src/main/res/values-en/strings.xml"))
        val pl = values(File(root, "src/main/res/values-pl/strings_rest.xml"))
        val cyrillic = Regex("[А-Яа-яІіЇїЄєҐґ]")

        NEW_STRINGS.forEach { key ->
            assertTrue("$key missing from values/", key in uk)
            assertTrue("$key missing from values-en/", key in en)
            assertTrue("$key missing from values-pl/", key in pl)
            assertFalse("Ukrainian leaked into EN for $key", cyrillic.containsMatchIn(en.getValue(key)))
            assertFalse("Ukrainian leaked into PL for $key", cyrillic.containsMatchIn(pl.getValue(key)))
        }
    }

    private fun values(file: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val node = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        node.findAll(file.readText(charset = Charsets.UTF_8)).forEach { match ->
            out[match.groupValues[1]] = match.groupValues[2]
        }
        return out
    }

    companion object {
        private val NEW_STRINGS = listOf(
            "settings_group_notifications",
            "settings_notifications_source_appearance",
            "settings_notifications_source_appearance_desc",
            "settings_notifications_people_new_arrivals",
            "settings_notifications_people_new_arrivals_desc",
            "settings_notifications_open_channel",
            "settings_notifications_playback_title",
            "settings_notifications_playback_desc",
            "settings_notifications_downloads_title",
            "settings_notifications_downloads_desc",
            "settings_notifications_disabled_title",
            "settings_notifications_disabled_desc",
            "settings_notifications_open_system",
        )
    }
}

@Composable
private fun ScreenSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        content = content,
    )
}
