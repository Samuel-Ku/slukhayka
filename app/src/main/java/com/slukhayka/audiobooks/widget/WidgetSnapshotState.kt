package com.slukhayka.audiobooks.widget

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState

/**
 * #1749 — the render state lives in Glance's per-instance DataStore instead of
 * a value captured by `provideGlance`.
 *
 * Glance 1.1.1 only extends a live session on `updateAll`; it does not call
 * `provideGlance` again. Content that closes over a local snapshot therefore
 * keeps rendering the frame from the start of the session while the transport
 * buttons still drive the player. The sampler publishes a new snapshot here,
 * and `provideContent` reads it through `currentState<Preferences>()`.
 *
 * Only the raw fields are stored; progress/formatting are recomputed with the
 * same [WidgetStateMapper.formatTime] the cold-start path uses, so the
 * state-to-render mapping stays one function.
 */
internal object WidgetSnapshotCodec {

    private const val VERSION = 1

    private val KEY_VERSION = intPreferencesKey("widget_snapshot_version")
    private val KEY_HAS_ACTIVE_BOOK = booleanPreferencesKey("widget_has_active_book")
    private val KEY_BOOK_ID = stringPreferencesKey("widget_book_id")
    private val KEY_TITLE = stringPreferencesKey("widget_title")
    private val KEY_AUTHOR = stringPreferencesKey("widget_author")
    private val KEY_CHAPTER_TITLE = stringPreferencesKey("widget_chapter_title")
    private val KEY_IS_PLAYING = booleanPreferencesKey("widget_is_playing")
    private val KEY_IS_BUFFERING = booleanPreferencesKey("widget_is_buffering")
    private val KEY_POSITION_SECONDS = longPreferencesKey("widget_position_seconds")
    private val KEY_DURATION_SECONDS = longPreferencesKey("widget_duration_seconds")

    fun write(into: MutablePreferences, state: GlanceWidgetState) {
        into[KEY_VERSION] = VERSION
        into[KEY_HAS_ACTIVE_BOOK] = state.hasActiveBook
        into[KEY_BOOK_ID] = state.bookId ?: ""
        into[KEY_TITLE] = state.title
        into[KEY_AUTHOR] = state.author
        into[KEY_CHAPTER_TITLE] = state.chapterTitle
        into[KEY_IS_PLAYING] = state.isPlaying
        into[KEY_IS_BUFFERING] = state.isBuffering
        into[KEY_POSITION_SECONDS] = state.currentPositionSeconds
        into[KEY_DURATION_SECONDS] = state.durationSeconds
    }

    /** Null means "no snapshot yet" — the caller renders its cold-start state. */
    fun read(from: Preferences): GlanceWidgetState? {
        if (from[KEY_VERSION] != VERSION) return null
        val position = from[KEY_POSITION_SECONDS] ?: 0L
        val duration = from[KEY_DURATION_SECONDS] ?: 0L
        val progress = if (duration > 0L) {
            (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val defaults = GlanceWidgetState()
        return GlanceWidgetState(
            hasActiveBook = from[KEY_HAS_ACTIVE_BOOK] ?: false,
            bookId = from[KEY_BOOK_ID]?.takeIf { it.isNotBlank() },
            title = from[KEY_TITLE] ?: defaults.title,
            author = from[KEY_AUTHOR] ?: defaults.author,
            chapterTitle = from[KEY_CHAPTER_TITLE] ?: defaults.chapterTitle,
            isPlaying = from[KEY_IS_PLAYING] ?: false,
            isBuffering = from[KEY_IS_BUFFERING] ?: false,
            currentPositionSeconds = position,
            durationSeconds = duration,
            progressFraction = progress,
            positionFormatted = WidgetStateMapper.formatTime(position),
            durationFormatted = WidgetStateMapper.formatTime(duration)
        )
    }
}

/**
 * Writes the snapshot for every placed widget and asks each one to repaint.
 *
 * The write and the repaint are separate steps by design: `updateAppWidgetState`
 * only stores preferences, and an active Glance session picks the new value up
 * only when an update arrives. The suspend function parameters are the seams
 * the JVM test uses to stand in for DataStore and the launcher.
 *
 * @return how many widget instances were refreshed (zero when none is placed).
 */
internal suspend fun publishWidgetSnapshot(
    state: GlanceWidgetState,
    ids: List<GlanceId>,
    write: suspend (GlanceId, GlanceWidgetState) -> Unit,
    update: suspend (GlanceId) -> Unit
): Int {
    for (id in ids) {
        write(id, state)
        update(id)
    }
    return ids.size
}

/** Production binding for [publishWidgetSnapshot]. */
internal suspend fun publishWidgetSnapshot(
    context: Context,
    state: GlanceWidgetState
): Int {
    val widget = AudiobookGlanceWidget()
    val ids = GlanceAppWidgetManager(context).getGlanceIds(AudiobookGlanceWidget::class.java)
    return publishWidgetSnapshot(
        state = state,
        ids = ids,
        write = { id, snapshot ->
            updateAppWidgetState(context, id) { preferences ->
                WidgetSnapshotCodec.write(preferences, snapshot)
            }
        },
        update = { id -> widget.update(context, id) }
    )
}
