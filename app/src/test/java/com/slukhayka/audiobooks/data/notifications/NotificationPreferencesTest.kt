package com.slukhayka.audiobooks.data.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR-0052 §1–5 — the two local event mutes: default ON (the features are
 * already opt-in) and survive a store re-creation, because a restart is what
 * the listener would otherwise have to remember.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationPreferencesTest {

    private lateinit var context: Context

    @Before
    fun resetPrefs() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("notification_preferences", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `both event families default to enabled`() {
        val prefs = NotificationPreferences(context)

        assertTrue(prefs.sourceAppearanceEnabled.value)
        assertTrue(prefs.peopleNewArrivalsEnabled.value)
    }

    @Test
    fun `muting persists across store re-creation and keeps the other family`() {
        NotificationPreferences(context).apply {
            setSourceAppearanceEnabled(false)
        }

        val reopened = NotificationPreferences(context)
        assertFalse("source appearance mute must survive", reopened.sourceAppearanceEnabled.value)
        assertTrue(
            "muting one family must not mute the other",
            reopened.peopleNewArrivalsEnabled.value
        )

        reopened.setPeopleNewArrivalsEnabled(false)
        val again = NotificationPreferences(context)
        assertFalse(again.sourceAppearanceEnabled.value)
        assertFalse(again.peopleNewArrivalsEnabled.value)
    }
}
