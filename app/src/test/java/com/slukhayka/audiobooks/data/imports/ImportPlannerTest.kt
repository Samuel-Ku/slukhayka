package com.slukhayka.audiobooks.data.imports

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Pure JVM tests for [ImportPlanner] (wayfinder #29). Only external
 * behaviour: grouping, the natural-sort invariant, T0 merge suggestions,
 * and the pure plan mutations (merge / split / reorder / edit).
 */
class ImportPlannerTest {

    private fun entry(name: String, folder: String? = null) = LocalAudioEntry(
        fileName = name,
        parentFolder = folder,
        openStream = { ByteArrayInputStream(byteArrayOf(0)) }
    )

    @Test
    fun `repeated splits keep unique plan identities and every audio chapter`() {
        var plan = ImportPlanner.buildPlan(SourceRef.Folder("content://tree"), listOf(entry("01.mp3", "Книга"), entry("02.mp3", "Книга"), entry("03.mp3", "Книга"), entry("04.mp3", "Книга")))
        plan = ImportPlanner.splitBook(plan, "folder:Книга", 3)
        plan = ImportPlanner.splitBook(plan, "folder:Книга", 1)
        assertEquals(3, plan.books.size)
        assertEquals(3, plan.books.map { it.id }.distinct().size)
        assertEquals(listOf("01.mp3", "02.mp3", "03.mp3", "04.mp3"), plan.books.flatMap { it.chapters }.map { it.file.fileName })
    }

    @Test
    fun `metadata editor can clear a previously set series volume`() {
        val plan = ImportPlanner.buildPlan(SourceRef.Folder("content://tree"), listOf(entry("01.mp3", "Книга")))
        val withSeries = ImportPlanner.editBook(plan, "folder:Книга", seriesTitle = "Серія", seriesIndex = 2)
        val cleared = ImportPlanner.editBook(withSeries, "folder:Книга", seriesTitle = "", seriesIndex = null, clearSeriesIndex = true)
        assertEquals("", cleared.books.single().seriesTitle)
        assertEquals(null, cleared.books.single().seriesIndex)
        assertTrue(cleared.corrections.any { it.kind == "FIELD" && it.value == "seriesIndex=" })
    }

    @Test
    fun `changing identity or splitting invalidates an earlier merge consent`() {
        val plan = ImportPlanner.buildPlan(SourceRef.Folder("content://tree"), listOf(entry("01.mp3", "Кобзар"), entry("02.mp3", "Кобзар")),
            listOf(ImportPlanner.ExistingWork("existing", "Кобзар", "кобзар|локальна папка")))
        val accepted = ImportPlanner.acceptMerge(plan, "folder:Кобзар")
        assertEquals("existing", accepted.books.single().mergedIntoBookId)
        val renamed = ImportPlanner.editBook(accepted, "folder:Кобзар", title = "Інша книга")
        assertNull(renamed.books.single().mergedIntoBookId)
        assertNull(renamed.books.single().suggestion)
        assertTrue(ImportPlanner.splitBook(accepted, "folder:Кобзар", 1).books.all { it.mergedIntoBookId == null && it.suggestion == null })
    }

    @Test
    fun `changing root grouping preserves corrections to unrelated subfolder books`() {
        val entries = listOf(entry("02.mp3"), entry("01.mp3"), entry("01.mp3", "Інша"))
        var plan = ImportPlanner.buildPlan(SourceRef.Folder("content://tree", "Кобзар"), entries)
        plan = ImportPlanner.editBook(plan, "folder:Інша", title = "Інша назва", author = "Автор", narrator = "Диктор", seriesTitle = "Твори", seriesIndex = 3)
        plan = ImportPlanner.editBook(plan, "root:01.mp3", title = "Моя вступна частина")
        val untouched = plan.books.first { it.id == "folder:Інша" }
        val grouped = ImportPlanner.changeFolderGrouping(plan, LocalFolderGrouping.ONE_BOOK)
        assertEquals(2, grouped.books.size)
        assertEquals(LocalFolderGrouping.ONE_BOOK, (grouped.source as SourceRef.Folder).grouping)
        assertEquals("Кобзар", grouped.books.first().title)
        assertEquals(listOf("01.mp3", "02.mp3"), grouped.books.first().chapters.map { it.file.fileName })
        assertEquals(untouched, grouped.books.last())
        assertTrue(grouped.corrections.any { it.value == "title=Інша назва" })
        assertFalse(grouped.corrections.any { it.value == "title=Моя вступна частина" })
        val separated = ImportPlanner.changeFolderGrouping(grouped, LocalFolderGrouping.SEPARATE_BOOKS)
        assertEquals(listOf("01", "02", "Інша назва"), separated.books.map { it.title })
        assertEquals(untouched, separated.books.last())
        assertEquals(grouped, ImportPlanner.changeFolderGrouping(grouped, LocalFolderGrouping.ONE_BOOK))
    }

    @Test
    fun `joining planned books preserves target metadata and every chapter but renews merge consent`() {
        var reads = 0
        fun lazyFile(name: String, folder: String? = null) = LocalAudioEntry(name, folder) { reads++; ByteArrayInputStream(byteArrayOf(1)) }
        var plan = ImportPlanner.buildPlan(SourceRef.Folder("content://tree"),
            listOf(lazyFile("Збірка.mp3"), lazyFile("Інший файл.mp3"), lazyFile("02.mp3", "Інша"), lazyFile("01.mp3", "Інша")),
            listOf(ImportPlanner.ExistingWork("existing", "Збірка", "збірка|локальний файл")))
        plan = ImportPlanner.editBook(plan, "root:Збірка.mp3", seriesTitle = "Твори", seriesIndex = 2)
        plan = ImportPlanner.acceptMerge(plan, "root:Збірка.mp3")
        assertEquals("existing", plan.books.first { it.id == "root:Збірка.mp3" }.mergedIntoBookId)
        val untouched = plan.books.first { it.id == "root:Інший файл.mp3" }
        val merged = ImportPlanner.mergePlannedBooks(plan, "folder:Інша", "root:Збірка.mp3")
        assertEquals(2, merged.books.size)
        val book = merged.books.first { it.id == "root:Збірка.mp3" }
        assertEquals("Збірка", book.title)
        assertEquals("Локальний файл", book.author)
        assertEquals("Твори", book.seriesTitle)
        assertEquals(2, book.seriesIndex)
        assertEquals(listOf("Збірка.mp3", "01.mp3", "02.mp3"), book.chapters.map { it.file.fileName })
        assertNull(book.mergedIntoBookId)
        assertNull(book.suggestion)
        assertEquals(untouched, merged.books.first { it.id == untouched.id })
        assertEquals(0, reads)
        assertEquals(plan, ImportPlanner.mergePlannedBooks(plan, "folder:Інша", "missing"))
        assertEquals(plan, ImportPlanner.mergePlannedBooks(plan, "folder:Інша", "folder:Інша"))
    }

    @Test
    fun `root files become single-chapter books, folders become multi-chapter books`() {
        val plan = ImportPlanner.buildPlan(
            source = SourceRef.Folder("content://tree"),
            entries = listOf(
                entry("root.mp3"),
                entry("01.mp3", "Кобзар"),
                entry("02.mp3", "Кобзар"),
                entry("10.mp3", "Кобзар"),
                entry("03.mp3", "Кобзар")
            )
        )
        assertEquals(2, plan.books.size)
        val rootBook = plan.books.first { it.id.startsWith("root:") }
        assertEquals("root", rootBook.title)
        assertEquals(1, rootBook.chapters.size)
        val folderBook = plan.books.first { it.id.startsWith("folder:") }
        assertEquals("Кобзар", folderBook.title)
        assertEquals(4, folderBook.chapters.size)
    }

    @Test
    fun `folder chapters are naturally sorted - 1, 2, 3, 10 not 1, 10, 2, 3`() {
        val plan = ImportPlanner.buildPlan(
            source = SourceRef.Folder("content://tree"),
            entries = listOf(
                entry("01.mp3", "Книга"),
                entry("02.mp3", "Книга"),
                entry("10.mp3", "Книга"),
                entry("03.mp3", "Книга")
            )
        )
        val folderBook = plan.books.first { it.id.startsWith("folder:") }
        assertEquals(
            listOf("01", "02", "03", "10"),
            folderBook.chapters.map { it.file.fileName.substringBefore('.') }
        )
    }

    @Test
    fun `an exact merge-key match surfaces as a T0 suggestion, never a silent merge`() {
        val plan = ImportPlanner.buildPlan(
            source = SourceRef.Folder("content://tree"),
            entries = listOf(entry("01.mp3", "Кобзар")),
            existingWorks = listOf(
                ImportPlanner.ExistingWork(
                    id = "b1",
                    title = "Кобзар",
                    mergeKey = "кобзар|локальна папка"
                )
            )
        )
        val book = plan.books.first()
        assertNotNull("a T0 suggestion must be offered", book.suggestion)
        assertEquals("b1", book.suggestion!!.existingBookId)
        assertEquals(0, book.suggestion!!.tier)
        assertNull("the plan never pre-merges", book.mergedIntoBookId)
    }

    @Test
    fun `accepting a merge pins the existing work, rejecting remembers never-match`() {
        val base = ImportPlanner.buildPlan(
            source = SourceRef.Folder("content://tree"),
            entries = listOf(entry("01.mp3", "Кобзар")),
            existingWorks = listOf(
                ImportPlanner.ExistingWork(id = "b1", title = "Кобзар", mergeKey = "кобзар|локальна папка")
            )
        )
        val bookId = base.books.first().id

        val accepted = ImportPlanner.acceptMerge(base, bookId)
        assertEquals("b1", accepted.books.first().mergedIntoBookId)
        assertTrue(accepted.corrections.isEmpty())

        val rejected = ImportPlanner.rejectMerge(base, bookId)
        assertNull(rejected.books.first().suggestion)
        assertTrue(
            "rejecting a suggestion must remember a NEVER_MATCH correction",
            rejected.corrections.any { it.kind == "NEVER_MATCH" && it.value == "b1" }
        )
    }

    @Test
    fun `splitting a book forks its chapters and remembers the split`() {
        val plan = ImportPlanner.buildPlan(
            source = SourceRef.Folder("content://tree"),
            entries = (1..4).map { entry("%02d.mp3".format(it), "Книга") }
        )
        val bookId = plan.books.first().id
        val split = ImportPlanner.splitBook(plan, bookId, chapterIndex = 2)
        assertEquals(2, split.books.size)
        assertEquals(2, split.books[0].chapters.size)
        assertEquals(2, split.books[1].chapters.size)
        assertEquals("Книга (1)", split.books[0].title)
        assertEquals("Книга (2)", split.books[1].title)
        assertTrue(split.corrections.any { it.kind == "SPLIT" })
    }

    @Test
    fun `reordering chapters overrides the natural order for that book only`() {
        val plan = ImportPlanner.buildPlan(
            source = SourceRef.Folder("content://tree"),
            entries = listOf(entry("01.mp3", "Книга"), entry("02.mp3", "Книга"), entry("03.mp3", "Книга"))
        )
        val bookId = plan.books.first().id
        val reordered = ImportPlanner.reorderChapters(plan, bookId, listOf(2, 0, 1))
        assertEquals(
            listOf("03", "01", "02"),
            reordered.books.first().chapters.map { it.file.fileName.substringBefore('.') }
        )
        // An invalid order is a no-op.
        assertEquals(plan.books.first().chapters, ImportPlanner.reorderChapters(plan, bookId, listOf(0)).books.first().chapters)
    }

    @Test
    fun `editing a book records FIELD corrections`() {
        val plan = ImportPlanner.buildPlan(
            source = SourceRef.Folder("content://tree"),
            entries = listOf(entry("01.mp3", "Книга"))
        )
        val bookId = plan.books.first().id
        val edited = ImportPlanner.editBook(plan, bookId, title = "Кобзар", author = "Тарас Шевченко")
        val book = edited.books.first()
        assertEquals("Кобзар", book.title)
        assertEquals("Тарас Шевченко", book.author)
        assertTrue(edited.corrections.any { it.kind == "FIELD" && it.value == "title=Кобзар" })
        assertTrue(edited.corrections.any { it.kind == "FIELD" && it.value == "author=Тарас Шевченко" })
    }

    @Test
    fun `an empty scan yields an empty plan without failing`() {
        val plan = ImportPlanner.buildPlan(SourceRef.Folder("content://tree"), emptyList())
        assertTrue(plan.books.isEmpty())
    }
    @Test fun `one book groups only root chapters with the confirmed folder name`() {
        val plan = ImportPlanner.buildPlan(
            SourceRef.Folder("content://root", displayName = "Кобзар", grouping = LocalFolderGrouping.ONE_BOOK),
            listOf(entry("03.mp3"), entry("01.mp3"), entry("02.mp3"), entry("01.mp3", "Інша книга"))
        )
        assertEquals(2, plan.books.size)
        val root = plan.books.first()
        assertEquals("Кобзар", root.title)
        assertEquals(listOf("01.mp3", "02.mp3", "03.mp3"), root.chapters.map { it.file.fileName })
        assertEquals("Інша книга", plan.books.last().title)
        assertEquals(1, plan.books.last().chapters.size)
    }

}
