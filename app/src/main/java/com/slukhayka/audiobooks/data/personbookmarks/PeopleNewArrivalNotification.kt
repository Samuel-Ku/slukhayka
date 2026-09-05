package com.slukhayka.audiobooks.data.personbookmarks

import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkKey
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.db.WorkEntity

/** Pure #403 notification gate. It has no Android notification dependency. */
object PeopleNewArrivalNotification {
    private data class NotificationCandidate(
        val bookmark: PersonBookmarkEntity,
        val key: PersonBookmarkKey,
        val totalCount: Int
    )

    data class Decision(
        val count: Int,
        val people: List<String>,
        val notifiedCounts: Map<PersonBookmarkKey, Int>
    )

    fun decide(
        bookmarks: List<PersonBookmarkEntity>,
        works: List<WorkEntity>,
        editions: List<EditionEntity>
    ): Decision? {
        val detected = PersonNewArrivals.detect(bookmarks, works, editions)
        val workById = works.associateBy { it.id }
        val editionById = editions.associateBy { it.id }
        val bookmarksByKey = bookmarks.mapNotNull { bookmark ->
            PersonRole.fromStorage(bookmark.kind)?.let { PersonBookmarkKey(it, bookmark.id) to bookmark }
        }.toMap()
        val allNewByKey = linkedMapOf<PersonBookmarkKey, MutableSet<String>>()
        val unnotifiedByKey = linkedMapOf<PersonBookmarkKey, MutableSet<String>>()

        fun add(key: PersonBookmarkKey, itemId: String, addedAt: Long) {
            allNewByKey.getOrPut(key) { linkedSetOf() } += itemId
            val bookmark = bookmarksByKey[key] ?: return
            if (addedAt > bookmark.lastNotifiedAt) {
                unnotifiedByKey.getOrPut(key) { linkedSetOf() } += itemId
            }
        }

        detected.workIds.forEach { id ->
            workById[id]?.let { work ->
                val key = PersonBookmarkKey(PersonRole.AUTHOR, PersonIdentity.from(PersonRole.AUTHOR, work.author).id)
                add(key, "work:$id", work.addedAt)
            }
        }
        detected.editionIds.forEach { id ->
            editionById[id]?.let { edition ->
                val key = PersonBookmarkKey(PersonRole.NARRATOR, PersonIdentity.from(PersonRole.NARRATOR, edition.narrator).id)
                // A new narration and the author's new Work project onto one
                // catalogue card, so the grouped notification counts it once.
                val itemId = edition.workId.takeIf(workById::containsKey)?.let { "work:$it" } ?: "edition:$id"
                add(key, itemId, edition.addedAt)
            }
        }
        val eligible = bookmarks.mapNotNull { bookmark ->
            val role = PersonRole.fromStorage(bookmark.kind) ?: return@mapNotNull null
            val key = PersonBookmarkKey(role, bookmark.id)
            val totalCount = allNewByKey[key].orEmpty().size
            bookmark.takeIf {
                it.notifyEnabled && totalCount > 0 && totalCount != it.lastNotifiedCount &&
                    unnotifiedByKey[key].orEmpty().isNotEmpty()
            }?.let { NotificationCandidate(it, key, totalCount) }
        }
        if (eligible.isEmpty()) return null
        val itemIds = eligible.flatMapTo(linkedSetOf()) { candidate ->
            unnotifiedByKey.getValue(candidate.key)
        }
        return Decision(
            count = itemIds.size,
            people = eligible.map { it.bookmark.displayName }.distinct(),
            notifiedCounts = eligible.associate { it.key to it.totalCount }
        )
    }
}
