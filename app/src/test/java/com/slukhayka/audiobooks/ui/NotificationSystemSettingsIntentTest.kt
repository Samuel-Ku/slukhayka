package com.slukhayka.audiobooks.ui

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.ui.screens.systemNotificationSettingsIntent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #1810 — the system door works on minSdk 24, not only on API 26+. */
@RunWith(RobolectricTestRunner::class)
class NotificationSystemSettingsIntentTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `pre-O opens the app system details page`() {
        val intent = systemNotificationSettingsIntent(context, null, sdkInt = 24)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    @Test
    fun `O plus opens the app notification page when no channel is given`() {
        val intent = systemNotificationSettingsIntent(context, null, sdkInt = 36)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
}
