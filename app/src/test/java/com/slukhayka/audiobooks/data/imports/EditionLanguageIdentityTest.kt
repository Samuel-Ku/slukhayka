package com.slukhayka.audiobooks.data.imports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-45 T1 (#489) — the content-language facet write-through.
 *
 * The Edition id formula carries the language (ADR-0010 + #405), so an en and
 * a uk rendition of the SAME Work must land as two Editions of one Work (never
 * merged, never duplicated). Books imported before the facet existed carried
 * ""-language ids; a re-import must still resolve to that card (legacy lookup),
 * not fork a duplicate.
 */
@RunWith(RobolectricTestRunner::class)
// sdk 36 matches the other Room tests; API 36 requires the JDK 21 toolchain
// (CONTRIBUTING.md — run via scripts/test-changed.sh or SLUKHAYKA_JAVA_HOME).
@Config(sdk = [36])
class EditionLanguageIdentityTest {

    private lateinit var context: Context
    private lateinit var db: AudiobookDatabase
    private lateinit var dao: AudiobookDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AudiobookDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.audiobookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A 4read-shaped adapter; [contentLanguage] is the source's declared language. */
    private fun adapter(contentLanguage: String): SourceAdapter = object : SourceAdapter {
        override val sourceId: String = "4read"
        override val contentLanguage: String = contentLanguage
        override suspend fun search(query: String): List<SourceBook> = emptyList()
        override suspend fun fetchBookPage(url: String): SourceBookDetail =
            throw IllegalStateException("not used in this test")
        override suspend fun fetchNew(limit: Int): List<SourceBook> = emptyList()
    }

    private fun detail(
        title: String = "Пані Боварі",
        author: String = "Гюстав Флобер",
        narrator: String = "Іван Франко",
        url: String,
        language: String = ""
    ) = SourceBookDetail(
        title = title,
        author = author,
        narrator = narrator,
        url = url,
        language = language,
        coverImageUrl = "https://4read.org/uploads/$title.jpg",
        chapters = listOf(SourceChapter("Розділ 1", "$url/ch1.mp3"))
    )

    private fun importsFor(contentLanguage: String) = LibraryImport(dao, context, listOf(adapter(contentLanguage)))

    @Test
    fun sameWorkDifferentLanguagesAreTwoEditionsOfOneWork() = runBlocking {
        val imports = importsFor(contentLanguage = "uk")

        // The uk rendition: the source declares "uk", the detail claims none.
        val ukBook = imports.importBookFromSource("4read", detail(url = "https://4read.org/bovari-uk"))
        // The en rendition of the SAME work: the detail's per-book claim wins
        // over the source's "uk". Same narrator on purpose — only the language
        // may split the Editions.
        val enBook = imports.importBookFromSource(
            "4read",
            detail(url = "https://4read.org/bovari-en", language = "en")
        )

        // One Work, two rendition cards.
        assertEquals(ukBook.workId, enBook.workId)
        assertNotEquals(ukBook.id, enBook.id)

        // Each rendition carried its language into the Edition row.
        val ukEdition = dao.getEditionForWork(ukBook.id)
        val enEdition = dao.getEditionForWork(enBook.id)
        assertNotNull(ukEdition)
        assertNotNull(enEdition)
        assertEquals("uk", ukEdition!!.language)
        assertEquals("en", enEdition!!.language)
        assertNotEquals(ukEdition.id, enEdition.id)
    }

    @Test
    fun reimportMergesIntoSameLanguageScopedCard() = runBlocking {
        val imports = importsFor(contentLanguage = "uk")
        val first = imports.importBookFromSource("4read", detail(url = "https://4read.org/bovari-uk"))
        val second = imports.importBookFromSource("4read", detail(url = "https://4read.org/bovari-uk"))

        assertEquals(first.id, second.id)
        assertEquals("uk", dao.getEditionForWork(first.id)!!.language)
        // One Edition, one Source — no fork.
        assertEquals(1, dao.getSourcesForBookSync(first.id).size)
    }

    @Test
    fun legacyBlankLanguageBookStillMergesAfterLanguageWriteThrough() = runBlocking {
        // A book imported BEFORE #489: its adapter declared no language, so its
        // Edition id and row carry "".
        val legacyImports = importsFor(contentLanguage = "")
        val legacy = legacyImports.importBookFromSource("4read", detail(url = "https://4read.org/bovari-uk"))
        assertNull(dao.getEditionForWork(legacy.id)!!.language.orEmpty().takeIf { it.isNotBlank() })

        // The same narration re-imported NOW (the adapter declares "uk"): the
        // language-scoped lookup misses, the legacy "" lookup must hit — the
        // card merges instead of forking a duplicate.
        val nowImports = importsFor(contentLanguage = "uk")
        val reimported = nowImports.importBookFromSource("4read", detail(url = "https://4read.org/bovari-uk"))

        assertEquals(legacy.id, reimported.id)
        assertEquals("", dao.getEditionForWork(reimported.id)!!.language)
        assertEquals(1, dao.getSourcesForBookSync(reimported.id).size)
    }
}