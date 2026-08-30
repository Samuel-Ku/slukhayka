package com.slukhayka.audiobooks.data.personbookmarks

import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkKey
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.db.WorkEntity

/** Pure #403 notification gate. It has no Android notification dependency. */
object PeopleNewArrivalNotification {
    data class Decision(
        val count: Int,
        val people: List<String>,
        val bookmarkKeys: Set<PersonBookmarkKey>
    )

    fun decide(
        bookmarks: List<PersonBookmarkEntity>,
        works: List<WorkEntity>,
        editions: List<EditionEntity>
    ): Decision? {
        val detected = PersonNewArrivals.detect(bookmarks, works, editions)
        val workById = works.associateBy { it.id }
        val editionById = editions.associateBy { it.id }
        val newByKey = linkedMapOf<PersonBookmarkKey, MutableSet<String>>()
        detected.workIds.forEach { id ->
            workById[id]?.let { work ->
                val key = PersonBookmarkKey(PersonRole.AUTHOR, PersonIdentity.from(PersonRole.AUTHOR, work.author).id)
                newByKey.getOrPut(key) { linkedSetOf() } += "work:$id"
            }
        }
        detected.editionIds.forEach { id ->
            editionById[id]?.let { edition ->
                val key = PersonBookmarkKey(PersonRole.NARRATOR, PersonIdentity.from(PersonRole.NARRATOR, edition.narrator).id)
                newByKey.getOrPut(key) { linkedSetOf() } += "edition:$id"
            }
        }
        val eligible = bookmarks.filter { bookmark ->
            val role = PersonRole.fromStorage(bookmark.kind) ?: return@filter false
            val key = PersonBookmarkKey(role, bookmark.id)
            bookmark.notifyEnabled && newByKey[key].orEmpty().size > 0 &&
                newByKey.getValue(key).size != bookmark.lastNotifiedCount
        }
        if (eligible.isEmpty()) return null
        val itemIds = eligible.flatMapTo(linkedSetOf()) { bookmark ->
            val role = PersonRole.fromStorage(bookmark.kind)!!
            newByKey.getValue(PersonBookmarkKey(role, bookmark.id))
        }
        return Decision(
            count = itemIds.size,
            people = eligible.map { it.displayName }.distinct(),
            bookmarkKeys = eligible.mapTo(linkedSetOf()) { bookmark ->
                PersonBookmarkKey(PersonRole.fromStorage(bookmark.kind)!!, bookmark.id)
            }
        )
    }
}
