package com.slukhayka.audiobooks.ui.achievements

import android.content.Context
import com.slukhayka.audiobooks.R

/** Names are resolved only for earned notices; hidden definitions have no pre-announcement. */
fun achievementNotice(context: Context, id: String): String {
    val title = when (id) {
        "first_book" -> context.getString(R.string.achievement_first_book)
        "first_playback" -> context.getString(R.string.achievement_first_playback)
        "first_review" -> context.getString(R.string.achievement_first_review)
        "first_not_interested" -> context.getString(R.string.achievement_first_not_interested)
        "first_search_import" -> context.getString(R.string.achievement_first_search_import)
        "first_offline_playback" -> context.getString(R.string.achievement_first_offline_playback)
        "first_download" -> context.getString(R.string.achievement_first_download)
        "first_completion" -> context.getString(R.string.achievement_first_completion)
        else -> {
            // #700/#701 — the catalogue grew past the first-steps ids and the
            // hour ladder, so an unknown id is NORMAL now, not a bug. The hour
            // ladder is matched by its shape; anything else falls back to a
            // generic title instead of throwing.
            //
            // This used to be `requireNotNull(...)`, which crashed the notice
            // for every award that was not `hours_*` — a real crash the moment
            // a book, speed, door, language or mechanism award was earned.
            val hours = id.removePrefix("hours_").toIntOrNull()
            if (id.startsWith("hours_") && hours != null) {
                context.getString(R.string.achievement_awarded,
                    context.resources.getQuantityString(R.plurals.achievement_hours, hours, hours))
            } else {
                // A generic award line, WITHOUT the «Нагорода: %s» wrapper — the
                // generic title already reads as a whole announcement.
                context.getString(R.string.achievement_awarded_generic)
            }
        }
    }
    return context.getString(R.string.achievement_awarded, title)
}
