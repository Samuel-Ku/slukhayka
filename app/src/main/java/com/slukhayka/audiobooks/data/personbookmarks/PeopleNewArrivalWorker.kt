package com.slukhayka.audiobooks.data.personbookmarks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.NotificationIntents
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
            // ADR-0037 §6 (spec-49 T4) — the Source Watch rides this already
            // scheduled union refresh: no polling loop of its own, zero extra
            // requests. Best-effort — a watch failure is silent.
            runCatching {
                com.slukhayka.audiobooks.data.watch.SourceWatchNotifier.evaluateAndNotify(
                    app,
                    app.sourceCatalog.unifiedCatalog.value
                )
            }
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
                editions = app.sourceCatalog.allEditions.first(),
                libraryEntries = app.sourceCatalog.allLibraryEntries.first()
            ) ?: return
            val context = app.applicationContext
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            // #1547 — every word (plural included) comes from resources for the
            // current locale; nothing user-facing lives in this file anymore.
            val copy = PeopleNewArrivalNotificationText.build(
                resources = context.resources,
                count = decision.count,
                people = decision.people
            )
            // Channels exist from API 26 only; API 24–25 posts the same
            // notification the old way, without one.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(NotificationManager::class.java)
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    manager.createNotificationChannel(NotificationChannel(
                        CHANNEL_ID, copy.channelName, NotificationManager.IMPORTANCE_DEFAULT
                    ))
                }
            }
            // ADR-0052 §6 — the alert carries the identity of the first
            // person it is about so its tap opens THAT page, not the feed.
            // `people` and `notifiedCounts` are both projections of the same
            // eligible list in the same order, so their first entries name
            // the same person (role from the key, name from `people`).
            val firstPersonName = decision.people.firstOrNull()?.takeIf { it.isNotBlank() }
            val firstPersonRole = decision.notifiedCounts.keys.firstOrNull()?.role
            val intent = NotificationIntents.peopleNew(context, firstPersonName, firstPersonRole)
            val pending = PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationManagerCompat.from(context).notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(com.slukhayka.audiobooks.R.drawable.ic_launcher_foreground)
                    .setContentTitle(copy.title)
                    .setContentText(copy.body)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .addAction(0, copy.action, pending)
                    .build()
            )
            app.personBookmarks.markNotified(decision.notifiedCounts)
        }
    }
}
