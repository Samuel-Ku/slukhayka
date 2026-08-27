package com.slukhayka.audiobooks.data.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.ui.library.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * #392/#393 — foreground service that keeps an offline download alive
 * when the app is backgrounded and shows the Ongoing progress notification.
 *
 * One download at a time (the ViewModel already guards the second tap with
 * a toast). The service is dataSync foreground: it is killed when the
 * download finishes and the notification is removed.
 *
 * Without POST_NOTIFICATIONS grant the download still runs, but no
 * notification is shown (graceful degrade, #393).
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val bookId = intent?.getStringExtra(EXTRA_BOOK_ID) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Книга"
        val author = intent.getStringExtra(EXTRA_AUTHOR) ?: ""

        // If already downloading a different book, ignore (ViewModel already toasted)
        if (downloadJob?.isActive == true) {
            Log.w(TAG, "Download already in progress, ignoring start for $bookId")
            return START_NOT_STICKY
        }

        val hasNotificationPermission = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        val notification = buildNotification(title, author, bookId, progress = 0, totalBytes = null, downloadedBytes = 0, isApproximate = false)

        if (hasNotificationPermission) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, getServiceType())
            } catch (e: Exception) {
                Log.w(TAG, "startForeground failed: ${e.message}")
                // Fall back to background download without foreground
            }
        } else {
            Log.w(TAG, "POST_NOTIFICATIONS not granted — downloading without notification for $bookId")
        }

        setDownloading(bookId)

        downloadJob = scope.launch {
            try {
                // Observe bytes progress and update notification
                val bytesJob = launch {
                    App.instance.offlineDownloads.downloadBytesProgress.collect { map ->
                        val p = map[bookId] ?: return@collect
                        if (!hasNotificationPermission) return@collect
                        val updated = buildNotification(
                            title = title,
                            author = author,
                            bookId = bookId,
                            progress = p.completedChapters * 100 / p.totalChapters.coerceAtLeast(1),
                            totalBytes = p.totalBytes,
                            downloadedBytes = p.downloadedBytes,
                            isApproximate = p.isApproximate,
                            completedChapters = p.completedChapters,
                            totalChapters = p.totalChapters
                        )
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(NOTIFICATION_ID, updated)
                    }
                }

                val result = App.instance.offlineDownloads.downloadAudiobookOffline(bookId)

                bytesJob.cancel()

                // Final notification: remove
                if (hasNotificationPermission) {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.cancel(NOTIFICATION_ID)
                }

                setResult(bookId, result)
                Log.i(TAG, "Download finished for $bookId: $result")

            } catch (e: Exception) {
                Log.w(TAG, "Download failed for $bookId", e)
                if (hasNotificationPermission) {
                    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                }
            } finally {
                setDownloading(null)
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        downloadJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun getServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= 34) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Завантаження",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Прогрес завантаження аудіокниг для офлайн прослуховування"
                    setShowBadge(false)
                    enableVibration(false)
                    enableLights(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(
        title: String,
        author: String,
        bookId: String,
        progress: Int = 0,
        totalBytes: Long? = null,
        downloadedBytes: Long = 0,
        isApproximate: Boolean = false,
        completedChapters: Int = 0,
        totalChapters: Int = 0
    ): Notification {
        val displayTitle = if (author.isNotBlank()) "$title — $author" else title
        val contentText = when {
            totalChapters > 0 && totalBytes != null -> {
                val totalStr = formatBytes(totalBytes)
                val downloadedStr = formatBytes(downloadedBytes)
                val prefix = if (isApproximate) "~" else ""
                "Розділ $completedChapters/$totalChapters • $downloadedStr / $prefix$totalStr ($progress%)"
            }
            totalChapters > 0 -> "Розділ $completedChapters/$totalChapters • $progress%"
            totalBytes != null -> "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)} ($progress%)"
            else -> "Завантаження… $progress%"
        }

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_BOOK_ID, bookId)
            action = Intent.ACTION_VIEW
        }
        val pendingIntent = PendingIntent.getActivity(
            this, bookId.hashCode(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher) // fallback; replace with download icon if available
            .setContentTitle("Завантаження: $displayTitle")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val TAG = "DownloadService"
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_BOOK_ID = "bookId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_AUTHOR = "author"
        const val EXTRA_OPEN_BOOK_ID = "openBookId"

        private val _downloadingBookId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val downloadingBookId: kotlinx.coroutines.flow.StateFlow<String?> = _downloadingBookId.asStateFlow()

        private val _downloadResult = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, OfflineDownloads.OfflineDownloadResult>?>(null)
        val downloadResult: kotlinx.coroutines.flow.StateFlow<Pair<String, OfflineDownloads.OfflineDownloadResult>?> = _downloadResult.asStateFlow()

        fun setDownloading(bookId: String?) {
            _downloadingBookId.value = bookId
        }

        fun setResult(bookId: String, result: OfflineDownloads.OfflineDownloadResult) {
            _downloadResult.value = bookId to result
        }

        fun clearResult() {
            _downloadResult.value = null
        }

        fun startIntent(context: Context, bookId: String, title: String, author: String): Intent =
            Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_BOOK_ID, bookId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_AUTHOR, author)
            }
    }
}
