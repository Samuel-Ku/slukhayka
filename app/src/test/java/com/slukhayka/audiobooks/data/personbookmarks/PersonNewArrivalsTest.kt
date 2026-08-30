package com.slukhayka.audiobooks.data.personbookmarks

import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.source.GlobalSearchResult
import com.slukhayka.audiobooks.data.source.GlobalSearchSource
import org.junit.Assert.assertEquals
import org.junit.Test

class PersonNewArrivalsTest {
    @Test fun `role scoped normalized identities detect new works and editions`() {
        val author = PersonIdentity.from(PersonRole.AUTHOR, "Ігор Петренко")
        val narrator = PersonIdentity.from(PersonRole.NARRATOR, "Ігор Петренко")
        val bookmarks = listOf(
            PersonBookmarkEntity(PersonRole.AUTHOR.storageValue, author.id, author.displayName, author.normalizedName, lastSeenAt = 10),
            PersonBookmarkEntity(PersonRole.NARRATOR.storageValue, narrator.id, narrator.displayName, narrator.normalizedName, lastSeenAt = 10)
        )
        val result = PersonNewArrivals.detect(bookmarks,
            listOf(WorkEntity("w", "", "Книга", "ігор петренко", addedAt = 11)),
            listOf(EditionEntity("e", "w", narrator = "Ігор Петренко", addedAt = 11)))
        assertEquals(setOf("w"), result.workIds)
        assertEquals(setOf("e"), result.editionIds)
    }

    @Test
    fun `projects detected work and edition onto one unified catalog card`() {
        val author = PersonIdentity.from(PersonRole.AUTHOR, "Ігор Петренко")
        val narrator = PersonIdentity.from(PersonRole.NARRATOR, "Ігор Петренко")
        val work = WorkEntity("work", "title|author", "Книга", "ігор петренко", addedAt = 11)
        val edition = EditionEntity("edition", "work", narrator = "Ігор Петренко", addedAt = 11)
        val card = GlobalSearchResult(
            title = "Книга",
            author = "Ігор Петренко",
            narrator = "Ігор Петренко",
            mergeKey = "title|author",
            sources = listOf(GlobalSearchSource("source-a", "A", "https://a.example/book"))
        )

        val projection = PersonNewArrivals.projectCatalog(
            bookmarks = listOf(
                PersonBookmarkEntity(author.role.storageValue, author.id, author.displayName, author.normalizedName, lastSeenAt = 10),
                PersonBookmarkEntity(narrator.role.storageValue, narrator.id, narrator.displayName, narrator.normalizedName, lastSeenAt = 10)
            ),
            works = listOf(work),
            editions = listOf(edition),
            unifiedCatalog = listOf(card, card.copy(sources = listOf(GlobalSearchSource("source-b", "B", "https://b.example/book"))))
        )

        assertEquals(listOf(card.key), projection.results.map { it.key })
        assertEquals(setOf(author.id, narrator.id), projection.bookmarkKeys.map { it.id }.toSet())
        assertEquals(1, projection.count)
    }

    @Test
    fun `notification gate respects per-person opt out and last notified count`() {
        val author = PersonIdentity.from(PersonRole.AUTHOR, "Леся Українка")
        val work = WorkEntity("work", "key", "Лісова пісня", "леся українка", addedAt = 11)
        val bookmark = PersonBookmarkEntity(
            kind = author.role.storageValue, id = author.id,
            displayName = author.displayName, normalizedName = author.normalizedName,
            lastSeenAt = 10, lastNotifiedCount = 0
        )

        assertEquals(1, PeopleNewArrivalNotification.decide(listOf(bookmark), listOf(work), emptyList())!!.count)
        assertEquals(null, PeopleNewArrivalNotification.decide(listOf(bookmark.copy(lastNotifiedCount = 1)), listOf(work), emptyList()))
        assertEquals(null, PeopleNewArrivalNotification.decide(listOf(bookmark.copy(notifyEnabled = false)), listOf(work), emptyList()))
    }
}
