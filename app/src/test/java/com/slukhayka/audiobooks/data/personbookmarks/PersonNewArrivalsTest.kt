package com.slukhayka.audiobooks.data.personbookmarks

import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.PersonBookmarkEntity
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.db.WorkEntity
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
}
