package com.slukhayka.audiobooks.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec-32 T1 (#231) — the [BookProfile] Firestore document codec — pure JVM.
 * Table-driven: the shape round-trips, the write path is bounded (chapter
 * cap, field length caps, http(s)-only stream URLs), and a corrupt document
 * is a miss, never a crash.
 */
class BookProfileCodecTest {

    private fun chapter(streamUrl: String, title: String = "Розділ 1", durationSeconds: Long = 0L) =
        ProfileChapter(title = title, streamUrl = streamUrl, durationSeconds = durationSeconds)

    private val richProfile = BookProfile(
        description = "Опис книги українською",
        narrator = "Дмитро Кузьменко",
        seriesTitle = "Серія пригод",
        seriesIndex = 2,
        genres = listOf("Фантастика", "Пригоди"),
        rating = 4.5,
        coverImageUrl = "https://4read.org/covers/kobzar.jpg",
        chapters = listOf(
            chapter("https://cdn.example.com/01.mp3", "Розділ 1", 1_800L),
            chapter("https://cdn.example.com/02.mp3", "Розділ 2", 1_200L)
        ),
        totalDurationSeconds = 3_000L
    )

    private val provenance = ProfileProvenance("derived", 1_000_000L)

    @Test
    fun `a rich profile round-trips through the codec`() {
        assertEquals(richProfile, BookProfileCodec.fromMap(BookProfileCodec.toMap(richProfile, provenance)))
    }

    @Test
    fun `a document without a chapters list is a miss`() {
        assertNull(BookProfileCodec.fromMap(mapOf("description" to "без розділів")))
        assertNull(BookProfileCodec.fromMap(mapOf("chapters" to "not-a-list")))
    }

    @Test
    fun `a chapter with a non-http stream url is dropped on write and read`() {
        val withBad = richProfile.copy(
            chapters = listOf(
                chapter("https://cdn.example.com/01.mp3"),
                chapter("ftp://cdn.example.com/bad.mp3"),
                chapter("//protocol-relative/bad.mp3")
            )
        )

        val written = BookProfileCodec.fromMap(BookProfileCodec.toMap(withBad, provenance))
        // Only the https chapter survives the write path.
        assertEquals(listOf("https://cdn.example.com/01.mp3"), written?.chapters?.map { it.streamUrl })

        // The read path drops a bad-URL chapter too, keeping the good ones.
        val doc = BookProfileCodec.toMap(richProfile, provenance).toMutableMap()
        doc["chapters"] = listOf(
            mapOf("title" to "good", "streamUrl" to "https://cdn.example.com/01.mp3"),
            mapOf("title" to "bad", "streamUrl" to "http:not-a-url")
        )
        assertEquals(listOf("https://cdn.example.com/01.mp3"), BookProfileCodec.fromMap(doc)?.chapters?.map { it.streamUrl })
    }

    @Test
    fun `the chapter cap truncates the write and rejects an oversized read`() {
        val many = richProfile.copy(
            chapters = List(BookProfileLimits.MAX_CHAPTERS + 50) { chapter("https://cdn.example.com/$it.mp3") }
        )

        assertEquals(BookProfileLimits.MAX_CHAPTERS, BookProfileCodec.toMap(many, provenance)["chapters"]?.let { (it as List<*>).size })
        // A doc that claims more chapters than the cap is corrupt — a miss.
        val oversized = BookProfileCodec.toMap(richProfile, provenance).toMutableMap()
        oversized["chapters"] = List(BookProfileLimits.MAX_CHAPTERS + 1) { i ->
            mapOf("title" to "r$i", "streamUrl" to "https://cdn.example.com/$i.mp3")
        }
        assertNull(BookProfileCodec.fromMap(oversized))
    }

    @Test
    fun `over-long title and description are truncated on both paths`() {
        val longTitle = "Р".repeat(BookProfileLimits.MAX_TITLE_LEN + 100)
        val longDesc = "д".repeat(BookProfileLimits.MAX_DESCRIPTION_LEN + 100)
        val long = richProfile.copy(
            description = longDesc,
            chapters = listOf(chapter("https://cdn.example.com/01.mp3", longTitle))
        )

        val decoded = BookProfileCodec.fromMap(BookProfileCodec.toMap(long, provenance))
        assertEquals(BookProfileLimits.MAX_DESCRIPTION_LEN, decoded?.description?.length)
        assertEquals(BookProfileLimits.MAX_TITLE_LEN, decoded?.chapters?.single()?.title?.length)
    }

    @Test
    fun `a corrupt chapter entry is dropped, never a crash`() {
        val doc = BookProfileCodec.toMap(richProfile, provenance).toMutableMap()
        doc["chapters"] = listOf(
            "garbage",
            mapOf("streamUrl" to 42),
            mapOf("title" to "ok", "streamUrl" to "https://cdn.example.com/03.mp3")
        )

        assertEquals(listOf("https://cdn.example.com/03.mp3"), BookProfileCodec.fromMap(doc)?.chapters?.map { it.streamUrl })
    }

    @Test
    fun `the write carries provenance fields`() {
        val doc = BookProfileCodec.toMap(richProfile, ProfileProvenance("derived", 1_000_000L))

        assertEquals("derived", doc["source"])
        assertEquals(1_000_000L, doc["resolvedAt"])
    }

    @Test
    fun `an implausible total duration is treated as unknown`() {
        // The fabricated 4:00:00 legacy sentinel (ADR-0014) is never real —
        // the profile codec applies the same honesty gate as the duration
        // codec, so the field decodes to absent rather than a fake number.
        val doc = BookProfileCodec.toMap(richProfile, provenance).toMutableMap()
        doc["totalDurationSeconds"] = 14_400L

        assertEquals(null, BookProfileCodec.fromMap(doc)?.totalDurationSeconds)
    }
}
