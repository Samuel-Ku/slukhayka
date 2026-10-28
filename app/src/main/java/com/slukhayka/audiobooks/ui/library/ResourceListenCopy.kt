package com.slukhayka.audiobooks.ui.library

import android.content.res.Resources
import com.slukhayka.audiobooks.R
import kotlin.math.round

/**
 * #1478 — the ONE resource-backed [ListenComposer.ListenCopy]. The pure
 * composer owns eligibility/order; every word it renders comes from here, so
 * a Polish phone shows Polish shelf titles instead of Ukrainian hardcodes.
 *
 * Non-composable on purpose: the composer runs in the ViewModel's flow, and
 * [Resources] resolves the current configuration (uk / en / pl).
 */
class ResourceListenCopy(private val resources: Resources) : ListenComposer.ListenCopy {

    override fun title(id: ListenComposer.BlockId): String = resources.getString(
        when (id) {
            ListenComposer.BlockId.HERO -> R.string.listen_shelf_hero
            ListenComposer.BlockId.ALMOST_DONE -> R.string.listen_shelf_almost_done
            ListenComposer.BlockId.RETURN -> R.string.listen_shelf_return
            ListenComposer.BlockId.NEXT_IN_SERIES -> R.string.listen_shelf_next_in_series
            ListenComposer.BlockId.TRAVEL -> R.string.listen_shelf_travel
            ListenComposer.BlockId.SHORT -> R.string.listen_shelf_short
            ListenComposer.BlockId.FAVORITE_AUTHORS -> R.string.listen_shelf_favorite_authors
            ListenComposer.BlockId.RECENTLY_ADDED -> R.string.listen_shelf_recently_added
            ListenComposer.BlockId.FINISHED -> R.string.listen_shelf_finished
        }
    )

    override fun almostDoneReason(remainingSeconds: Long): String = resources.getString(
        R.string.listen_reason_remaining,
        formatRemainingTime(remainingSeconds, units())
    )

    override fun returnReason(days: Int): String =
        resources.getQuantityString(R.plurals.listen_reason_dormant, days, days)

    override fun nextVolumeReason(seriesTitle: String): String =
        resources.getString(R.string.listen_reason_next_volume, seriesTitle)

    override fun offlineReadyReason(): String = resources.getString(R.string.listen_reason_offline_ready)

    override fun shortReason(totalDurationSeconds: Long): String {
        val hours = totalDurationSeconds / 3600.0
        val label = if (hours < 1.0) {
            resources.getString(R.string.listen_short_less_than_hour)
        } else {
            resources.getString(R.string.listen_short_hours, round(hours).toInt())
        }
        return resources.getString(R.string.listen_reason_short, label)
    }

    override fun favoriteAuthorReason(): String =
        resources.getString(R.string.listen_reason_favorite_author)

    override fun addedThisWeekReason(): String =
        resources.getString(R.string.listen_reason_added_this_week)

    override fun finishedReason(count: Int): String =
        resources.getString(R.string.listen_reason_finished, count)

    override fun nextSeriesPartCaption(book: LibraryBook): String {
        val index = book.book.seriesIndex
        return if (index != null && index > 0) {
            resources.getString(R.string.listen_series_part, index)
        } else {
            resources.getString(R.string.listen_series_next_part)
        }
    }

    /** The response-backed duration units shared with the rest of the app. */
    private fun units(): RemainingTimeUnits = object : RemainingTimeUnits {
        private val hoursMinutes = resources.getString(R.string.remaining_units_hm)
        private val hours = resources.getString(R.string.remaining_units_h)
        private val minutes = resources.getString(R.string.remaining_units_m)
        private val singleMinute = resources.getString(R.string.remaining_units_minute)

        override fun hoursMinutes(hours: Long, minutes: Long) =
            String.format(hoursMinutes, hours, minutes)

        override fun hours(hours: Long) = String.format(this.hours, hours)

        override fun minutes(minutes: Long) = String.format(this.minutes, minutes)

        override fun singleMinute() = singleMinute
    }
}
