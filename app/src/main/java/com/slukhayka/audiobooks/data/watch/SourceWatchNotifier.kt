package com.slukhayka.audiobooks.data.watch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.sourceDisplayName
import kotlinx.coroutines.flow.first

/**
 * ADR-0037 §6 (spec-49 T4) — delivers one Source Watch appearance as a
 * local notification. The evaluation inputs are zero-request by contract:
 * the union catalog the app already holds and the mapping verdicts the
 * Replacement Mapping (T2a/T2b) just produced. The store's seen-state is
 * persisted only when the notification actually goes out, so a dropped
 * result can legitimately repeat.
 *
 * Silent auto-import never happens: the appearance announces, the tap
 * opens the book through the ordinary import doors.
 */
object SourceWatchNotifier {

    private const val CHANNEL_ID = "source_watch"
    private const val NOTIFICATION_ID = 404

    /**
     * The zero-request scan a caller fires on a refresh that already
     * happens: the state comes from [SourceWatchStore], the union from the
     * app, the verdicts from the Replacement Mapping (T2a/T2b).
     */
    suspend fun evaluateAndNotify(
        app: App,
        catalog: List<GlobalSearchResult>,
        mappingVerdicts: List<SourceWatchPolicy.MappingVerdict> = emptyList()
    ): SourceWatchPolicy.Appearance? {
        val store = app.sourceWatchStore
        val watched = store.watched.value
        if (watched.isEmpty()) return null
        return evaluateAndNotify(
            app = app,
            state = SourceWatchPolicy.SeenState(
                watched = watched,
                seen = watched.keys.associateWith(store::seenFor)
            ),
            catalog = catalog,
            mappingVerdicts = mappingVerdicts
        )
    }

    /**
     * Feeds ONE mapping verdict (T2a/T2b — the resolver already produced
     * it during a card tap) into the watch. A watched Work whose verdict
     * names a non-refused source gets its appearance; nothing new fires
     * for unwatched works, refused sources or already-notified pairs.
     */
    suspend fun notifyMappingVerdict(app: App, mergeKey: String, sourceId: String) {
        evaluateAndNotify(
            app,
            catalog = emptyList(),
            mappingVerdicts = listOf(SourceWatchPolicy.MappingVerdict(mergeKey, sourceId))
        )
    }

    /**
     * Evaluates one scan for [state]; on appearance sends the local
     * notification and persists [Appearance.next]. Returns the appearance
     * that fired, or null when nothing new appeared. Pure bookkeeping stays
     * testable without Android — this is the only seam that touches
     * notifications.
     */
    suspend fun evaluateAndNotify(
        app: App,
        state: SourceWatchPolicy.SeenState,
        catalog: List<com.slukhayka.audiobooks.data.source.GlobalSearchResult>,
        mappingVerdicts: List<SourceWatchPolicy.MappingVerdict> = emptyList()
    ): SourceWatchPolicy.Appearance? {
        if (state.watched.isEmpty()) return null
        val refused = app.sourceAudioRefusal.refusedSources.value
        val appearance = SourceWatchPolicy.evaluate(
            state = state,
            catalog = catalog,
            mappingVerdicts = mappingVerdicts,
            refusedSources = refused
        ) ?: return null

        notifyAppearance(app, appearance)
        appearance.appearedByMergeKey.forEach { (mergeKey, sourceIds) ->
            app.sourceWatchStore.markSeen(mergeKey, sourceIds)
        }
        return appearance
    }

    /**
     * The local notification for one appearance. Several Works may appear
     * in one scan; each Work carries its own notification so a tap opens
     * exactly the book that found its source.
     */
    suspend fun notifyAppearance(app: App, appearance: SourceWatchPolicy.Appearance) {
        val context = app.applicationContext
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Чекає на джерело", NotificationManager.IMPORTANCE_DEFAULT
            ))
        }

        val workTitles = workTitles(app, appearance.workIds)
        val entryIdByWorkId = entryIdsByWorkId(app)
        appearance.appearedByMergeKey.forEach { (mergeKey, sourceIds) ->
            val workId = state_workId(app, mergeKey) ?: return@forEach
            val sources = sourceIds.joinToString(", ") { sourceDisplayName(it) }
            val title = workTitles[workId] ?: context.getString(
                com.slukhayka.audiobooks.R.string.source_watch_notification_title
            )
            val text = context.getString(
                com.slukhayka.audiobooks.R.string.source_watch_notification_text, sources
            )
            val pending = openWorkIntent(context, workId, entryIdByWorkId[workId])
            NotificationManagerCompat.from(context).notify(
                notificationIdFor(mergeKey),
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(com.slukhayka.audiobooks.R.drawable.ic_launcher_foreground)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    private fun state_workId(app: App, mergeKey: String): String? =
        app.sourceWatchStore.watched.value[mergeKey]

    private suspend fun workTitles(
        app: App,
        workIds: List<String>
    ): Map<String, String> = buildMap {
        val works = app.sourceCatalog.allWorks.first()
        val byId = works.associateBy { it.id }
        workIds.forEach { id -> byId[id]?.let { put(id, it.title) } }
    }

    private suspend fun entryIdsByWorkId(app: App): Map<String, String> =
        app.sourceCatalog.allLibraryEntries.first().associate { it.workId to it.id }

    private fun openWorkIntent(context: Context, workId: String, entryId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        if (entryId != null) {
            // The ordinary book-detail door (the same extras the download
            // notification uses) — the tap imports/opens like any other.
            intent.putExtra("openBookDetail", true).putExtra("bookId", entryId)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            workId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Distinct notification ids per Work so appearances never overwrite each other. */
    fun notificationIdFor(mergeKey: String): Int = (NOTIFICATION_ID * 31 + mergeKey.hashCode()) and 0x7FFFFFFF
}
