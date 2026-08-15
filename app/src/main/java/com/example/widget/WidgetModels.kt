package com.example.widget

/**
 * Spec-17 (#110): the home-screen widget's pure models.
 *
 * This file has zero Android dependencies on purpose — the session→widget
 * mapping and the refresh policy are the two seams the spec pins with fast
 * no-device JVM tests. The Android layer only converts media3 session values
 * into [SessionSnapshot]; everything downstream of that is pure.
 */

/** The MediaSession distilled to exactly what the widget renders. */
data class SessionSnapshot(
    val title: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** True when a book (chapter) is loaded into the session. */
    val hasBook: Boolean,
)

/** What the widget renders; produced from a [SessionSnapshot] by [WidgetModelMapper]. */
data class WidgetModel(
    val title: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val hasBook: Boolean,
    /** Fraction of the chapter played, 0f..1f; 0f when the duration is unknown. */
    val progress: Float,
)

/**
 * Maps session state + metadata to the widget model (spec-17 testing decision:
 * "session playback state + metadata → widget model (title, isPlaying, position,
 * duration, hasBook)").
 *
 * Neutral placeholder rules: without a book the widget must never show a
 * playing icon or a position — it renders the "nothing playing" placeholder.
 */
object WidgetModelMapper {

    fun map(snapshot: SessionSnapshot): WidgetModel {
        if (!snapshot.hasBook) {
            return WidgetModel(
                title = null,
                isPlaying = false,
                positionMs = 0L,
                durationMs = 0L,
                hasBook = false,
                progress = 0f,
            )
        }
        val position = snapshot.positionMs.coerceAtLeast(0L)
        val duration = snapshot.durationMs.coerceAtLeast(0L)
        return WidgetModel(
            title = snapshot.title,
            isPlaying = snapshot.isPlaying,
            positionMs = position,
            durationMs = duration,
            hasBook = true,
            progress = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
        )
    }
}