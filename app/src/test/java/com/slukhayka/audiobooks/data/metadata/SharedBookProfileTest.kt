package com.slukhayka.audiobooks.data.metadata

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec-32 T1 (#231) — the [SharedBookMetaStore] profile seam over a fake
 * in-memory store (prior art: the universe store fixture tests): get/put
 * round-trip and the miss → null contract for one Source×Edition key. The
 * store is keyed by `(sourceId, editionId)` because stream URLs are
 * per-source.
 */
class SharedBookProfileTest {

    private val profile = BookProfile(
        description = "Опис",
        narrator = "Дмитро",
        genres = listOf("Фантастика"),
        chapters = listOf(ProfileChapter("Розділ 1", "https://cdn.example.com/01.mp3", 1_800L))
    )

    private class FakeStore : SharedBookMetaStore {
        val profiles = mutableMapOf<String, BookProfile>()

        override suspend fun getDuration(editionId: String): Long? = null
        override suspend fun getDurations(editionIds: List<String>): Map<String, Long> = emptyMap()
        override suspend fun putDuration(editionId: String, durationSeconds: Long, provenance: DurationProvenance) = Unit

        override suspend fun getProfile(sourceId: String, editionId: String): BookProfile? =
            profiles["$sourceId|$editionId"]

        override suspend fun putProfile(
            sourceId: String, editionId: String, profile: BookProfile, provenance: ProfileProvenance
        ) {
            profiles["$sourceId|$editionId"] = profile
        }
    }

    @Test
    fun `a miss on an empty store is null`() = runBlocking {
        val store = FakeStore()

        assertNull(store.getProfile("4read", "edition-1"))
    }

    @Test
    fun `put and get round-trip through the seam per source`() = runBlocking {
        val store = FakeStore()

        store.putProfile("4read", "edition-1", profile, ProfileProvenance("derived", 1_000_000L))

        // The key is (sourceId, editionId): the same edition on another
        // source is a DIFFERENT document (per-source stream URLs).
        assertEquals(profile, store.getProfile("4read", "edition-1"))
        assertNull(store.getProfile("soundbooks", "edition-1"))
    }

    @Test
    fun `re-put replaces the stored profile idempotently`() = runBlocking {
        val store = FakeStore()

        store.putProfile("4read", "edition-1", profile, ProfileProvenance("derived", 1_000_000L))
        val updated = profile.copy(description = "Опис оновлено")
        store.putProfile("4read", "edition-1", updated, ProfileProvenance("derived", 2_000_000L))

        assertEquals(updated, store.getProfile("4read", "edition-1"))
        assertEquals(1, store.profiles.size)
    }
}
