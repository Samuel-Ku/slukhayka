package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.metadata.SubmissionAccessMode
import com.slukhayka.audiobooks.testing.FakeSharedBookMetaStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0035 / #605/#607 — the publication door, fixture-tested: a bare link
 * insert with NO real playback verdict publishes NOTHING; a verified
 * submission publishes the honest observed claims and consumes one daily
 * budget slot; the same normalized URL is refused honestly on a repeat; the
 * store keys by the URL so one link lives once.
 */
class SubmissionPublisherTest {

    private val store = FakeSharedBookMetaStore()
    private var now = 10_000L
    private val verification = SubmissionVerification { now }
    private val policy = SubmissionPolicy(store, verification) { now }
    private val publisher = SubmissionPublisher(store, policy) { now }

    private val kingPlaylistJson = """
        {
          "_type": "playlist",
          "id": "PLking1",
          "title": "Стівен Кінг - Острів Дума",
          "entries": [
            {"_type": "url", "ie_key": "Youtube", "id": "6XIPkMFZf-0", "url": "https://www.youtube.com/watch?v=6XIPkMFZf-0", "title": "Острів Дума. Розділ 1"},
            {"_type": "url", "ie_key": "Youtube", "id": "biwxkjI06KA", "url": "https://www.youtube.com/watch?v=biwxkjI06KA", "title": "Острів Дума. Розділ 2"}
          ]
        }
    """.trimIndent()

    private val playlistUrl = "https://www.youtube.com/playlist?list=PLking1"
    private val sourceId = "youtube-ed1-abc"

    @Test
    fun `bare link insert without a verdict publishes nothing`() = runBlocking {
        val result = publisher.publish(playlistUrl, kingPlaylistJson, "@stivenkingua", sourceId, "device-1")
        assertEquals(SubmissionPublisher.Result.NOT_VERIFIED, result)
        assertTrue("nothing was handed to the shared store", store.submissionPuts.isEmpty())
        assertEquals("no budget slot was consumed", 0L, store.getSubmissionCount("device-1", "0"))
    }

    @Test
    fun `verified submission publishes the honest observed claims and consumes one slot`() = runBlocking {
        verification.record(sourceId, actualPlaybackStarted = true)
        val result = publisher.publish(playlistUrl, kingPlaylistJson, "@stivenkingua", sourceId, "device-1")
        assertEquals(SubmissionPublisher.Result.PUBLISHED, result)

        val publication = store.submissionPuts.single()
        assertEquals(playlistUrl, publication.sourceUrl)
        assertEquals(SubmissionAccessMode.YOUTUBE, publication.accessMode)
        assertEquals("Стівен Кінг", publication.author)
        assertEquals("Острів Дума", publication.title)
        assertEquals(2, publication.chapters.size)
        assertTrue(
            "chapters carry canonical watch URLs, never signed URLs",
            publication.chapters.all { it.watchUrl.startsWith("https://www.youtube.com/watch?v=") }
        )
        assertEquals("the verdict moment rides the document", 10_000L, publication.verifiedAt)
        assertEquals("the write moment is the clock", 10_000L, publication.submittedAt)
        assertEquals("device-1", publication.submitterId)
        assertEquals("the publish consumed one daily slot", 1L, store.getSubmissionCount("device-1", "0"))
    }

    @Test
    fun `single-video metadata carries the observed duration`() = runBlocking {
        verification.record(sourceId, actualPlaybackStarted = true)
        val singleJson = """{"id": "6XIPkMFZf-0", "title": "Стівен Кінг - Острів Дума", "duration": 5400}"""
        assertEquals(
            SubmissionPublisher.Result.PUBLISHED,
            publisher.publish("https://youtu.be/6XIPkMFZf-0", singleJson, "@stivenkingua", sourceId, "device-1")
        )
        assertEquals(5_400L, store.submissionPuts.single().durationSeconds)
    }

    @Test
    fun `garbage metadata publishes nothing`() = runBlocking {
        verification.record(sourceId, actualPlaybackStarted = true)
        assertEquals(
            SubmissionPublisher.Result.METADATA_FAILED,
            publisher.publish(playlistUrl, "not json", "@stivenkingua", sourceId, "device-1")
        )
        assertTrue(store.submissionPuts.isEmpty())
    }

    @Test
    fun `the same url is refused honestly on a repeat`() = runBlocking {
        verification.record(sourceId, actualPlaybackStarted = true)
        assertEquals(SubmissionPublisher.Result.PUBLISHED, publisher.publish(playlistUrl, kingPlaylistJson, "@stivenkingua", sourceId, "device-1"))
        assertEquals(
            "the repeat is refused, not silently duplicated",
            SubmissionPublisher.Result.ALREADY_PUBLISHED,
            publisher.publish(playlistUrl, kingPlaylistJson, "@stivenkingua", sourceId, "device-2")
        )
        assertEquals("one document, one put", 1, store.submissionPuts.size)
        assertEquals(1, store.getSubmissionPage(null, 100).publications.size)
        assertEquals("the refused repeat consumed no slot", 1L, store.getSubmissionCount("device-1", "0"))
    }
}