package com.slukhayka.audiobooks.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ADR-0035 / #605 — the shared submission document codec, fixture-tested
 * (the CoverCodec precedent): a valid document round-trips; a missing,
 * mistyped, blank, non-http, negative or OVERSIZED field is a MISS, never a
 * crash. Decode is defensive on purpose — a corrupt/empty/redundant shared
 * document must never take the consumption lane down.
 */
class SubmissionPublicationCodecTest {

    private val full = SubmissionPublication(
        sourceUrl = "https://www.youtube.com/playlist?list=PLking1",
        accessMode = SubmissionAccessMode.YOUTUBE,
        title = "Острів Дума",
        author = "Стівен Кінг",
        narrator = "Сергій Філатов",
        description = "Трудар-мільйонер дивом вижив…",
        coverUrl = "https://img.youtube.com/vi/abc/0.jpg",
        durationSeconds = 7_200,
        chapters = listOf(
            SubmissionChapter("Розділ 1", "https://www.youtube.com/watch?v=6XIPkMFZf-0"),
            SubmissionChapter("Розділ 2", "https://www.youtube.com/watch?v=biwxkjI06KA")
        ),
        verifiedAt = 1_000,
        submittedAt = 2_000,
        submitterId = "device-42"
    )

    @Test
    fun `valid document round-trips`() {
        val decoded = SubmissionPublicationCodec.fromMap(SubmissionPublicationCodec.toMap(full))!!
        assertEquals(full, decoded)
    }

    @Test
    fun `metadata-only document round-trips without chapters`() {
        val metadataOnly = full.copy(chapters = emptyList())
        assertEquals(metadataOnly, SubmissionPublicationCodec.fromMap(SubmissionPublicationCodec.toMap(metadataOnly)))
    }

    @Test
    fun `empty document is a miss`() {
        assertNull(SubmissionPublicationCodec.fromMap(emptyMap()))
    }

    @Test
    fun `missing or non-http sourceUrl is a miss`() {
        assertNull(SubmissionPublicationCodec.fromMap(SubmissionPublicationCodec.toMap(full) - "sourceUrl"))
        assertNull(
            SubmissionPublicationCodec.fromMap(
                SubmissionPublicationCodec.toMap(full) + ("sourceUrl" to "youtu.be/6XIPkMFZf-0")
            )
        )
        assertNull(
            SubmissionPublicationCodec.fromMap(
                SubmissionPublicationCodec.toMap(full) + ("sourceUrl" to "ftp://files.example/1.mp3")
            )
        )
    }

    @Test
    fun `blank or missing title is a miss`() {
        assertNull(SubmissionPublicationCodec.fromMap(SubmissionPublicationCodec.toMap(full) - "title"))
        assertNull(
            SubmissionPublicationCodec.fromMap(
                SubmissionPublicationCodec.toMap(full) + ("title" to "   ")
            )
        )
    }

    @Test
    fun `mistyped title or submitterId is a miss`() {
        assertNull(
            SubmissionPublicationCodec.fromMap(
                SubmissionPublicationCodec.toMap(full) + ("title" to 42)
            )
        )
        assertNull(
            SubmissionPublicationCodec.fromMap(
                SubmissionPublicationCodec.toMap(full) - "submitterId"
            )
        )
    }

    @Test
    fun `negative timestamps are a miss`() {
        assertNull(
            SubmissionPublicationCodec.fromMap(
                SubmissionPublicationCodec.toMap(full) + ("verifiedAt" to -1L)
            )
        )
        assertNull(
            SubmissionPublicationCodec.fromMap(
                SubmissionPublicationCodec.toMap(full) + ("submittedAt" to -5L)
            )
        )
    }

    @Test
    fun `non-http coverUrl is a miss`() {
        assertNull(
            SubmissionPublicationCodec.fromMap(
                SubmissionPublicationCodec.toMap(full) + ("coverUrl" to "file:///tmp/cover.jpg")
            )
        )
    }

    @Test
    fun `implausible duration is dropped, not fatal`() {
        val decoded = SubmissionPublicationCodec.fromMap(
            SubmissionPublicationCodec.toMap(full) + ("durationSeconds" to -9L)
        )!!
        assertEquals(null, decoded.durationSeconds)
    }

    @Test
    fun `oversized chapter list is a miss`() {
        // A WILD document (encode truncates; decode must reject the oversize).
        val wildChapters = List(SubmissionPublicationLimits.MAX_CHAPTERS + 1) {
            mapOf("title" to "Розділ $it", "watchUrl" to "https://www.youtube.com/watch?v=abc$it")
        }
        assertNull(SubmissionPublicationCodec.fromMap(SubmissionPublicationCodec.toMap(full) + ("chapters" to wildChapters)))
    }

    @Test
    fun `corrupt chapter entry is dropped, the document survives`() {
        val map = SubmissionPublicationCodec.toMap(full) +
            ("chapters" to listOf(mapOf("title" to "Зламаний", "watchUrl" to "not-a-url")))
        val decoded = SubmissionPublicationCodec.fromMap(map)!!
        assertEquals(0, decoded.chapters.size)
    }

    @Test
    fun `non-http watchUrl chapters are filtered on encode`() {
        val withBad = full.copy(
            chapters = full.chapters + SubmissionChapter("bad", "youtu.be/short")
        )
        val decoded = SubmissionPublicationCodec.fromMap(SubmissionPublicationCodec.toMap(withBad))!!
        assertEquals(2, decoded.chapters.size)
    }

    @Test
    fun `document key is the normalized url hash — same url, same key`() {
        assertEquals(
            SubmissionPublicationCodec.documentId("  https://www.youtube.com/watch?v=6XIPkMFZf-0  "),
            SubmissionPublicationCodec.documentId("https://www.youtube.com/watch?v=6XIPkMFZf-0")
        )
    }

    @Test
    fun `bounded encode truncates overlong claims`() {
        val wild = full.copy(title = "x".repeat(10_000), submitterId = "y".repeat(500))
        val map = SubmissionPublicationCodec.toMap(wild)
        assertEquals(SubmissionPublicationLimits.MAX_TEXT_LEN, (map["title"] as String).length)
        assertEquals(SubmissionPublicationLimits.MAX_SUBMITTER_ID_LEN, (map["submitterId"] as String).length)
    }

    @Test
    fun `an overlong submitterId in a wild document is a miss`() {
        assertNull(
            SubmissionPublicationCodec.fromMap(
                SubmissionPublicationCodec.toMap(full) + ("submitterId" to "y".repeat(SubmissionPublicationLimits.MAX_SUBMITTER_ID_LEN + 1))
            )
        )
    }
}