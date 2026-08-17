package com.slukhayka.audiobooks.data.metadata

import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import com.slukhayka.audiobooks.data.source.SeriesRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec-32 T2 (#232) — the pure mapping from a resolved source page
 * ([SourceBookDetail]) to the shared [BookProfile]: every field rides over
 * unchanged, chapters 1:1. Table-driven, no I/O.
 */
class BookProfileMappingTest {

    @Test
    fun `a resolved detail maps to the full profile`() {
        val detail = SourceBookDetail(
            title = "Кобзар",
            author = "Тарас Шевченко",
            narrator = "Дмитро Кузьменко",
            url = "https://sound-books.net/kobzar.html",
            coverImageUrl = "https://sound-books.net/uploads/kobzar.jpg",
            chapters = listOf(
                SourceChapter("Розділ 1", "https://arch.sound-books.net/kobzar/1.mp3", 1_800L),
                SourceChapter("Розділ 2", "https://arch.sound-books.net/kobzar/2.mp3", 1_200L)
            ),
            totalDurationSeconds = 3_000L,
            rating = 4.5,
            genres = listOf("Поезія"),
            series = SeriesRef(name = "Кобзаріана", position = 1, url = "https://sound-books.net/series/kobzariana"),
            description = "Збірка поезій."
        )

        val profile = BookProfileMapping.fromDetail(detail)

        assertEquals("Кобзар", profile.title)
        assertEquals("Тарас Шевченко", profile.author)
        assertEquals("Дмитро Кузьменко", profile.narrator)
        assertEquals("Збірка поезій.", profile.description)
        assertEquals("Кобзаріана", profile.seriesTitle)
        assertEquals(1, profile.seriesIndex)
        assertEquals(listOf("Поезія"), profile.genres)
        assertEquals(4.5, profile.rating)
        assertEquals("https://sound-books.net/uploads/kobzar.jpg", profile.coverImageUrl)
        assertEquals(3_000L, profile.totalDurationSeconds)
        assertEquals(2, profile.chapters.size)
        assertEquals("Розділ 1", profile.chapters[0].title)
        assertEquals("https://arch.sound-books.net/kobzar/1.mp3", profile.chapters[0].streamUrl)
        assertEquals(1_800L, profile.chapters[0].durationSeconds)
    }

    @Test
    fun `absent optional fields map to their defaults`() {
        val detail = SourceBookDetail(
            title = "Без опису",
            author = "Автор",
            url = "https://sound-books.net/x.html",
            chapters = emptyList()
        )

        val profile = BookProfileMapping.fromDetail(detail)

        assertEquals("", profile.description)
        assertEquals(null, profile.seriesTitle)
        assertEquals(null, profile.rating)
        assertEquals(emptyList<String>(), profile.genres)
        assertEquals(emptyList<ProfileChapter>(), profile.chapters)
        assertEquals(null, profile.totalDurationSeconds)
    }
}
