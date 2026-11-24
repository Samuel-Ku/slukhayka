package com.slukhayka.audiobooks.data.personbookmarks

import android.content.res.Resources
import com.slukhayka.audiobooks.R

/**
 * #1547 — the whole copy of the «Новинки від людей» notification, resolved
 * from resources for the CURRENT locale.
 *
 * The command used to be built with hardcodes in [PeopleNewArrivalWorker]:
 * `"${count} нові книги"` produced «1 нові книги» for a single book and
 * stayed Ukrainian on EN/PL phones (ADR-0033). The count is now a plural and
 * every other word — «Від …», the two-name conjunction, «Переглянути», the
 * channel name — comes from `values/` · `values-en/` · `values-pl/`.
 */
data class PeopleNewArrivalNotificationText(
    val title: String,
    val body: String,
    val action: String,
    val channelName: String
) {
    companion object {
        fun build(
            resources: Resources,
            count: Int,
            people: List<String>
        ): PeopleNewArrivalNotificationText {
            val names = people.filter { it.isNotBlank() }.take(2)
            val who = when (names.size) {
                0 -> resources.getString(R.string.people_new_notification_some_people)
                1 -> names.first()
                else -> resources.getString(
                    R.string.people_new_notification_two_people,
                    names[0],
                    names[1]
                )
            }
            return PeopleNewArrivalNotificationText(
                title = resources.getQuantityString(
                    R.plurals.people_new_notification_title,
                    count,
                    count
                ),
                body = resources.getString(R.string.people_new_notification_from, who),
                action = resources.getString(R.string.people_new_notification_action),
                channelName = resources.getString(
                    R.string.people_new_notification_channel_name
                )
            )
        }
    }
}
