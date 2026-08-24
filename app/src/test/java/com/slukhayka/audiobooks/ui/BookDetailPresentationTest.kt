package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.entries.LibraryEntries
import com.slukhayka.audiobooks.ui.screens.bookDetailPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDetailPresentationTest {

    private val book = AudiobookEntity(
        id = "work-edition",
        title = "Трохи ненависті",
        author = "Джо Аберкромбі",
        narrator = "Pik CAH4E3",
        description = "Аудіокнига з каталогу 4read.org",
        coverDrawableRes = 0,
        genre = "Пригоди · Сучасна проза · Фентезі",
        sourceUrl = "https://4read.org/trohy-nenavysti",
        isDownloaded = false,
        rating = 4.6f
    )

    private val fourReadProfile = LibraryEntries.SourceProfile(
        sourceId = "4read",
        sourceName = "4read",
        url = book.sourceUrl,
        description = "Над Адуа зависочіли промислові труби, тож світ закипає, бо зароджується нова ера.",
        rating = 4.6,
        narrator = book.narrator,
        genres = listOf("Пригоди", "Сучасна проза", "Фентезі")
    )

    private val fourReadSource = SourceCatalog.WorkSourceRow(
        sourceId = "4read",
        sourceName = "4read",
        url = book.sourceUrl,
        streamOnly = false
    )

    @Test
    fun `single source presentation promotes the real blurb and exposes metadata once`() {
        val presentation = bookDetailPresentation(
            book = book,
            sourceProfiles = listOf(fourReadProfile),
            playableSources = listOf(fourReadSource)
        )

        assertEquals(book.title, presentation.title)
        assertEquals(book.author, presentation.author)
        assertEquals(fourReadProfile.description, presentation.description)
        assertEquals(book.narrator, presentation.narrator)
        assertEquals(book.genre, presentation.genre)
        assertEquals("Джерело", presentation.sourceHeading)
        assertEquals(1, presentation.sources.size)
        assertEquals("4read", presentation.sources.single().name)
        assertEquals(4.6, presentation.sources.single().rating!!, 0.001)
        assertFalse(presentation.sources.single().selectable)
        assertNull(presentation.sources.single().differingDescription)
        assertNull(presentation.sources.single().differingNarrator)
        assertTrue(presentation.sources.single().differingGenres.isEmpty())
    }

    @Test
    fun `multiple sources keep one selector and choose the longest real blurb`() {
        val secondSource = SourceCatalog.WorkSourceRow(
            sourceId = "sluhay",
            sourceName = "Sluhay",
            url = "https://sluhay.com/trohy-nenavysti",
            streamOnly = true
        )
        val secondProfile = LibraryEntries.SourceProfile(
            sourceId = "sluhay",
            sourceName = "Sluhay",
            url = secondSource.url,
            description = "Короткий опис.",
            rating = 4.8,
            narrator = book.narrator,
            genres = fourReadProfile.genres
        )

        val presentation = bookDetailPresentation(
            book = book,
            sourceProfiles = listOf(fourReadProfile, secondProfile),
            playableSources = listOf(fourReadSource, secondSource),
            listenerRatings = listOf(5)
        )

        assertEquals(fourReadProfile.description, presentation.description)
        assertEquals("Джерела", presentation.sourceHeading)
        assertEquals(listOf("4read", "Sluhay"), presentation.sources.map { it.name })
        assertEquals(listOf(true, false), presentation.sources.map { it.isCurrent })
        assertEquals(listOf(true, true), presentation.sources.map { it.selectable })
        assertEquals(3, presentation.combinedAverage?.count)
        assertEquals((4.6 + 4.8 + 5.0) / 3.0, presentation.combinedAverage!!.value, 0.001)
    }

    @Test
    fun `same provider on another url never borrows the current source rating`() {
        val siblingUrl = "https://4read.org/insha-nachytka"
        val presentation = bookDetailPresentation(
            book = book,
            sourceProfiles = listOf(fourReadProfile),
            playableSources = listOf(
                fourReadSource,
                fourReadSource.copy(url = siblingUrl)
            )
        )

        assertEquals(4.6, presentation.sources[0].rating!!, 0.001)
        assertNull(presentation.sources[1].rating)
    }

    @Test
    fun `different source assertions stay labelled while identical ones are suppressed`() {
        val secondSource = SourceCatalog.WorkSourceRow(
            sourceId = "sluhay",
            sourceName = "Sluhay",
            url = "https://sluhay.com/trohy-nenavysti",
            streamOnly = false
        )
        val secondProfile = LibraryEntries.SourceProfile(
            sourceId = "sluhay",
            sourceName = "Sluhay",
            url = secondSource.url,
            description = "Інша редакція опису цієї книги.",
            narrator = "Інший диктор",
            genres = listOf("Історичне фентезі")
        )

        val presentation = bookDetailPresentation(
            book = book,
            sourceProfiles = listOf(fourReadProfile, secondProfile),
            playableSources = listOf(fourReadSource, secondSource)
        )

        val identical = presentation.sources[0]
        assertNull(identical.differingDescription)
        assertNull(identical.differingNarrator)
        assertTrue(identical.differingGenres.isEmpty())
        val conflicting = presentation.sources[1]
        assertEquals(secondProfile.description, conflicting.differingDescription)
        assertEquals(secondProfile.narrator, conflicting.differingNarrator)
        assertEquals(secondProfile.genres, conflicting.differingGenres)
    }

    @Test
    fun `technical and blank descriptions produce no description`() {
        val presentation = bookDetailPresentation(
            book = book.copy(description = "Аудіокнига з джерела SomeSource."),
            sourceProfiles = listOf(fourReadProfile.copy(description = "  ")),
            playableSources = listOf(fourReadSource)
        )

        assertTrue(presentation.description.isBlank())
    }
}
