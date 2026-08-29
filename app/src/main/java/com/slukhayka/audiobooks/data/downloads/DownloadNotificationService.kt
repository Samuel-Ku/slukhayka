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

/**
 * #393 — Foreground service showing an ongoing download notification.
 * IMPORTANCE_LOW (no sound), progress bar, chapter count, MB, tap opens book.
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
                startForeground(NOTIFICATION_ID, buildNotification(bookId, 0, 0, null, false))
            }
            ACTION_UPDATE -> {
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification(
                    currentBookId,
                    intent.getIntExtra(EXTRA_COMPLETED, 0),
                    intent.getIntExtra(EXTRA_TOTAL, 0),
                    intent.getLongExtra(EXTRA_TOTAL_BYTES, -1).takeIf { it >= 0 },
                    intent.getBooleanExtra(EXTRA_IS_APPROXIMATE, false)
                ))
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
        return builder.build()
    }

    override fun onDestroy() { super.onDestroy(); stopForeground(STOP_FOREGROUND_REMOVE) }

    companion object {
        const val CHANNEL_ID = "download_progress"
        const val NOTIFICATION_ID = 2001
        private const val ACTION_START = "com.slukhayka.DOWNLOAD_START"
        private const val ACTION_UPDATE = "com.slukhayka.DOWNLOAD_UPDATE"
        private const val ACTION_STOP = "com.slukhayka.DOWNLOAD_STOP"
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

        fun start(ctx: Context, bookId: String, title: String, author: String) {
            ctx.startForegroundService(Intent(ctx, DownloadNotificationService::class.java).apply {
                action = ACTION_START; putExtra(EXTRA_BOOK_ID, bookId)
                putExtra(EXTRA_TITLE, title); putExtra(EXTRA_AUTHOR, author)
            })
        }
        fun updateProgress(ctx: Context, completed: Int, total: Int, totalBytes: Long?, isApprox: Boolean) {
            ctx.startService(Intent(ctx, DownloadNotificationService::class.java).apply {
                action = ACTION_UPDATE; putExtra(EXTRA_COMPLETED, completed)
                putExtra(EXTRA_TOTAL, total); putExtra(EXTRA_TOTAL_BYTES, totalBytes ?: -1L)
                putExtra(EXTRA_IS_APPROXIMATE, isApprox)
            })
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, DownloadNotificationService::class.java).apply { action = ACTION_STOP })
        }
    }
}
