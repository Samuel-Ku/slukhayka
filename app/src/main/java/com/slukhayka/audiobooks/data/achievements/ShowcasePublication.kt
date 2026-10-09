package com.slukhayka.audiobooks.data.achievements

/**
 * #705 (T7) — one award as it appears in a PUBLIC curator profile.
 *
 * The name is a SNAPSHOT taken at publish time, following the precedent set for
 * published collections (#692, `itemSnapshots`): the reader may be on another
 * app version with a different catalogue, and their profile must still read
 * correctly. Resolving names on the reading side would show blanks — or worse,
 * a different award's name — whenever the two versions disagree.
 */
data class ShowcaseAwardSnapshot(val id: String, val name: String)

/**
 * #705 (T7) — what actually leaves the device when a listener publishes their
 * showcase.
 *
 * The ticket's first criterion is that the showcase appears in a public profile
 * «лише за явним вибором людини», and its second that «публікуються лише
 * обрані нагороди; решта профілю не видно». Both are properties of THIS list,
 * so they are decided here, in one pure place, rather than at the Firestore
 * call site.
 */
object ShowcasePublication {

    /** The showcase holds at most three; the published one cannot hold more. */
    const val MAX_PUBLISHED = 3

    /**
     * The awards to publish, in the order the listener pinned them.
     *
     * [nameOf] answers null for an id the catalogue does not know. Such an id is
     * DROPPED rather than published with a placeholder: a profile must never
     * show an award it cannot name, and an id with no name is an id this build
     * cannot honestly describe.
     */
    fun of(pinnedIds: List<String>, nameOf: (String) -> String?): List<ShowcaseAwardSnapshot> =
        pinnedIds
            .mapNotNull { id -> nameOf(id)?.let { name -> ShowcaseAwardSnapshot(id, name) } }
            .take(MAX_PUBLISHED)
}
