package com.example.data.imports

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
}
