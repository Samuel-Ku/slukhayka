package com.slukhayka.audiobooks.data.personbookmarks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.MainActivity
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** #403 — one best-effort background check; no payload leaves the device. */
class PeopleNewArrivalWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? App ?: return Result.success()
        return try {
            app.sourceCatalog.refreshUnifiedCatalog()
            notifyIfNeeded(app)
            Result.success()
        } catch (_: Exception) {
            // A source outage must not cause a retry storm or affect playback.
            Result.success()
        }
    }

    companion object {
        const val UNIQUE_WORK = "people-new-arrivals"
        private const val CHANNEL_ID = "people_new"
        private const val NOTIFICATION_ID = 403

        fun schedule(context: Context) {
            // Tests and Firebase-less recovery builds may intentionally omit
            // WorkManager startup; background discovery is best-effort.
            runCatching {
                val request = PeriodicWorkRequestBuilder<PeopleNewArrivalWorker>(12, TimeUnit.HOURS)
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request
                )
            }
        }

        suspend fun notifyIfNeeded(app: App) {
            val decision = PeopleNewArrivalNotification.decide(
                bookmarks = app.personBookmarks.allBookmarks().first(),
                works = app.sourceCatalog.allWorks.first(),
                editions = app.sourceCatalog.allEditions.first()
            ) ?: return
            val context = app.applicationContext
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, "Новинки від людей", NotificationManager.IMPORTANCE_DEFAULT
                ))
            }
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PEOPLE_NEW, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val people = decision.people.take(2).joinToString(" та ").ifBlank { "ваших авторів" }
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(com.slukhayka.audiobooks.R.drawable.ic_launcher_foreground)
                    .setContentTitle("${decision.count} нові книги")
                    .setContentText("Від $people")
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .addAction(0, "Переглянути", pending)
                    .build()
            )
            app.personBookmarks.markNotified(decision.bookmarkKeys, decision.count)
        }
    }
}
