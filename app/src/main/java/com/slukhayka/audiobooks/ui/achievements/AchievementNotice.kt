package com.slukhayka.audiobooks.ui.achievements

import android.content.Context
import com.slukhayka.audiobooks.R

/**
 * Names are resolved only for earned notices; hidden definitions have no
 * pre-announcement.
 *
 * The catalogue grew well past the first-steps awards, so every award now has a
 * name here. Two ladders stay DYNAMIC because their ids carry a number
 * (`hours_10`, `books_25`); everything else is a plain lookup, and an id nobody
 * defined still degrades to a generic line rather than throwing — notices are
 * shown from persisted rows, and a row can outlive its definition.
 */
private val named = mapOf(
    "first_book" to R.string.achievement_first_book,
    "first_playback" to R.string.achievement_first_playback,
    "first_review" to R.string.achievement_first_review,
    "first_not_interested" to R.string.achievement_first_not_interested,
    "first_search_import" to R.string.achievement_first_search_import,
    "first_offline_playback" to R.string.achievement_first_offline_playback,
    "first_download" to R.string.achievement_first_download,
    "first_completion" to R.string.achievement_first_completion,
    "short_form_10" to R.string.achievement_short_form_10,
    "epic_1" to R.string.achievement_epic_1,
    "long_liver_5" to R.string.achievement_long_liver_5,
    "speedster_10" to R.string.achievement_speedster_10,
    "slow_savour_5" to R.string.achievement_slow_savour_5,
    "bookmarks_50" to R.string.achievement_bookmarks_50,
    "notes_10" to R.string.achievement_notes_10,
    "sleep_timer_20" to R.string.achievement_sleep_timer_20,
    "relisten_1" to R.string.achievement_relisten_1,
    "relisten_5" to R.string.achievement_relisten_5,
    "deep_reserve_10" to R.string.achievement_deep_reserve_10,
    "four_doors" to R.string.achievement_four_doors,
    "browser_guest" to R.string.achievement_browser_guest,
    "bilingual_2" to R.string.achievement_bilingual_2,
    "polyglot_3" to R.string.achievement_polyglot_3,
    "genre_polyglot_8" to R.string.achievement_genre_polyglot_8,
    "omnivore_12" to R.string.achievement_omnivore_12,
    "mono_genre_25" to R.string.achievement_mono_genre_25,
    "deep_search" to R.string.achievement_deep_search,
    "night_watch" to R.string.achievement_night_watch,
    "owl_and_lark" to R.string.achievement_owl_and_lark,
    "holiday" to R.string.achievement_holiday,
    "vintage" to R.string.achievement_vintage,
    "comeback" to R.string.achievement_comeback,
    "never_too_late" to R.string.achievement_never_too_late
)

fun achievementNotice(context: Context, id: String): String {
    named[id]?.let { return context.getString(R.string.achievement_awarded, context.getString(it)) }

    val number = id.substringAfter('_', "").toIntOrNull()
    if (number != null) {
        if (id.startsWith("hours_")) {
            return context.getString(
                R.string.achievement_awarded,
                context.resources.getQuantityString(R.plurals.achievement_hours, number, number)
            )
        }
        if (id.startsWith("books_")) {
            return context.getString(
                R.string.achievement_awarded,
                context.resources.getQuantityString(R.plurals.achievement_books, number, number)
            )
        }
    }
    return context.getString(R.string.achievement_awarded_generic)
}
