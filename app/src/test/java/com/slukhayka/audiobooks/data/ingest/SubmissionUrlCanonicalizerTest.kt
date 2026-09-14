package com.slukhayka.audiobooks.data.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec-53 T6 — the canonicalization table. Expected values come from the
 * spec (one canonical form per live shape), never recomputed by the code
 * under test.
 */
class SubmissionUrlCanonicalizerTest {

    private val videoId = "abc123XYZ89"

    @Test
    fun `every live youtube video shape collapses to one watch url`() {
        val expected = "https://www.youtube.com/watch?v=$videoId"
        val shapes = listOf(
            "https://youtu.be/$videoId",
            "https://youtu.be/$videoId?si=tracking",
            "https://www.youtube.com/watch?v=$videoId",
            "https://www.youtube.com/watch?v=$videoId&si=tracking&t=42s",
            "https://m.youtube.com/watch?v=$videoId&feature=share",
            "https://music.youtube.com/watch?v=$videoId",
            "https://www.youtube.com/shorts/$videoId",
            "https://www.youtube.com/embed/$videoId?autoplay=1",
            "https://www.youtube.com/live/$videoId",
            "https://www.youtube.com/v/$videoId"
        )

        for (shape in shapes) {
            assertEquals(shape, expected, SubmissionUrlCanonicalizer.canonical(shape))
        }
    }

    @Test
    fun `a watch url that also carries a list stays a video`() {
        assertEquals(
            "https://www.youtube.com/watch?v=$videoId",
            SubmissionUrlCanonicalizer.canonical(
                "https://www.youtube.com/watch?v=$videoId&list=PLtracking&index=3"
            )
        )
    }

    @Test
    fun `a playlist keeps only its list id`() {
        assertEquals(
            "https://www.youtube.com/playlist?list=PLabc123",
            SubmissionUrlCanonicalizer.canonical(
                "https://www.youtube.com/playlist?list=PLabc123&si=tracking&pp=xyz"
            )
        )
    }

    @Test
    fun `a channel keeps its handle and drops tracking`() {
        assertEquals(
            "https://www.youtube.com/@BookChannel",
            SubmissionUrlCanonicalizer.canonical("https://www.youtube.com/@BookChannel?si=tracking")
        )
        assertEquals(
            "https://www.youtube.com/channel/UCabc123",
            SubmissionUrlCanonicalizer.canonical("https://www.youtube.com/channel/UCabc123/videos")
        )
    }

    @Test
    fun `a telegram post drops its query and keeps its path`() {
        assertEquals(
            "https://t.me/bookchannel/42",
            SubmissionUrlCanonicalizer.canonical("https://t.me/bookchannel/42?embed=1")
        )
        assertEquals(
            "https://t.me/s/bookchannel",
            SubmissionUrlCanonicalizer.canonical("https://t.me/s/bookchannel")
        )
    }

    @Test
    fun `anything else is honestly unsupported`() {
        assertNull(SubmissionUrlCanonicalizer.canonical(""))
        assertNull(SubmissionUrlCanonicalizer.canonical("просто текст"))
        assertNull(SubmissionUrlCanonicalizer.canonical("https://example.com/watch?v=$videoId"))
        assertNull(SubmissionUrlCanonicalizer.canonical("https://www.youtube.com/"))
    }
}
