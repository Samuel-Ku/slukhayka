package com.slukhayka.audiobooks.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.ui.screens.appNotificationSettingsIntent
import com.slukhayka.audiobooks.ui.screens.preOAppDetailsIntent
import com.slukhayka.audiobooks.ui.screens.systemNotificationSettingsIntent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1810 — the system door works on minSdk 24 and its channel branch is real. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationSystemSettingsIntentTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `pre-O opens the app system details page`() {
        val intent = preOAppDetailsIntent(context)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    @Test
    fun `O plus selector opens the app notification page when no channel is given`() {
        val intent = systemNotificationSettingsIntent(context, null)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `the existing channel opens its own system page`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("source_watch", "Source watch", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val intent = systemNotificationSettingsIntent(context, "source_watch")
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals("source_watch", intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test
    fun `O plus app notification builder carries the package`() {
        val intent = appNotificationSettingsIntent(context)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
}
