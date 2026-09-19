package com.slukhayka.audiobooks.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.data.authors.AuthorIdentity
import com.slukhayka.audiobooks.data.catalog.CatalogPerson
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.PersonRole
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.ui.library.PersonWorkRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #955 — the person page must count ownership through the route that matches
 * the person's ROLE.
 *
 * The device defect (OnePlus 8 Pro): the page «Айя Нея» listed three of her
 * Works and badged none, although `library_entries` held «амнезія|айя нея»
 * and «велика зима|айя нея». The page was opened through
 * `openPersonBooks(CatalogPerson)` with `authorId = null`, so it read her as a
 * NARRATOR — and the editions carry an EMPTY narrator — while `work_facets`
 * mapped both Works to the canonical author `author-4ba6bdc52ed5f262`.
 *
 * These fixtures mirror that state exactly: Works indexed under one canonical
 * author, two of them owned, and no narration naming the person. An author
 * page must badge the two owned Works and must NOT badge the third — the
 * boundary that separates «I have it» from «I could add it».
 *
 * The narrator fixtures pin the other direction: a person who genuinely
 * narrates keeps the narrator route, and a name that happens to match an
 * author never re-routes a narrator's page to the author's Works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersonPageOwnershipTest {

    private val app: App = ApplicationProvider.getApplicationContext()
    private val dao: AudiobookDao get() = app.audiobookDao

    @Test
    fun `an author page badges the owned works and leaves the mirror neighbour clean`() {
        val author = "Тестова Авторка 955-A"
        val canonical = AuthorIdentity.fromWorkName(author).id
        runBlocking {
            seedWork("w955a-amneziya", "Амнезія", author, canonical)
            seedWork("w955a-velyka", "Велика зима", author, canonical)
            seedWork("w955a-tini", "Ім'я тіні", author, canonical)
            own("w955a-amneziya")
            own("w955a-velyka")
            // No edition of either owned Work names the author as narrator —
            // the exact shape of the device database.
        }

        val viewModel = MainViewModel(app)
        viewModel.openPersonBooks(
            CatalogPerson(name = author, path = "", bookCount = 3, role = PersonRole.AUTHOR)
        )
        val rows = awaitRows(viewModel, expected = 3)

        assertEquals("the author page reads every known Work", 3, rows.size)
        assertTrue(
            "an owned Work is badged",
            rows.first { it.workId == "w955a-amneziya" }.ownedInLibrary
        )
        assertTrue(
            "an owned Work is badged",
            rows.first { it.workId == "w955a-velyka" }.ownedInLibrary
        )
        assertFalse(
            "a Work the listener does NOT have must never be badged",
            rows.first { it.workId == "w955a-tini" }.ownedInLibrary
        )
    }

    @Test
    fun `openPersonBooks carries the canonical author id for an author and none for a narrator`() {
        val viewModel = MainViewModel(app)

        val author = "Тестова Авторка 955-B"
        viewModel.openPersonBooks(
            CatalogPerson(name = author, path = "", bookCount = 0, role = PersonRole.AUTHOR)
        )
        assertEquals(
            "an AUTHOR page must read Works by the canonical author id",
            AuthorIdentity.fromWorkName(author).id,
            viewModel.selectedPerson.value?.authorId
        )

        val narrator = "Тестовий Наратор 955-B"
        viewModel.openPersonBooks(
            CatalogPerson(name = narrator, path = "", bookCount = 0, role = PersonRole.NARRATOR)
        )
        assertNull(
            "a NARRATOR has no canonical author id — the role decides the route",
            viewModel.selectedPerson.value?.authorId
        )
    }

    @Test
    fun `a narrator page keeps reading the editions that name them`() {
        val narrator = "Тестовий Наратор 955-C"
        val bookId = "book955c"
        runBlocking {
            dao.insertAudiobooks(
                listOf(
                    AudiobookEntity(
                        id = bookId,
                        title = "Кобзар 955",
                        author = "Хтось",
                        narrator = narrator,
                        description = "",
                        coverDrawableRes = 0,
                        genre = "",
                        sourceUrl = "test://955"
                    )
                )
            )
            dao.upsertLibraryEntry(
                id = bookId,
                workId = "work955c",
                isFavorite = false,
                createdAt = 1L,
                downloadProgress = 0f
            )
            dao.insertEdition(EditionEntity(id = "ed955c", workId = "work955c", narrator = narrator))
        }

        val viewModel = MainViewModel(app)
        viewModel.openPersonBooks(
            CatalogPerson(name = narrator, path = "", bookCount = 1, role = PersonRole.NARRATOR)
        )
        val rows = awaitRows(viewModel, expected = 1)

        assertEquals(
            "the narrator route still lists the narrated owned book",
            listOf("Кобзар 955"),
            rows.map { it.title }
        )
    }

    @Test
    fun `a narrator is never re-routed to the author route by a matching name`() {
        val narrator = "Тестовий Наратор 955-D"
        val canonical = AuthorIdentity.fromWorkName(narrator).id
        runBlocking {
            seedWork("w955d-imya", "Ім'я тіні", narrator, canonical)
            own("w955d-imya")
            // Nothing narrates this Work — the person is only an author of it.
        }

        val viewModel = MainViewModel(app)
        viewModel.openPersonBooks(
            CatalogPerson(name = narrator, path = "", bookCount = 1, role = PersonRole.NARRATOR)
        )
        val rows = awaitRows(viewModel, expected = 1, timeoutMs = 1_500)

        assertTrue(
            "a narrator page must not silently become the author page",
            rows.isEmpty()
        )
    }

    private suspend fun seedWork(id: String, title: String, author: String, canonicalAuthorId: String) {
        dao.upsertWork(
            WorkEntity(id = id, mergeKey = "$id|author", title = title, author = author, addedAt = 1L)
        )
        dao.mergeWorkFacet(id, canonicalAuthorId, updatedAt = 1L)
    }

    private suspend fun own(workId: String) {
        dao.upsertLibraryEntry(
            id = "entry-$workId",
            workId = workId,
            isFavorite = false,
            createdAt = 1L,
            downloadProgress = 0f
        )
    }

    /**
     * `personWorks` is a `stateIn` flow whose DAO reads resume on the main
     * looper; idle it until the page settles or the budget runs out. A timeout
     * returns whatever the page has, so a regression reports its real count.
     */
    private fun awaitRows(
        viewModel: MainViewModel,
        expected: Int,
        timeoutMs: Long = 5_000
    ): List<PersonWorkRow> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val rows = viewModel.personWorks.value
            if (rows.size >= expected) return rows
            Thread.sleep(20)
        }
        return viewModel.personWorks.value
    }
}
