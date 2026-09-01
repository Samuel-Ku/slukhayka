package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.source.SourceAccessCandidate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedSourceProfilePublisherTest {

    @Test
    fun `publishes only after player verdict and cookie free probe`() = runBlocking {
        val store = RecordingStore()
        val probe = RecordingProbe(CleanProfileProbeVerdict.PLAYABLE)
        val outcome = VerifiedSourceProfilePublisher(store, probe, nowMillis = { 123L }).publish(
            VerifiedSourceProfile(
                sourceId = "4read",
                editionId = "edition-a",
                playerOpened = true,
                source = SourceAccessCandidate("4read", url = "https://4read.org/book"),
                profile = profile()
            )
        )

        assertEquals(ProfilePublication.PUBLISHED, outcome)
        assertEquals("edition-a", store.editionId)
        assertEquals(ProfileProvenance.SOURCE_VERIFIED, store.provenance?.source)
        assertEquals(123L, store.provenance?.resolvedAt)
        assertEquals(mapOf("Referer" to "https://4read.org/"), probe.headers)
    }

    @Test
    fun `cookie bound or stale candidate stays local without refreshing profile`() = runBlocking {
        val store = RecordingStore()
        val publisher = VerifiedSourceProfilePublisher(
            store,
            RecordingProbe(CleanProfileProbeVerdict.BLOCKED),
            nowMillis = { 999L }
        )

        val outcome = publisher.publish(verified(playerOpened = true))

        assertEquals(ProfilePublication.LOCAL_ONLY, outcome)
        assertEquals(0, store.writes)
    }

    @Test
    fun `firebase absence or failed write never changes successful local recovery`() = runBlocking {
        val absent = VerifiedSourceProfilePublisher(null, RecordingProbe(CleanProfileProbeVerdict.PLAYABLE))
        assertEquals(ProfilePublication.LOCAL_ONLY, absent.publish(verified(playerOpened = true)))

        val failed = VerifiedSourceProfilePublisher(FailingStore(), RecordingProbe(CleanProfileProbeVerdict.PLAYABLE))
        assertEquals(ProfilePublication.LOCAL_ONLY, failed.publish(verified(playerOpened = true)))
    }

    @Test
    fun `no player verdict never probes or publishes`() = runBlocking {
        val store = RecordingStore()
        val probe = RecordingProbe(CleanProfileProbeVerdict.PLAYABLE)

        assertEquals(
            ProfilePublication.LOCAL_ONLY,
            VerifiedSourceProfilePublisher(store, probe).publish(verified(playerOpened = false))
        )
        assertEquals(0, probe.calls)
        assertTrue(store.writes == 0)
    }

    @Test
    fun `reader rejects stale or blocked verified shortcut with browser outcome`() = runBlocking {
        val old = System.currentTimeMillis() - ProfileFreshness.FRESHNESS_MILLIS
        val store = EntryStore(SharedProfileEntry(profile(), old, ProfileProvenance.SOURCE_VERIFIED))

        assertEquals(
            VerifiedProfileReadOutcome.BrowserRequired,
            VerifiedSourceProfileReader(store, RecordingProbe(CleanProfileProbeVerdict.PLAYABLE)).read("4read", "edition-a")
        )

        val fresh = EntryStore(SharedProfileEntry(profile(), System.currentTimeMillis(), ProfileProvenance.SOURCE_VERIFIED))
        assertEquals(
            VerifiedProfileReadOutcome.BrowserRequired,
            VerifiedSourceProfileReader(fresh, RecordingProbe(CleanProfileProbeVerdict.BLOCKED)).read("4read", "edition-a")
        )
    }

    @Test
    fun `verified shortcut is stale at the exact twenty four hour boundary`() = runBlocking {
        val observedAt = 5_000L
        val entry = SharedProfileEntry(profile(), observedAt, ProfileProvenance.SOURCE_VERIFIED)
        val store = EntryStore(entry)
        val probe = RecordingProbe(CleanProfileProbeVerdict.PLAYABLE)

        assertTrue(
            VerifiedSourceProfileReader(
                store,
                probe,
                nowMillis = { observedAt + VerifiedSourceProfileFreshness.FRESHNESS_MILLIS - 1 }
            ).read("4read", "edition-a") is VerifiedProfileReadOutcome.Ready
        )
        assertEquals(
            VerifiedProfileReadOutcome.BrowserRequired,
            VerifiedSourceProfileReader(
                store,
                probe,
                nowMillis = { observedAt + VerifiedSourceProfileFreshness.FRESHNESS_MILLIS }
            ).read("4read", "edition-a")
        )
    }

    private fun verified(playerOpened: Boolean) = VerifiedSourceProfile(
        sourceId = "4read",
        editionId = "edition-a",
        playerOpened = playerOpened,
        source = SourceAccessCandidate("4read", url = "https://4read.org/book"),
        profile = profile()
    )

    private fun profile() = BookProfile(
        chapters = listOf(ProfileChapter("Розділ 1", "https://s1.reasd.org/book.mp3", 60L))
    )

    private class RecordingProbe(private val result: CleanProfileProbeVerdict) : CleanProfileProber {
        var calls = 0
        var headers: Map<String, String> = emptyMap()
        override suspend fun probe(url: String, headers: Map<String, String>): CleanProfileProbeVerdict {
            calls++
            this.headers = headers
            return result
        }
    }

    private open class RecordingStore : SharedBookMetaStore {
        var writes = 0
        var editionId: String? = null
        var provenance: ProfileProvenance? = null
        override suspend fun getDuration(editionId: String): Long? = null
        override suspend fun getDurations(editionIds: List<String>): Map<String, Long> = emptyMap()
        override suspend fun putDuration(editionId: String, durationSeconds: Long, provenance: DurationProvenance) = Unit
        override suspend fun getProfile(sourceId: String, editionId: String): BookProfile? = null
        override suspend fun getProfileEntry(sourceId: String, editionId: String): SharedProfileEntry? = null
        override suspend fun putProfile(sourceId: String, editionId: String, profile: BookProfile, provenance: ProfileProvenance) {
            writes++
            this.editionId = editionId
            this.provenance = provenance
        }
        override suspend fun getCover(mergeKey: String): String? = null
        override suspend fun getCovers(mergeKeys: List<String>): Map<String, String> = emptyMap()
        override suspend fun putCover(mergeKey: String, coverUrl: String, provenance: CoverProvenance) = Unit
    }

    private class FailingStore : RecordingStore() {
        override suspend fun putProfile(sourceId: String, editionId: String, profile: BookProfile, provenance: ProfileProvenance) {
            error("firebase unavailable")
        }
    }

    private class EntryStore(private val entry: SharedProfileEntry) : RecordingStore() {
        override suspend fun getProfileEntry(sourceId: String, editionId: String): SharedProfileEntry = entry
    }
}
