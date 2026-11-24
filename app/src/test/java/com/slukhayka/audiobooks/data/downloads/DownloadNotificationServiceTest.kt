package com.slukhayka.audiobooks.data.downloads

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * #1734 — the dataSync foreground service must respect Android 15's 6-hour
 * budget: a pause leaves the foreground, and `onTimeout` stops the service
 * instead of letting the system kill the process. The paused/system-stopped
 * notification survives with its Continue action, and a repeated stop or
 * timeout callback is harmless.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DownloadNotificationServiceTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancel(DownloadNotificationService.NOTIFICATION_ID)
    }

    @Test
    fun `timeout stops foreground and service and leaves the system-stopped notification`() {
        val controller = startDownload(bookId = "book-timeout", startId = 11)
        val service = controller.get()
        val shadow = shadowOf(service)
        assertTrue("an active download is foreground", shadow.isLastForegroundNotificationAttached())

        service.onTimeout(11, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        assertTrue("timeout leaves the foreground", shadow.isForegroundStopped())
        assertTrue("timeout stops the service", shadow.isStoppedBySelf())
        assertEquals("the right startId is stopped", 11, shadow.getStopSelfId())
        assertFalse("the notification is detached, not attached", shadow.isLastForegroundNotificationAttached())
        assertFalse("the notification must not be removed", shadow.getNotificationShouldRemoved())

        val notification = latestNotification()
        assertNotNull("the system-stopped state stays visible", notification)
        assertEquals(
            context.getString(R.string.download_notification_system_stopped),
            notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        assertFalse(
            "the detached notification is swipeable",
            notification!!.isOngoingEvent(),
        )

        // A duplicated callback must not crash or re-enter the foreground.
        service.onTimeout(11, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        assertTrue(shadow.isStoppedBySelf())
        assertFalse(shadow.isLastForegroundNotificationAttached())
    }

    @Test
    fun `pause leaves the foreground and keeps the paused notification`() {
        val controller = startDownload(bookId = "book-pause", startId = 21)
        val service = controller.get()
        val shadow = shadowOf(service)
        assertTrue("an active download is foreground", shadow.isLastForegroundNotificationAttached())

        controller.withIntent(intent(DownloadNotificationService.ACTION_PAUSED))
            .startCommand(0, 22)

        assertTrue("pause leaves the foreground", shadow.isForegroundStopped())
        assertFalse("the notification is detached, not attached", shadow.isLastForegroundNotificationAttached())
        assertFalse("the notification must not be removed", shadow.getNotificationShouldRemoved())
        assertPausedNotification()

        // A late progress tick must not resurrect the active state.
        controller.withIntent(intent(DownloadNotificationService.ACTION_UPDATE))
            .startCommand(0, 23)
        assertTrue("pause survives a late progress tick", shadow.isForegroundStopped())
        assertPausedNotification()

        // An explicit resume is the only path back into the foreground.
        controller.withIntent(startIntent("book-pause"))
            .startCommand(0, 24)
        assertFalse("resume returns to the foreground", shadow.isForegroundStopped())
        assertTrue(shadow.isLastForegroundNotificationAttached())
    }

    @Test
    fun `destroy while paused keeps the paused notification for the listener`() {
        val controller = startDownload(bookId = "book-destroy", startId = 31)
        controller.withIntent(intent(DownloadNotificationService.ACTION_PAUSED))
            .startCommand(0, 32)
        assertPausedNotification()

        controller.destroy()

        val notification = latestNotification()
        assertNotNull("a paused notification outlives the service", notification)
        assertEquals(
            context.getString(R.string.download_notification_paused),
            notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test
    fun `stop is idempotent and removes the active notification`() {
        val controller = startDownload(bookId = "book-stop", startId = 41)
        val service = controller.get()
        val shadow = shadowOf(service)

        controller.withIntent(intent(DownloadNotificationService.ACTION_STOP))
            .startCommand(0, 42)
        assertTrue("stop reaches the service", shadow.isStoppedBySelf())
        assertNull("stop removes the notification", latestNotification())

        controller.withIntent(intent(DownloadNotificationService.ACTION_STOP))
            .startCommand(0, 43)
        assertNull("a repeated stop stays harmless", latestNotification())
    }

    private fun startDownload(bookId: String, startId: Int): ServiceController<DownloadNotificationService> =
        Robolectric.buildService(DownloadNotificationService::class.java)
            .withIntent(startIntent(bookId))
            .create()
            .startCommand(0, startId)

    private fun startIntent(bookId: String): Intent =
        intent(DownloadNotificationService.ACTION_START)
            .putExtra(DownloadNotificationService.EXTRA_BOOK_ID, bookId)

    private fun intent(action: String): Intent =
        Intent(context, DownloadNotificationService::class.java).setAction(action)

    private fun Notification.isOngoingEvent(): Boolean =
        (flags and Notification.FLAG_ONGOING_EVENT) != 0

    private fun latestNotification(): Notification? =
        shadowOf(notificationManager).getNotification(DownloadNotificationService.NOTIFICATION_ID)

    private fun assertPausedNotification() {
        val notification = latestNotification()
        assertNotNull("the paused notification stays visible", notification)
        assertEquals(
            context.getString(R.string.download_notification_paused),
            notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        assertFalse(
            "the paused notification is swipeable",
            notification!!.isOngoingEvent(),
        )
    }
}
