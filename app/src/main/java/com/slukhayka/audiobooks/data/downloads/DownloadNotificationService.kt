package com.slukhayka.audiobooks.data.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.App

/**
 * #393 + #394 — Foreground service showing an ongoing download notification.
 *
 * IMPORTANCE_LOW (no sound), progress bar, chapter count, MB, tap opens book.
 * #394 adds Pause / Continue / Cancel action buttons:
 * - During active download: Pause + Cancel buttons
 * - When paused: Continue + Cancel buttons
 * - Swipe disabled on ongoing (active); swipe on PAUSED hides notification only
 *
 * #1734 — on Android 15+ a `dataSync` foreground service gets a 6-hour budget
 * per 24 hours. Pausing therefore leaves the foreground — a paused transfer
 * must not spend that budget — and [onTimeout] stops the service within the
 * system's grace period instead of letting the process be killed.
 */
class DownloadNotificationService : Service() {

    private var isForeground = false
    private var timeoutHandled = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: return START_NOT_STICKY
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                author = intent.getStringExtra(EXTRA_AUTHOR).orEmpty()
                currentBookId = bookId
                isPaused = false
                timeoutHandled = false
                startForeground(NOTIFICATION_ID, buildNotification(bookId, 0, 0, null, false))
                isForeground = true
            }
            ACTION_UPDATE -> {
                currentBookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: currentBookId
                // #1734 — a late progress tick must not turn a paused state
                // back into "active": only ACTION_START (the explicit resume)
                // returns the service to the foreground.
                if (isPaused) return START_NOT_STICKY
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification(
                    currentBookId,
                    intent.getIntExtra(EXTRA_COMPLETED, 0),
                    intent.getIntExtra(EXTRA_TOTAL, 0),
                    intent.getLongExtra(EXTRA_TOTAL_BYTES, -1).takeIf { it >= 0 },
                    intent.getBooleanExtra(EXTRA_IS_APPROXIMATE, false)
                ))
            }
            ACTION_PAUSED -> {
                isPaused = true
                currentBookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: currentBookId
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildPausedNotification(
                    currentBookId, R.string.download_notification_paused
                ))
                // #1734 — paused is not data transfer. DETACH keeps the
                // paused notification and its Continue action; the service
                // itself stops counting as a dataSync foreground service.
                leaveForeground()
            }
            ACTION_PAUSE_REQUEST -> {
                val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: currentBookId
                (application as App).downloadNotificationActions.dispatch(NotificationAction.Pause(bookId))
            }
            ACTION_CONTINUE_REQUEST -> {
                val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: currentBookId
                (application as App).downloadNotificationActions.dispatch(NotificationAction.Continue(bookId))
            }
            ACTION_CANCEL_REQUEST -> {
                val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: currentBookId
                (application as App).downloadNotificationActions.dispatch(NotificationAction.Cancel(bookId))
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * #1734 — Android 15+ calls this when the app's `dataSync` foreground
     * time budget is exhausted (targetSdk 36). At that point the service is
     * no longer counted as foreground; it has a few seconds to stop itself
     * or the system throws a `RemoteServiceException` (shipped as a process
     * kill / ANR). A transfer that was still in flight becomes an honest
     * "stopped by the system" notification, detached so its Continue/Cancel
     * actions outlive the service.
     *
     * https://developer.android.com/develop/background-work/services/fgs/timeout
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        if (timeoutHandled) return
        timeoutHandled = true
        val transferInFlight = !isPaused
        if (transferInFlight) isPaused = true
        // DETACH keeps the (now non-foreground) notification: it is the only
        // place the listener can resume from. REMOVE here would erase it.
        // Detach first, then replace the content: the replacement must not be
        // tied back to the foreground-service notification.
        leaveForeground()
        if (transferInFlight && currentBookId.isNotEmpty()) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildPausedNotification(
                currentBookId, R.string.download_notification_system_stopped
            ))
        }
        stopSelf(startId)
    }

    /** #1734 — leave the foreground without dropping the notification. */
    private fun leaveForeground() {
        stopForeground(STOP_FOREGROUND_DETACH)
        isForeground = false
    }

    /**
     * Creates the progress channel once on API 26+. API 24–25 has no channels
     * at all, so there the same notification is published the only way the
     * platform offers — no channel, no per-channel description or badge.
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, getString(R.string.download_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.download_notification_channel_description)
                    setShowBadge(false)
                })
            }
        }
    }

    private fun buildNotification(
        bookId: String, completed: Int, total: Int,
        totalBytes: Long?, isApproximate: Boolean
    ): Notification {
        ensureChannel()
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("bookId", bookId)
            putExtra("openBookDetail", true)
        }
        val tapPending = PendingIntent.getActivity(
            this, bookId.hashCode(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val displayTitle = if (author.isNotBlank()) "$title — $author" else title
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.download_notification_title, displayTitle))
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(tapPending)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (total > 0) {
            val pct = completed * 100 / total
            val sizeText = if (totalBytes != null && totalBytes > 0) {
                val dl = totalBytes * completed / total / (1024 * 1024)
                val tot = totalBytes / (1024 * 1024)
                val p = (dl * 100 / tot).toInt()
                val approx = if (isApproximate) "~" else ""
                getString(R.string.download_notification_size, approx, dl, tot, p)
            } else ""
            val ch = getString(R.string.download_notification_chapters, completed, total)
            builder.setContentText(if (sizeText.isNotBlank()) "$ch • $sizeText" else ch)
                .setProgress(100, pct, false)
        } else {
            builder.setContentText(getString(R.string.download_notification_preparing))
                .setProgress(0, 0, true)
        }
        // #394 — Pause + Cancel actions
        builder.addAction(buildPauseAction(bookId))
        builder.addAction(buildCancelAction(bookId))
        return builder.build()
    }

    private fun buildPausedNotification(bookId: String, textRes: Int): Notification {
        ensureChannel()
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("bookId", bookId)
            putExtra("openBookDetail", true)
        }
        val tapPending = PendingIntent.getActivity(
            this, bookId.hashCode(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val displayTitle = if (author.isNotBlank()) "$title — $author" else title
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.download_notification_title, displayTitle))
            .setContentText(getString(textRes))
            .setOngoing(false) // swipe allowed when paused
            .setContentIntent(tapPending)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(buildContinueAction(bookId))
            .addAction(buildCancelAction(bookId))
            .build()
    }

    private fun buildPauseAction(bookId: String): NotificationCompat.Action {
        val intent = Intent(this, DownloadNotificationService::class.java).apply {
            action = ACTION_PAUSE_REQUEST
            putExtra(EXTRA_BOOK_ID, bookId)
        }
        val pending = PendingIntent.getService(
            this, "pause-$bookId".hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            0, getString(R.string.download_action_pause), pending
        ).build()
    }

    private fun buildContinueAction(bookId: String): NotificationCompat.Action {
        val intent = Intent(this, DownloadNotificationService::class.java).apply {
            action = ACTION_CONTINUE_REQUEST
            putExtra(EXTRA_BOOK_ID, bookId)
        }
        val pending = PendingIntent.getService(
            this, "continue-$bookId".hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            0, getString(R.string.download_action_continue), pending
        ).build()
    }

    private fun buildCancelAction(bookId: String): NotificationCompat.Action {
        val intent = Intent(this, DownloadNotificationService::class.java).apply {
            action = ACTION_CANCEL_REQUEST
            putExtra(EXTRA_BOOK_ID, bookId)
        }
        val pending = PendingIntent.getService(
            this, "cancel-$bookId".hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            0, getString(R.string.download_action_cancel), pending
        ).build()
    }

    override fun onDestroy() {
        // #1734 — a paused / system-stopped notification was detached on
        // purpose: it is the listener's only Continue/Cancel surface after
        // the service is gone. Only an active foreground notification is
        // ours to remove here.
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        isRunning = false
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "download_progress"
        const val NOTIFICATION_ID = 2001
        internal const val ACTION_START = "com.slukhayka.DOWNLOAD_START"
        internal const val ACTION_UPDATE = "com.slukhayka.DOWNLOAD_UPDATE"
        internal const val ACTION_PAUSED = "com.slukhayka.DOWNLOAD_PAUSED"
        internal const val ACTION_STOP = "com.slukhayka.DOWNLOAD_STOP"
        const val ACTION_PAUSE_REQUEST = "com.slukhayka.DOWNLOAD_PAUSE_REQUEST"
        const val ACTION_CONTINUE_REQUEST = "com.slukhayka.DOWNLOAD_CONTINUE_REQUEST"
        const val ACTION_CANCEL_REQUEST = "com.slukhayka.DOWNLOAD_CANCEL_REQUEST"
        internal const val EXTRA_BOOK_ID = "book_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_AUTHOR = "author"
        private const val EXTRA_COMPLETED = "completed"
        private const val EXTRA_TOTAL = "total"
        private const val EXTRA_TOTAL_BYTES = "total_bytes"
        private const val EXTRA_IS_APPROXIMATE = "is_approximate"

        private var currentBookId = ""
        private var title = ""
        private var author = ""
        private var isPaused = false

        /** #1734 — guards late progress/pause ticks after the service stopped. */
        @Volatile
        private var isRunning = false

        fun start(ctx: Context, bookId: String, title: String, author: String) {
            val intent = Intent(ctx, DownloadNotificationService::class.java).apply {
                action = ACTION_START; putExtra(EXTRA_BOOK_ID, bookId)
                putExtra(EXTRA_TITLE, title); putExtra(EXTRA_AUTHOR, author)
            }
            // API 26+ bans a background startService and wants the foreground
            // entry point; API 24–25 predates it and takes the same intent
            // through startService — never a silent no-op.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
        fun updateProgress(ctx: Context, bookId: String, completed: Int, total: Int, totalBytes: Long?, isApprox: Boolean) {
            // #1734 — after the dataSync timeout the service is stopping; a
            // late progress tick must not restart it from the background.
            if (!isRunning) return
            val intent = Intent(ctx, DownloadNotificationService::class.java).apply {
                action = ACTION_UPDATE; putExtra(EXTRA_COMPLETED, completed)
                putExtra(EXTRA_BOOK_ID, bookId)
                putExtra(EXTRA_TOTAL, total); putExtra(EXTRA_TOTAL_BYTES, totalBytes ?: -1L)
                putExtra(EXTRA_IS_APPROXIMATE, isApprox)
            }
            try {
                ctx.startService(intent)
            } catch (_: IllegalStateException) {
                // The service stopped between the check and the call; the
                // next ACTION_START carries the state that matters.
            }
        }
        fun notifyPaused(ctx: Context, bookId: String) {
            if (!isRunning) return
            ctx.startService(Intent(ctx, DownloadNotificationService::class.java).apply {
                action = ACTION_PAUSED; putExtra(EXTRA_BOOK_ID, bookId)
            })
        }
        fun stop(ctx: Context) {
            // #1734 — a direct cancel+stopService is idempotent and legal
            // from the background, unlike startService(ACTION_STOP): it also
            // removes a detached paused/system-stopped notification.
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            ctx.stopService(Intent(ctx, DownloadNotificationService::class.java))
        }
    }
}

/** #394 — notification button actions forwarded to the ViewModel. */
sealed interface NotificationAction {
    data class Pause(val bookId: String) : NotificationAction
    data class Continue(val bookId: String) : NotificationAction
    data class Cancel(val bookId: String) : NotificationAction
}
