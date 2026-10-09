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
    "second_wind" to R.string.achievement_second_wind,
    "deep_reserve_10" to R.string.achievement_deep_reserve_10,
    "four_doors" to R.string.achievement_four_doors,
    "browser_guest" to R.string.achievement_browser_guest,
    "bilingual_2" to R.string.achievement_bilingual_2,
    "polyglot_3" to R.string.achievement_polyglot_3,
    "genre_polyglot_8" to R.string.achievement_genre_polyglot_8,
    "omnivore_12" to R.string.achievement_omnivore_12,
    "mono_genre_25" to R.string.achievement_mono_genre_25,
    "deep_search" to R.string.achievement_deep_search,
    "five_in_a_row" to R.string.achievement_five_in_a_row,
    "english_start" to R.string.achievement_english_start,
    "night_watch" to R.string.achievement_night_watch,
    "owl_and_lark" to R.string.achievement_owl_and_lark,
    "holiday" to R.string.achievement_holiday,
    "vintage" to R.string.achievement_vintage,
    "comeback" to R.string.achievement_comeback,
    "never_too_late" to R.string.achievement_never_too_late,
    // #702 (T4) — the NAMED genre shelves. A name is written only for a genre
    // the dictionary knows, so these ten ids match `canonicalIdentities`.
    "genre_detective_10" to R.string.achievement_genre_detective_10,
    "genre_fantasy_10" to R.string.achievement_genre_fantasy_10,
    "genre_science_fiction_10" to R.string.achievement_genre_science_fiction_10,
    "genre_romance_10" to R.string.achievement_genre_romance_10,
    "genre_horror_10" to R.string.achievement_genre_horror_10,
    "genre_childrens_literature_10" to R.string.achievement_genre_childrens_literature_10,
    "genre_historical_prose_10" to R.string.achievement_genre_historical_prose_10,
    "genre_adventure_10" to R.string.achievement_genre_adventure_10,
    "genre_self_development_10" to R.string.achievement_genre_self_development_10,
    "genre_biography_10" to R.string.achievement_genre_biography_10,
    "marathon_3h" to R.string.achievement_marathon_3h,
    "week_in_earphones" to R.string.achievement_week_in_earphones,
    "month_in_earphones" to R.string.achievement_month_in_earphones,
    "mondays_10" to R.string.achievement_mondays_10,
    // #1166 (T8, US13) — «Слухацький рік» is CUMULATIVE, so the name avoids the
    // calendar promise «Рік у навушниках» would make; #1175 — «Нова хвиля»
    // reads the registry's appearance date, not the library shelf.
    "listening_year_365" to R.string.achievement_listening_year_365,
    "new_wave" to R.string.achievement_new_wave,
    // #1183 (T9b) — the measurement layer's awards. Plain ids, no number in
    // them, so no dynamic rule is needed for any of the eight.
    "autonomous_10h" to R.string.achievement_autonomous_10h,
    "download_gourmet_100h" to R.string.achievement_download_gourmet_100h,
    "big_screen_10h" to R.string.achievement_big_screen_10h,
    "airplane_2h" to R.string.achievement_airplane_2h,
    "chapter_end_10" to R.string.achievement_chapter_end_10,
    "sync_4h" to R.string.achievement_sync_4h,
    "night_shift_2h" to R.string.achievement_night_shift_2h,
    "dawn_5" to R.string.achievement_dawn_5,
    // #1174 (друга смуга, US28) — «Не кидаю»: ten finished books and not one
    // «покинуто» mark standing. Plain id, no number rule needed.
    "never_abandon_10" to R.string.achievement_never_abandon_10
)

/**
 * The award's OWN name, or null when this build cannot name it.
 *
 * #705 (T7) — the showcase publishes NAMES, and an id this build cannot name has
 * to be dropped rather than published under the generic fallback. A public
 * profile must never claim an award it cannot describe, so the two callers need
 * different answers from the same catalogue: the notice may say «Нова нагорода»,
 * the publisher may not.
 */
fun achievementName(context: Context, id: String): String? {
    named[id]?.let { return context.getString(it) }

    val number = id.substringAfter('_', "").toIntOrNull() ?: return null
    return when {
        id.startsWith("hours_") ->
            context.resources.getQuantityString(R.plurals.achievement_hours, number, number)
        id.startsWith("books_") ->
            context.resources.getQuantityString(R.plurals.achievement_books, number, number)
        else -> null
    }
}

fun achievementNotice(context: Context, id: String): String {
    val name = achievementName(context, id)
    return if (name != null) {
        context.getString(R.string.achievement_awarded, name)
    } else {
        context.getString(R.string.achievement_awarded_generic)
    }
}
