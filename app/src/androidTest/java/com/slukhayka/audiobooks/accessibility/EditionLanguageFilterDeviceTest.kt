package com.slukhayka.audiobooks.accessibility

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.WorkFeedRow
import com.slukhayka.audiobooks.data.facets.*
import com.slukhayka.audiobooks.data.imports.LibraryImport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Real Android SQLite, isolated in memory: never opens the listener's database. */
class EditionLanguageFilterDeviceTest {
    @Test fun knownUnknownAndMixedEditionsComposeWithOtherFilters() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java).build()
        try {
            val dao = db.audiobookDao()
            val catalog = SourceCatalog(dao, emptyList(), LibraryImport(dao, context, emptyList()))
            for (title in listOf("English", "Українська", "Невідома", "Інший жанр")) {
                catalog.writeWorkEdition("4read", title, "Автор", "", "https://example.invalid/$title",
                    genreTexts = listOf(if (title == "Інший жанр") "Детектив" else "Фентезі"))
            }
            val works = dao.observeWorks().first().associateBy { it.title }
            suspend fun language(title: String, edition: String, code: String) {
                val workId = works.getValue(title).id
                catalog.facetWriter.apply(listOf(LocalFacetDelta(
                    work = WorkFacetDelta(workId),
                    editions = listOf(EditionFacetDelta(editionId = edition, workId = workId, language = code, updatedAt = 1L))
                )))
            }
            language("English", "en", "en")
            language("Українська", "uk", "uk")
            language("Невідома", "unknown", "")
            language("Інший жанр", "detective", "en")
            suspend fun titles(languages: Set<String>, genres: Set<String> = emptySet()): Set<String> {
                val page = catalog.pagedWorkFeedByTitle(WorkFacetFilter(languages = languages, genreIds = genres))
                    .load(PagingSource.LoadParams.Refresh(key = null, loadSize = 30, placeholdersEnabled = false))
                check(page is PagingSource.LoadResult.Page<Int, WorkFeedRow>) { "Expected a page: $page" }
                return page.data.map { it.title }.toSet()
            }
            assertEquals(setOf("English", "Невідома", "Інший жанр"), titles(setOf("en")))
            assertEquals(setOf("Українська", "Невідома"), titles(setOf("uk")))
            assertEquals(setOf("English", "Невідома"), titles(setOf("en"), setOf("fantasy")))
            assertEquals(4, titles(emptySet()).size)
            language("English", "also-uk", "uk")
            assertEquals(setOf("English", "Українська", "Невідома"), titles(setOf("uk")))
            assertEquals(setOf("English", "Невідома"), titles(setOf("en"), setOf("fantasy")))
        } finally {
            db.close()
        }
    }
}
