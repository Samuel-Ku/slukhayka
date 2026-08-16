package com.slukhayka.audiobooks.data.imports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.SeriesRef
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec-26 T8 (#182) — the import event trigger on the explicit import door:
 * a NEW work with a series fires the [LibraryImport] callback (wired to the
 * universe chain validation in the composition root); a book without a
 * series, or a re-import of an existing rendition, does NOT fire it; and a
 * failing callback never breaks the import itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryImportTriggerTest {

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

    private fun detail(title: String, author: String, seriesName: String?): SourceBookDetail =
        SourceBookDetail(
            title = title,
            author = author,
            narrator = "",
            url = "https://4read.org/kniga-$title.html",
            chapters = listOf(SourceChapter("Розділ 1", "https://4read.org/audio/1.mp3")),
            series = seriesName?.let { SeriesRef(name = it, position = 1, url = null) }
        )

    @Test
    fun `a new book with a series fires the import trigger with its work id`() = runBlocking {
        val fired = mutableListOf<String>()
        val imports = LibraryImport(dao, null, emptyList(), onWorkImported = { fired += it })

        imports.importBookFromSource("4read", detail("Нова книга", "Автор", "Відомий цикл"))

        // The trigger fires once, with the work's merge key (its identity).
        assertEquals(listOf(MergeKey.keyFor("Нова книга", "Автор")), fired)
    }

    @Test
    fun `a book without a series does not fire the trigger`() = runBlocking {
        val fired = mutableListOf<String>()
        val imports = LibraryImport(dao, null, emptyList(), onWorkImported = { fired += it })

        imports.importBookFromSource("4read", detail("Книга без серії", "Автор", null))

        assertTrue(fired.isEmpty())
    }

    @Test
    fun `a re-import of an existing rendition does not fire the trigger`() = runBlocking {
        val fired = mutableListOf<String>()
        val imports = LibraryImport(dao, null, emptyList(), onWorkImported = { fired += it })

        imports.importBookFromSource("4read", detail("Нова книга", "Автор", "Відомий цикл"))
        fired.clear()
        imports.importBookFromSource("4read", detail("Нова книга", "Автор", "Відомий цикл"))

        // The second import merges into the existing card — no new work, no trigger.
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `a failing trigger callback never breaks the import`() = runBlocking {
        val imports = LibraryImport(dao, null, emptyList(), onWorkImported = { throw IllegalStateException("boom") })

        val book = imports.importBookFromSource("4read", detail("Нова книга", "Автор", "Відомий цикл"))

        assertEquals("Нова книга", book.title)
    }
}
