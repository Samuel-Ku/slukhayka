package com.slukhayka.audiobooks.data.personbookmarks

import com.slukhayka.audiobooks.data.db.LibraryEntryEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkKey
import com.slukhayka.audiobooks.data.source.GlobalSearchResult

/** Pure #402 detector; identity stays role-scoped by [PersonIdentity]. */
object PersonNewArrivals {
    data class Result(val workIds: Set<String>, val editionIds: Set<String>) {
        val count: Int get() = workIds.size + editionIds.size
    }

    /**
     * The display projection for the ephemeral [GlobalSearchResult] catalogue.
     *
     * The catalogue itself deliberately stores no discovery timestamps.  We
     * therefore use the persisted Work/Edition rows as the time authority,
     * then project their identities back onto catalogue cards by Work merge
     * key.  That keeps a Work available from two sources on one card and never
     * manufactures a "new" timestamp from a network refresh.
     */
    data class CatalogProjection(
        val results: List<GlobalSearchResult>,
        val bookmarkKeys: Set<PersonBookmarkKey>
    ) {
        val count: Int get() = results.size
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
            work.author.isNotBlank() && authors[PersonIdentity.from(PersonRole.AUTHOR, work.author).id]
                ?.let { work.addedAt > it.lastSeenAt } == true
        }.mapTo(linkedSetOf()) { it.id }
        val editionIds = editions.filter { edition ->
            edition.narrator.isNotBlank() && narrators[PersonIdentity.from(PersonRole.NARRATOR, edition.narrator).id]
                ?.let { edition.addedAt > it.lastSeenAt } == true
        }.mapTo(linkedSetOf()) { it.id }
        return Result(workIds, editionIds)
    }

    fun projectCatalog(
        bookmarks: List<PersonBookmarkEntity>,
        works: List<WorkEntity>,
        editions: List<EditionEntity>,
        unifiedCatalog: List<GlobalSearchResult>,
        libraryEntries: List<LibraryEntryEntity>
    ): CatalogProjection {
        val detected = detect(bookmarks, works, editions)
        val worksById = works.associateBy { it.id }
        val workIdByBookId = libraryEntries.associate { it.id to it.workId }
        val keysByWorkMergeKey = linkedMapOf<String, MutableSet<PersonBookmarkKey>>()

        fun add(key: String, bookmarkKey: PersonBookmarkKey) {
            keysByWorkMergeKey.getOrPut(key) { linkedSetOf() } += bookmarkKey
        }

        detected.workIds.forEach { workId ->
            worksById[workId]?.let { work ->
                add(work.mergeKey.ifBlank { work.id }, PersonBookmarkKey(
                    PersonRole.AUTHOR,
                    PersonIdentity.from(PersonRole.AUTHOR, work.author).id
                ))
            }
        }
        detected.editionIds.forEach { editionId ->
            editions.firstOrNull { it.id == editionId }?.let { edition ->
                worksById[workIdByBookId[edition.workId]]?.let { work ->
                    add(work.mergeKey.ifBlank { work.id }, PersonBookmarkKey(
                        PersonRole.NARRATOR,
                        PersonIdentity.from(PersonRole.NARRATOR, edition.narrator).id
                    ))
                }
            }
        }
        val results = unifiedCatalog.filter { it.key in keysByWorkMergeKey }.distinctBy { it.key }
        return CatalogProjection(
            results = results,
            // The header/badge action means “seen what the shelf showed”, not
            // every detected row that was absent from this catalog projection.
            bookmarkKeys = results.flatMapTo(linkedSetOf()) { keysByWorkMergeKey.getValue(it.key) }
        )
    }
}
