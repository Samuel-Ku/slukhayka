package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.metadata.SubmissionPublication
import com.slukhayka.audiobooks.data.privacy.PacingParams
import com.slukhayka.audiobooks.data.privacy.PacingPolicy
import com.slukhayka.audiobooks.testing.FakeSharedBookMetaStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * ADR-0035 п. 12 / #608 — the curator's seeder mode, pure JVM (the
 * FeedCursorTest / PacingPolicyTest precedent): a channel walk publishes
 * ONLY verdict-verified items through the same publication door, an
 * already-published URL is skipped, the walk is bounded, every fetch is
 * paced by [PacingPolicy] (the burst gate engaged under load), and a
 * cancelled batch leaves no partial trace in the shared base.
 */
class SubmissionSeederTest {

    private val store = FakeSharedBookMetaStore()
    private var now = 10_000L
    private val verification = SubmissionVerification { now }
    private val policy = SubmissionPolicy(store, verification) { now }
    private val publisher = SubmissionPublisher(store, policy) { now }

    private val channelUrl = "https://www.youtube.com/@stivenkingua/videos"
    private val channelId = "@stivenkingua"
    private val submitterId = "curator-device-1"

    // The real video ids from the discussion fixtures (6XIPkMFZf-0,
    // biwxkjI06KA) plus synthetic ones for the skip paths.
    private val v1 = "https://www.youtube.com/watch?v=6XIPkMFZf-0"
    private val v2 = "https://www.youtube.com/watch?v=biwxkjI06KA"
    private val v3 = "https://www.youtube.com/watch?v=DEADBEEF123"
    private val v4 = "https://www.youtube.com/watch?v=DEADBEEF456"
    private val v5 = "https://www.youtube.com/watch?v=DEADBEEF789"

    private val flatChannelJson = """
        {
          "_type": "playlist",
          "id": "UUstivenkingua",
          "title": "Стівен Кінг українською",
          "entries": [
            {"_type": "url", "ie_key": "Youtube", "id": "6XIPkMFZf-0", "url": "$v1", "title": "Стівен Кінг - Острів Дума"},
            {"_type": "url", "ie_key": "Youtube", "id": "biwxkjI06KA", "url": "$v2", "title": "Стівен Кінг - Дівчинка, яка любила Тома Ґордона"},
            {"_type": "url", "ie_key": "Youtube", "id": "DEADBEEF123", "url": "$v3", "title": "Стівен Кінг - Мертва зона"},
            {"_type": "url", "ie_key": "Youtube", "id": "DEADBEEF456", "url": "$v4", "title": "Стівен Кінг - Воно"},
            {"_type": "url", "ie_key": "Youtube", "id": "DEADBEEF789", "url": "$v5", "title": "Стівен Кінг - Крізь час"}
          ]
        }
    """.trimIndent()

    private fun itemJson(id: String, title: String) =
        """{"id": "$id", "title": "$title", "duration": 5400}"""

    // A policy that never refuses a burst slot: exactly one pause precedes
    // every fetch — deterministic without sleeping.
    private val wideOpenPacing = PacingPolicy(
        PacingParams(minPauseMillis = 100, maxPauseMillis = 100, burstLimit = 1_000),
        Random(42)
    )

    private class RecordingSeeder(
        val seeder: SubmissionSeeder,
        val pauses: MutableList<Long> = mutableListOf()
    )

    private fun seederWith(
        fetchItem: suspend (String) -> String? = { url ->
            when (url) {
                v1 -> itemJson("6XIPkMFZf-0", "Стівен Кінг - Острів Дума")
                v2 -> itemJson("biwxkjI06KA", "Стівен Кінг - Дівчинка, яка любила Тома Ґордона")
                v3 -> itemJson("DEADBEEF123", "Стівен Кінг - Мертва зона")
                v5 -> itemJson("DEADBEEF789", "Стівен Кінг - Крізь час")
                else -> null
            }
        },
        pacing: PacingPolicy = wideOpenPacing
    ): RecordingSeeder {
        val pauses = mutableListOf<Long>()
        val seeder = SubmissionSeeder(
            publisher = publisher,
            pacing = pacing,
            fetchFlatMetadata = { flatChannelJson },
            fetchItemMetadata = fetchItem,
            pauseMillis = { pauses += it }
        )
        return RecordingSeeder(seeder, pauses)
    }

    private suspend fun prePublish(url: String) {
        store.publishSubmission(
            SubmissionPublication(
                sourceUrl = url,
                accessMode = "youtube",
                title = "Крізь час",
                chapters = emptyList(),
                verifiedAt = now,
                submittedAt = now,
                submitterId = submitterId
            )
        )
    }

    @Test
    fun `the walk publishes only verdict-verified items and skips the rest honestly`() = runBlocking {
        val recording = seederWith()
        val seeder = recording.seeder
        // Verdicts: v1, v2, v5 verified; v3 not; v4's metadata is unfetchable.
        verification.record(seeder.itemSourceId(v1), actualPlaybackStarted = true)
        verification.record(seeder.itemSourceId(v2), actualPlaybackStarted = true)
        verification.record(seeder.itemSourceId(v5), actualPlaybackStarted = true)
        prePublish(v5) // the same URL already lives in the shared base

        val result = seeder.seedChannel(channelUrl, channelId, submitterId)

        assertEquals(5, result.walked)
        assertEquals(2, result.published)
        assertEquals(1, result.notVerified)
        assertEquals(1, result.metadataFailed)
        assertEquals(1, result.alreadyPublished)
        assertEquals(3, result.skipped)
        assertEquals(
            "the shared base holds the two verified publications plus the pre-existing one",
            listOf(v5, v1, v2),
            store.submissionPuts.map { it.sourceUrl }
        )
        assertEquals(
            "the verification gate refused v3 - nothing was invented",
            0L,
            store.getSubmissionCount(submitterId, SubmissionPolicy.dayKeyOf(now))
        )
    }

    @Test
    fun `every fetch is paced - one policy pause precedes each request`() = runBlocking {
        val recording = seederWith()
        verification.record(recording.seeder.itemSourceId(v1), actualPlaybackStarted = true)
        verification.record(recording.seeder.itemSourceId(v2), actualPlaybackStarted = true)
        verification.record(recording.seeder.itemSourceId(v5), actualPlaybackStarted = true)

        recording.seeder.seedChannel(channelUrl, channelId, submitterId, maxItems = 3)

        // One pause before the flat fetch + one per walked item (3 items):
        // the human rhythm of the policy is consulted every time (spec-38 T5).
        assertEquals(listOf(100L, 100L, 100L, 100L), recording.pauses)
    }

    @Test
    fun `the burst gate is engaged under load - extra policy pauses appear`() = runBlocking {
        // A tightened burst budget (3 hits per 1 s window, 100 ms pauses)
        // forces the gate to engage deterministically inside the 5-entry
        // fixture: the base rhythm is exactly 6 pauses (flat + one per item),
        // but the 4th request onward is refused until hits leave the window —
        // extra policy pauses appear. The exact refusal arithmetic (strict
        // window edges included) is PacingPolicyTest's concern; this test
        // proves the seeder consults the gate on EVERY request and pays the
        // extra pauses instead of hammering through.
        var fakeNow = 0L
        val pauses = mutableListOf<Long>()
        val seeder = SubmissionSeeder(
            publisher = publisher,
            pacing = PacingPolicy(
                PacingParams(
                    minPauseMillis = 100,
                    maxPauseMillis = 100,
                    burstLimit = 3,
                    burstWindowMillis = 1_000
                ),
                Random(42)
            ),
            fetchFlatMetadata = { flatChannelJson },
            fetchItemMetadata = { url -> if (url == v1) itemJson("6XIPkMFZf-0", "Стівен Кінг - Острів Дума") else null },
            pauseMillis = { pauses += it; fakeNow += it },
            nowMillis = { fakeNow }
        )
        verification.record(seeder.itemSourceId(v1), actualPlaybackStarted = true)

        seeder.seedChannel(channelUrl, channelId, submitterId, maxItems = 10)

        // The 4th request within the window is refused and re-paused — a
        // scraper-shaped burst never forms.
        assertTrue(
            "the burst gate forced refusals beyond the 6-pause base rhythm: ${pauses.size} pauses",
            pauses.size > 6
        )
    }

    @Test
    fun `maxItems bounds the walk`() = runBlocking {
        val recording = seederWith()
        verification.record(recording.seeder.itemSourceId(v1), actualPlaybackStarted = true)
        verification.record(recording.seeder.itemSourceId(v2), actualPlaybackStarted = true)

        val result = recording.seeder.seedChannel(channelUrl, channelId, submitterId, maxItems = 2)

        assertEquals(2, result.walked)
        assertEquals(2, result.published)
        assertEquals(2, store.submissionPuts.size)
        assertEquals("only the bounded prefix walked", listOf(v1, v2), store.submissionPuts.map { it.sourceUrl })
    }

    @Test
    fun `an unfetchable channel yields zeros and no item fetches`() = runBlocking {
        var itemFetches = 0
        val seeder = SubmissionSeeder(
            publisher = publisher,
            pacing = wideOpenPacing,
            fetchFlatMetadata = { null },
            fetchItemMetadata = { itemFetches++; null },
            pauseMillis = {}
        )

        val result = seeder.seedChannel(channelUrl, channelId, submitterId)

        assertEquals(SubmissionSeeder.SeedResult(0, 0, 0, 0, 0), result)
        assertEquals(0, itemFetches)
        assertTrue(store.submissionPuts.isEmpty())
    }

    @Test
    fun `cancelling mid-batch leaves no partial trace in the shared base`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val seeder = SubmissionSeeder(
            publisher = publisher,
            pacing = wideOpenPacing,
            fetchFlatMetadata = { flatChannelJson },
            fetchItemMetadata = { url ->
                if (url == v2) {
                    gate.await() // the second item blocks — the batch is mid-flight
                }
                if (url == v1) itemJson("6XIPkMFZf-0", "Стівен Кінг - Острів Дума") else null
            },
            pauseMillis = {}
        )
        verification.record(seeder.itemSourceId(v1), actualPlaybackStarted = true)
        verification.record(seeder.itemSourceId(v2), actualPlaybackStarted = true)

        val job = launch { seeder.seedChannel(channelUrl, channelId, submitterId) }
        // Let the first item land, then cancel while the second is in flight
        // (the DeepModulesRoomTest precedent: a stuck publish fails the test
        // instead of hanging the build forever).
        withTimeout(5_000) {
            while (store.submissionPuts.isEmpty()) delay(10)
        }
        job.cancel()
        gate.complete(Unit)
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(
            "the verified half that already published stays; the rest never landed",
            1,
            store.submissionPuts.size
        )
        assertEquals(v1, store.submissionPuts.single().sourceUrl)
    }

    @Test
    fun `item source ids are deterministic per watch url`() {
        val seeder = seederWith().seeder
        assertEquals(seeder.itemSourceId(v1), seeder.itemSourceId(v1))
        assertTrue(seeder.itemSourceId(v1).startsWith("seed-youtube-"))
        assertTrue(seeder.itemSourceId(v1) != seeder.itemSourceId(v2))
    }
}