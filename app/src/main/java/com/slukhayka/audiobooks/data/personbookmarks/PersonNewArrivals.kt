package com.slukhayka.audiobooks.data.personbookmarks

import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.db.WorkEntity

/** Pure #402 detector; identity stays role-scoped by [PersonIdentity]. */
object PersonNewArrivals {
    data class Result(val workIds: Set<String>, val editionIds: Set<String>) {
        val count: Int get() = workIds.size + editionIds.size
    }

    fun detect(
        bookmarks: List<PersonBookmarkEntity>,
        works: List<WorkEntity>,
        editions: List<EditionEntity>
    ): Result {
        val authors = bookmarks.filter { it.kind == PersonRole.AUTHOR.storageValue }
            .associateBy { it.id }
        val narrators = bookmarks.filter { it.kind == PersonRole.NARRATOR.storageValue }
            .associateBy { it.id }
        val workIds = works.filter { work ->
            authors[PersonIdentity.from(PersonRole.AUTHOR, work.author).id]
                ?.let { work.addedAt > it.lastSeenAt } == true
        }.mapTo(linkedSetOf()) { it.id }
        val editionIds = editions.filter { edition ->
            narrators[PersonIdentity.from(PersonRole.NARRATOR, edition.narrator).id]
                ?.let { edition.addedAt > it.lastSeenAt } == true
        }.mapTo(linkedSetOf()) { it.id }
        return Result(workIds, editionIds)
    }
}
