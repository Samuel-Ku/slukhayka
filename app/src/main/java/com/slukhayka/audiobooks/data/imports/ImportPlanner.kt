package com.slukhayka.audiobooks.data.imports

import com.slukhayka.audiobooks.data.merge.MergeKey

/**
 * Pure JVM planner of the smart import (wayfinder #29). Turns scanned
 * [LocalAudioEntry]s into an [ImportPlan] without touching disk or Room:
 *
 * - **Grouping** is the same rule the importer already uses — loose files at
 *   the tree root become one single-chapter book each; every sub-folder
 *   becomes one multi-chapter book whose chapters are its files naturally
 *   sorted (1, 2, 3, 10). A confirmed-but-unedited plan therefore applies
 *   exactly like today's direct import.
 * - **Merge suggestions** (#54) surface as review rows, never silent merges:
 *   a planned book whose normalized key matches an existing Work exactly
 *   (T0) is *offered* for joining; T1/T2 near-candidates render with the
 *   differing field as the reason. Accepting sets [PlannedBook.mergedIntoBookId].
 * - **Mutations** (merge / split / reorder / edit) are pure functions that
 *   return a new plan, so the preview is re-plannable and an "undo my
 *   edits" reset is one step.
 */
object ImportPlanner {

    /** One existing library Work, as far as the plan needs to see it. */
    data class ExistingWork(
        val id: String,
        val title: String,
        val mergeKey: String
    )

    /**
     * Builds the plan for a scan result. [existingWorks] drives the T0 merge
     * suggestions; an empty list means "no merge suggestions" (e.g. rescan).
     */
    fun buildPlan(
        source: SourceRef,
        entries: List<LocalAudioEntry>,
        existingWorks: List<ExistingWork> = emptyList()
    ): ImportPlan {
        val byKey = existingWorks.associateBy { it.mergeKey }
        val byTitle = existingWorks.associateBy { MergeKey.normalizeTitle(it.title) }
        val books = mutableListOf<PlannedBook>()

        // 1) Loose files at the root → one single-chapter book each.
        for (entry in entries.filter { it.parentFolder.isNullOrBlank() }) {
            val title = sanitize(entry.fileName)
            val suggestion = suggest(title, "Локальний файл", byKey, byTitle)
            books += PlannedBook(
                id = "root:${entry.fileName}",
                title = title,
                author = "Локальний файл",
                chapters = listOf(
                    PlannedChapter(file = entry, title = title)
                ),
                suggestion = suggestion
            )
        }

        // 2) Each sub-folder → one book; files become naturally-sorted chapters.
        for ((folder, files) in entries.filter { !it.parentFolder.isNullOrBlank() }.groupBy { it.parentFolder }) {
            val folderName = folder ?: continue
            // Title from the last path segment so a relative path like
            // "SeriesA/Кобзар" still yields a clean "Кобзар" book name.
            val title = sanitize(folderName.substringAfterLast('/')).ifBlank { "Аудіокнига" }
            val chapters = files
                .sortedWith(Comparator { a, b -> compareNatural(a.fileName, b.fileName) })
                .map { PlannedChapter(file = it, title = sanitize(it.fileName).ifBlank { it.fileName }) }
            if (chapters.isEmpty()) continue
            val suggestion = suggest(title, "Локальна папка", byKey, byTitle)
            books += PlannedBook(
                id = "folder:$folderName",
                title = title,
                author = "Локальна папка",
                chapters = chapters,
                suggestion = suggestion
            )
        }

        return ImportPlan(source = source, books = books)
    }

    // -----------------------------------------------------------------
    // Mutations — pure, return a new plan
    // -----------------------------------------------------------------

    /** Accepts a suggestion: the planned book will attach to the existing Work. */
    fun acceptMerge(plan: ImportPlan, bookId: String): ImportPlan = plan.copy(
        books = plan.books.map { book ->
            val suggestion = book.suggestion
            if (book.id == bookId && suggestion != null && book.mergedIntoBookId == null) {
                book.copy(mergedIntoBookId = suggestion.existingBookId)
            } else book
        }
    )

    /** Rejects a suggestion: the pair becomes a remembered NEVER_MATCH. */
    fun rejectMerge(plan: ImportPlan, bookId: String): ImportPlan {
        val book = plan.books.firstOrNull { it.id == bookId } ?: return plan
        val suggestion = book.suggestion ?: return plan
        val neverMatch = CorrectionDraft(
            mergeKey = bookKey(book),
            kind = "NEVER_MATCH",
            value = suggestion.existingBookId
        )
        return plan.copy(
            books = plan.books.map { if (it.id == bookId) it.copy(suggestion = null, mergedIntoBookId = null) else it },
            corrections = plan.corrections + neverMatch
        )
    }

    /**
     * Splits a planned book at [chapterIndex]: the chapters before the split
     * stay under the original book (renamed with a suffix), the rest become a
     * second book with the same folder lineage. A SPLIT correction is
     * remembered so the pair never re-asks.
     */
    fun splitBook(plan: ImportPlan, bookId: String, chapterIndex: Int): ImportPlan {
        val book = plan.books.firstOrNull { it.id == bookId } ?: return plan
        if (chapterIndex <= 0 || chapterIndex >= book.chapters.size) return plan
        val first = book.copy(
            title = "${book.title} (1)",
            chapters = book.chapters.take(chapterIndex)
        )
        val second = book.copy(
            id = "${book.id}#2",
            title = "${book.title} (2)",
            chapters = book.chapters.drop(chapterIndex),
            suggestion = null,
            mergedIntoBookId = null
        )
        val splitCorrection = CorrectionDraft(
            mergeKey = bookKey(book),
            kind = "SPLIT",
            value = "${book.title} (2)"
        )
        val idx = plan.books.indexOf(book)
        val books = plan.books.toMutableList()
        books[idx] = first
        books.add(idx + 1, second)
        return plan.copy(books = books, corrections = plan.corrections + splitCorrection)
    }

    /** Reorders the chapters of a book — manual override of the natural sort. */
    fun reorderChapters(plan: ImportPlan, bookId: String, newOrder: List<Int>): ImportPlan {
        val book = plan.books.firstOrNull { it.id == bookId } ?: return plan
        if (newOrder.size != book.chapters.size || newOrder.toSet() != book.chapters.indices.toSet()) return plan
        val reordered = newOrder.map { book.chapters[it] }
        return plan.copy(books = plan.books.map { if (it.id == bookId) it.copy(chapters = reordered) else it })
    }

    /** Edits a planned book's metadata — becomes a remembered FIELD correction. */
    fun editBook(
        plan: ImportPlan,
        bookId: String,
        title: String? = null,
        author: String? = null,
        narrator: String? = null,
        seriesTitle: String? = null,
        seriesIndex: Int? = null
    ): ImportPlan {
        val book = plan.books.firstOrNull { it.id == bookId } ?: return plan
        val fieldCorrections = mutableListOf<CorrectionDraft>()
        if (title != null && title != book.title) {
            fieldCorrections += CorrectionDraft(mergeKey = bookKey(book), kind = "FIELD", value = "title=$title")
        }
        if (author != null && author != book.author) {
            fieldCorrections += CorrectionDraft(mergeKey = bookKey(book), kind = "FIELD", value = "author=$author")
        }
        if (narrator != null && narrator != book.narrator) {
            fieldCorrections += CorrectionDraft(mergeKey = bookKey(book), kind = "FIELD", value = "narrator=$narrator")
        }
        if (seriesTitle != null && seriesTitle != book.seriesTitle) {
            fieldCorrections += CorrectionDraft(mergeKey = bookKey(book), kind = "FIELD", value = "series=$seriesTitle")
        }
        if (seriesIndex != null && seriesIndex != book.seriesIndex) {
            fieldCorrections += CorrectionDraft(mergeKey = bookKey(book), kind = "FIELD", value = "seriesIndex=$seriesIndex")
        }
        if (fieldCorrections.isEmpty()) return plan
        return plan.copy(
            books = plan.books.map {
                if (it.id == bookId) it.copy(
                    title = title ?: it.title,
                    author = author ?: it.author,
                    narrator = narrator ?: it.narrator,
                    seriesTitle = seriesTitle ?: it.seriesTitle,
                    seriesIndex = seriesIndex ?: it.seriesIndex
                ) else it
            },
            corrections = plan.corrections + fieldCorrections
        )
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    /**
     * Merge suggestion for a planned book (never a silent merge):
     * - T0 when the book's normalized MergeKey matches an existing Work
     *   exactly (the #54 fast path);
     * - T2 title-only fallback when the author is the generic local label
     *   (local files carry no author — a same-title library Work is still a
     *   review candidate, but the mismatch is shown honestly).
     */
    private fun suggest(
        title: String,
        author: String,
        existingByKey: Map<String, ExistingWork>,
        existingByTitle: Map<String, ExistingWork>
    ): MergeSuggestion? {
        val key = MergeKey.keyFor(title, author)
        val exact = if (key.isNotBlank()) existingByKey[key] else null
        if (exact != null) {
            return MergeSuggestion(
                existingBookId = exact.id,
                existingTitle = exact.title,
                tier = 0,
                reason = "Точний збіг"
            )
        }
        // Local books have no real author — a same-title Work is a review
        // candidate, but only the title is evidence (T2).
        val normTitle = MergeKey.normalizeTitle(title)
        val byTitle = if (normTitle.isNotBlank()) existingByTitle[normTitle] else null
        if (byTitle != null) {
            return MergeSuggestion(
                existingBookId = byTitle.id,
                existingTitle = byTitle.title,
                tier = 2,
                reason = "Лише назва збігається (автор невідомий)"
            )
        }
        return null
    }

    // ADR-0010: the Work key is bibliographic — the narrator is an Edition
    // property, never part of the merge suggestion key.
    private fun bookKey(book: PlannedBook): String =
        MergeKey.keyFor(book.title, book.author)

    private fun sanitize(displayName: String): String =
        displayName.substringBeforeLast('.').trim().ifBlank { displayName }

    /** Natural (human) file-name comparison: track2 < track10. */
    private fun compareNatural(a: String, b: String): Int {
        val chunksA = SPLIT_CHUNKS.findAll(a.lowercase()).map { it.value }.toList()
        val chunksB = SPLIT_CHUNKS.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(chunksA.size, chunksB.size)) {
            val ca = chunksA[i]
            val cb = chunksB[i]
            val cmp = if (ca.first().isDigit() && cb.first().isDigit()) {
                (ca.toLongOrNull() ?: 0L).compareTo(cb.toLongOrNull() ?: 0L)
            } else {
                ca.compareTo(cb)
            }
            if (cmp != 0) return cmp
        }
        return chunksA.size - chunksB.size
    }

    private val SPLIT_CHUNKS = Regex("\\d+|\\D+")
}
