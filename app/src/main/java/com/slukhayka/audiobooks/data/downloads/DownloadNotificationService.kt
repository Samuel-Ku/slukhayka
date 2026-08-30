package com.slukhayka.audiobooks.data.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
 */
class DownloadNotificationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: return START_NOT_STICKY
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                author = intent.getStringExtra(EXTRA_AUTHOR).orEmpty()
                currentBookId = bookId
                isPaused = false
                startForeground(NOTIFICATION_ID, buildNotification(bookId, 0, 0, null, false))
            }
            ACTION_UPDATE -> {
                val nm = getSystemService(NotificationManager::class.java)
                isPaused = false
                currentBookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: currentBookId
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
                nm.notify(NOTIFICATION_ID, buildPausedNotification(currentBookId))
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

    private fun buildNotification(
        bookId: String, completed: Int, total: Int,
        totalBytes: Long?, isApproximate: Boolean
    ): Notification {
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

    private fun buildPausedNotification(bookId: String): Notification {
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
            .setContentText(getString(R.string.download_notification_paused))
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

    override fun onDestroy() { super.onDestroy(); stopForeground(STOP_FOREGROUND_REMOVE) }

    companion object {
        const val CHANNEL_ID = "download_progress"
        const val NOTIFICATION_ID = 2001
        private const val ACTION_START = "com.slukhayka.DOWNLOAD_START"
        private const val ACTION_UPDATE = "com.slukhayka.DOWNLOAD_UPDATE"
        private const val ACTION_PAUSED = "com.slukhayka.DOWNLOAD_PAUSED"
        private const val ACTION_STOP = "com.slukhayka.DOWNLOAD_STOP"
        const val ACTION_PAUSE_REQUEST = "com.slukhayka.DOWNLOAD_PAUSE_REQUEST"
        const val ACTION_CONTINUE_REQUEST = "com.slukhayka.DOWNLOAD_CONTINUE_REQUEST"
        const val ACTION_CANCEL_REQUEST = "com.slukhayka.DOWNLOAD_CANCEL_REQUEST"
        private const val EXTRA_BOOK_ID = "book_id"
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

        fun start(ctx: Context, bookId: String, title: String, author: String) {
            ctx.startForegroundService(Intent(ctx, DownloadNotificationService::class.java).apply {
                action = ACTION_START; putExtra(EXTRA_BOOK_ID, bookId)
                putExtra(EXTRA_TITLE, title); putExtra(EXTRA_AUTHOR, author)
            })
        }
        fun updateProgress(ctx: Context, bookId: String, completed: Int, total: Int, totalBytes: Long?, isApprox: Boolean) {
            ctx.startService(Intent(ctx, DownloadNotificationService::class.java).apply {
                action = ACTION_UPDATE; putExtra(EXTRA_COMPLETED, completed)
                putExtra(EXTRA_BOOK_ID, bookId)
                putExtra(EXTRA_TOTAL, total); putExtra(EXTRA_TOTAL_BYTES, totalBytes ?: -1L)
                putExtra(EXTRA_IS_APPROXIMATE, isApprox)
            })
        }
        fun notifyPaused(ctx: Context, bookId: String) {
            ctx.startService(Intent(ctx, DownloadNotificationService::class.java).apply {
                action = ACTION_PAUSED; putExtra(EXTRA_BOOK_ID, bookId)
            })
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, DownloadNotificationService::class.java).apply { action = ACTION_STOP })
        }
    }
}

/** #394 — notification button actions forwarded to the ViewModel. */
sealed interface NotificationAction {
    data class Pause(val bookId: String) : NotificationAction
    data class Continue(val bookId: String) : NotificationAction
    data class Cancel(val bookId: String) : NotificationAction
}
