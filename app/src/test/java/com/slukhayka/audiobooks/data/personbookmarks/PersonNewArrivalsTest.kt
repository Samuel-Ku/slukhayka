package com.slukhayka.audiobooks.data.personbookmarks

import com.slukhayka.audiobooks.data.db.LibraryEntryEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkKey
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
        val edition = EditionEntity("edition", "4read-card", narrator = "Ігор Петренко", addedAt = 11)
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
            libraryEntries = listOf(LibraryEntryEntity("4read-card", work.id)),
            unifiedCatalog = listOf(card, card.copy(sources = listOf(GlobalSearchSource("source-b", "B", "https://b.example/book"))))
        )

        assertEquals(listOf(card.key), projection.results.map { it.key })
        assertEquals(setOf(author.id, narrator.id), projection.bookmarkKeys.map { it.id }.toSet())
        assertEquals(1, projection.count)
    }

    @Test
    fun `mark seen keys are limited to people represented by shown cards`() {
        val shown = PersonIdentity.from(PersonRole.AUTHOR, "Леся Українка")
        val absent = PersonIdentity.from(PersonRole.AUTHOR, "Іван Франко")
        val shownWork = WorkEntity("shown", "shown-key", "Книга", shown.displayName, addedAt = 11)
        val absentWork = WorkEntity("absent", "absent-key", "Інша", absent.displayName, addedAt = 11)
        val card = GlobalSearchResult(
            title = "Книга", author = shown.displayName, mergeKey = "shown-key",
            sources = listOf(GlobalSearchSource("source", "S", "https://a.example/book"))
        )

        val projection = PersonNewArrivals.projectCatalog(
            bookmarks = listOf(
                PersonBookmarkEntity(shown.role.storageValue, shown.id, shown.displayName, shown.normalizedName, lastSeenAt = 10),
                PersonBookmarkEntity(absent.role.storageValue, absent.id, absent.displayName, absent.normalizedName, lastSeenAt = 10)
            ),
            works = listOf(shownWork, absentWork), editions = emptyList(), libraryEntries = emptyList(), unifiedCatalog = listOf(card)
        )

        assertEquals(setOf(shown.id), projection.bookmarkKeys.map { it.id }.toSet())
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

    @Test
    fun `notification watermark is per person rather than the grouped total`() {
        val first = PersonIdentity.from(PersonRole.AUTHOR, "Леся Українка")
        val second = PersonIdentity.from(PersonRole.AUTHOR, "Іван Франко")
        val bookmarks = listOf(
            PersonBookmarkEntity(first.role.storageValue, first.id, first.displayName, first.normalizedName, lastSeenAt = 10, lastNotifiedCount = 1),
            PersonBookmarkEntity(second.role.storageValue, second.id, second.displayName, second.normalizedName, lastSeenAt = 10, lastNotifiedCount = 0)
        )
        val works = listOf(
            WorkEntity("first", "first", "Книга", first.displayName, addedAt = 11),
            WorkEntity("second-1", "second-1", "Інша", second.displayName, addedAt = 11),
            WorkEntity("second-2", "second-2", "Ще", second.displayName, addedAt = 12)
        )

        val decision = PeopleNewArrivalNotification.decide(bookmarks, works, emptyList())!!

        assertEquals(listOf(second.displayName), decision.people)
        assertEquals(mapOf(PersonBookmarkKey(PersonRole.AUTHOR, second.id) to 2), decision.notifiedCounts)
    }

    @Test
    fun `notification includes only items newer than its previous watermark`() {
        val author = PersonIdentity.from(PersonRole.AUTHOR, "Леся Українка")
        val bookmark = PersonBookmarkEntity(
            author.role.storageValue, author.id, author.displayName, author.normalizedName,
            lastSeenAt = 10, lastNotifiedAt = 11, lastNotifiedCount = 1
        )
        val works = listOf(
            WorkEntity("already-notified", "old", "Стара", author.displayName, addedAt = 11),
            WorkEntity("new", "new", "Нова", author.displayName, addedAt = 12)
        )

        val decision = PeopleNewArrivalNotification.decide(listOf(bookmark), works, emptyList())!!

        assertEquals(1, decision.count)
        assertEquals(mapOf(PersonBookmarkKey(PersonRole.AUTHOR, author.id) to 2), decision.notifiedCounts)
    }

    @Test
    fun `notification counts author work and its narrator edition once`() {
        val author = PersonIdentity.from(PersonRole.AUTHOR, "Леся Українка")
        val narrator = PersonIdentity.from(PersonRole.NARRATOR, "Ігор Петренко")
        val work = WorkEntity("work", "key", "Книга", author.displayName, addedAt = 11)
        val edition = EditionEntity("edition", "4read-card", narrator = narrator.displayName, addedAt = 11)
        val bookmarks = listOf(
            PersonBookmarkEntity(author.role.storageValue, author.id, author.displayName, author.normalizedName, lastSeenAt = 10),
            PersonBookmarkEntity(narrator.role.storageValue, narrator.id, narrator.displayName, narrator.normalizedName, lastSeenAt = 10)
        )

        val decision = PeopleNewArrivalNotification.decide(bookmarks, listOf(work), listOf(edition), listOf(LibraryEntryEntity("4read-card", work.id)))!!

        assertEquals(1, decision.count)
        assertEquals(setOf(author.displayName, narrator.displayName), decision.people.toSet())
    }
}
