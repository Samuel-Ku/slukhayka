package com.slukhayka.audiobooks.data.entries

import android.content.Context
import android.content.SharedPreferences

/**
 * spec-52 US28 / #1174 — what the mark TOOK AWAY, remembered until the cancel
 * puts it back.
 *
 * The mark overwrites the pass's own state, and the frozen Room schema has
 * nowhere to keep the previous one. Without this note the cancel could only
 * re-derive it from today's evidence, and the review caught both consequences:
 * a pass that was PLANNED before the mark came back IN_PROGRESS, and a pass the
 * mark had created itself was left behind as a phantom readthrough on «Мій рік»
 * — while the AC says the cancel returns the book EXACTLY as it was.
 *
 * This is a note about the listener's own last action, never a second truth
 * about the book: the Room pass stays the only pass, and this only says whether
 * the mark brought it into being and what it carried before.
 */
interface AbandonUndo {

    /** What one book's AUDIO pass was BEFORE the mark. */
    data class BeforeMark(
        /** False: the mark itself brought the pass into being. */
        val existed: Boolean,
        /** The state the pass carried — null when it did not exist. */
        val state: ReadingState?
    )

    /** Records the note of one mark, replacing any earlier one for the book. */
    fun remember(bookId: String, before: BeforeMark)

    /** The note of the live mark, or null when nothing was noted (or it was consumed). */
    fun recall(bookId: String): BeforeMark?

    /** Drops the note: the cancel consumed it, or the mark is gone. */
    fun forget(bookId: String)
}

/**
 * The durable [AbandonUndo] — SharedPreferences, like every other note a
 * listener's own reversible action leaves behind (the dismissed works in
 * `ListenPrefsStore`). An abandon and its cancel may sit on different sides of
 * a process death, and the AC's «точно як було» has to survive that.
 */
class SharedPreferencesAbandonUndo(context: Context) : AbandonUndo {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun remember(bookId: String, before: AbandonUndo.BeforeMark) {
        val value = if (before.existed) "1|${before.state?.name.orEmpty()}" else "0|"
        prefs.edit().putString(key(bookId), value).apply()
    }

    override fun recall(bookId: String): AbandonUndo.BeforeMark? {
        val value = prefs.getString(key(bookId), null) ?: return null
        val parts = value.split(SEPARATOR, limit = 2)
        if (parts.size != 2) return null
        if (parts[0] != "1") return AbandonUndo.BeforeMark(existed = false, state = null)
        // An unreadable state is a note the app cannot trust: no note at all is
        // better than a guessed restore (ADR-0014).
        val state = ReadingState.entries.firstOrNull { it.name == parts[1] } ?: return null
        return AbandonUndo.BeforeMark(existed = true, state = state)
    }

    override fun forget(bookId: String) {
        prefs.edit().remove(key(bookId)).apply()
    }

    private fun key(bookId: String): String = "$KEY_PREFIX$bookId"

    private companion object {
        const val PREFS_NAME = "abandon_undo"
        const val KEY_PREFIX = "before:"
        const val SEPARATOR = "|"
    }
}
