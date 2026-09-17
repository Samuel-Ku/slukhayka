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
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.imports.KnownBookIdentity
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

        val worksById = worksById(app)
        val entryIdByWorkId = entryIdsByWorkId(app)
        // The union card is the app's canonical Work identity (the same one
        // `importPreferredSource` passes to the import); it is what makes the
        // 4read verified-profile read land on the right Edition, so an absent
        // card means "no identity" and a plain live fetch, never a guessed one.
        val cardByMergeKey = app.sourceCatalog.unifiedCatalog.value.associateBy { it.mergeKey }
        appearance.appearedByMergeKey.forEach { (mergeKey, sourceIds) ->
            val workId = state_workId(app, mergeKey) ?: return@forEach
            // ADR-0052 §7 — a notification whose tap leads nowhere is not
            // posted. A Work with no Library Entry resolves the appeared
            // Source URL from its persisted Work Sources; when nothing
            // usable resolves, this Work is silently skipped while the
            // others still post.
            val target = openTarget(app, workId, sourceIds, entryIdByWorkId[workId])
                ?: return@forEach
            val sources = sourceIds.joinToString(", ") { sourceDisplayName(it) }
            val title = worksById[workId]?.title ?: context.getString(
                com.slukhayka.audiobooks.R.string.source_watch_notification_title
            )
            val text = context.getString(
                com.slukhayka.audiobooks.R.string.source_watch_notification_text, sources
            )
            val pending = openWorkIntent(
                context = context,
                workId = workId,
                target = target,
                identity = cardByMergeKey[mergeKey]?.let {
                    KnownBookIdentity(it.title, it.author, it.narrator, it.coverImageUrl)
                }
            )
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

    /** Where one Work's tap leads: an existing Library Entry or an importable Source. */
    private data class OpenTarget(
        val entryId: String? = null,
        val sourceId: String? = null,
        val sourceUrl: String? = null
    )

    /**
     * ADR-0052 §7 — the ordinary-door target for a Work. A Library Entry wins;
     * otherwise the appeared Source must be one the Work already persists a
     * usable URL for. Null means "no target" and the caller posts nothing.
     */
    private suspend fun openTarget(
        app: App,
        workId: String,
        appearedSourceIds: Set<String>,
        entryId: String?
    ): OpenTarget? {
        if (entryId != null) return OpenTarget(entryId = entryId)
        val workSources = runCatching { app.audiobookDao.getWorkSourcesForWorkSync(workId) }
            .getOrDefault(emptyList())
        val match = workSources.firstOrNull {
            it.sourceId in appearedSourceIds && it.sourceUrl.isNotBlank()
        } ?: return null
        return OpenTarget(sourceId = match.sourceId, sourceUrl = match.sourceUrl)
    }

    private suspend fun worksById(app: App): Map<String, WorkEntity> =
        app.sourceCatalog.allWorks.first().associateBy { it.id }

    private suspend fun entryIdsByWorkId(app: App): Map<String, String> =
        app.sourceCatalog.allLibraryEntries.first().associate { it.workId to it.id }

    private fun openWorkIntent(
        context: Context,
        workId: String,
        target: OpenTarget,
        identity: KnownBookIdentity?
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        if (target.entryId != null) {
            // The ordinary book-detail door (the same extras the download
            // notification uses) — the tap imports/opens like any other.
            intent.putExtra("openBookDetail", true).putExtra("bookId", target.entryId)
        } else {
            // ADR-0052 §7 — no Library Entry yet: the tap imports the Source
            // that just appeared through the ordinary import door (the extras
            // MainActivity parses and hands to MainViewModel) and then opens
            // the imported book. The identity only enables the shared-profile
            // read-skip; an absent one still imports by live fetch.
            intent.putExtra(MainActivity.EXTRA_OPEN_SOURCE_WORK, true)
                .putExtra(MainActivity.EXTRA_SOURCE_ID, target.sourceId)
                .putExtra(MainActivity.EXTRA_SOURCE_URL, target.sourceUrl)
            if (identity != null) {
                intent.putExtra(MainActivity.EXTRA_SOURCE_WORK_TITLE, identity.title)
                    .putExtra(MainActivity.EXTRA_SOURCE_WORK_AUTHOR, identity.author)
                    .putExtra(MainActivity.EXTRA_SOURCE_WORK_NARRATOR, identity.narrator)
            }
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
