package com.slukhayka.audiobooks.data.imports

import com.slukhayka.audiobooks.data.merge.MergeKey

/**
 * Pure JVM planner of the smart import (wayfinder #29). Turns scanned
 * [LocalAudioEntry]s into an [ImportPlan] without touching disk or Room:
 *
 * - **Grouping** uses the same rule as direct import and rescan. The folder
 *   choice makes root files one book or separate books; every sub-folder
 *   remains a book. Chapters are naturally sorted (1, 2, 3, 10).
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

        val folder = source as? SourceRef.Folder
        val grouping = folder?.grouping ?: LocalFolderGrouping.SEPARATE_BOOKS
        for ((key, files) in LocalImportGrouping.group(entries, grouping)) {
            val title = when {
                key == LocalImportGrouping.ROOT_BOOK -> folder?.displayName.orEmpty().trim()
                key.startsWith("folder:") -> key.removePrefix("folder:").substringAfterLast('/')
                else -> sanitize(files.single().fileName)
            }
            val author = if (key.startsWith("root:")) "Локальний файл" else "Локальна папка"
            books += PlannedBook(
                id = key,
                title = title,
                author = author,
                chapters = files.map { PlannedChapter(file = it, title = sanitize(it.fileName)) },
                suggestion = suggest(title, author, byKey, byTitle)
            )
        }

        return ImportPlan(source = source, books = books)
    }

    // -----------------------------------------------------------------
    // Mutations — pure, return a new plan
    // -----------------------------------------------------------------

    /** Only books with direct root files are rebuilt by a grouping change. */
    fun groupingChangeDiscardsCorrections(plan: ImportPlan): Boolean {
        val folder = plan.source as? SourceRef.Folder ?: return false
        val affected = booksWithRootFiles(plan)
        val ids = affected.map { it.id }.toSet()
        val baseline = ImportPlanner.buildPlan(folder, affected.flatMap { book -> book.chapters.map { it.file } }).books
        return affected.any { it.mergedIntoBookId != null } ||
            affected.map { it.copy(suggestion = null, mergedIntoBookId = null) } != baseline ||
            plan.corrections.any { it.plannedBookId in ids }
    }

    private fun booksWithRootFiles(plan: ImportPlan): List<PlannedBook> =
        plan.books.filter { book -> book.chapters.any { it.file.parentFolder.isNullOrBlank() } }

    /** Rebuilds only books containing root files; unrelated edits survive. */
    fun changeFolderGrouping(plan: ImportPlan, grouping: LocalFolderGrouping): ImportPlan {
        val folder = plan.source as? SourceRef.Folder ?: return plan
        if (folder.grouping == grouping) return plan
        val affected = booksWithRootFiles(plan)
        if (affected.isEmpty()) return plan
        val affectedIds = affected.map { it.id }.toSet()
        val remaining = plan.books.filterNot { it.id in affectedIds }
        val usedIds = remaining.map { it.id }.toMutableSet()
        val source = folder.copy(grouping = grouping)
        val rebuilt = buildPlan(source, affected.flatMap { book -> book.chapters.map { it.file } }).books.map { book ->
            var id = book.id
            var suffix = 2
            while (!usedIds.add(id)) id = "${book.id}#${suffix++}"
            book.copy(id = id)
        }
        return plan.copy(
            source = source,
            books = rebuilt + remaining,
            corrections = plan.corrections.filterNot { it.plannedBookId in affectedIds }
        )
    }

    /** Joins two explicit preview selections before any audio is copied. */
    fun mergePlannedBooks(plan: ImportPlan, sourceBookId: String, targetBookId: String): ImportPlan {
        if (sourceBookId == targetBookId) return plan
        val source = plan.books.firstOrNull { it.id == sourceBookId } ?: return plan
        val target = plan.books.firstOrNull { it.id == targetBookId } ?: return plan
        val merged = target.copy(
            chapters = target.chapters + source.chapters,
            suggestion = null,
            mergedIntoBookId = null
        )
        return plan.copy(
            books = plan.books.filterNot { it.id == sourceBookId }.map { if (it.id == targetBookId) merged else it },
            corrections = plan.corrections.map { correction ->
                if (correction.plannedBookId == sourceBookId) correction.copy(plannedBookId = targetBookId) else correction
            } + CorrectionDraft(
                mergeKey = bookKey(target), kind = "MERGE", value = "$sourceBookId->$targetBookId", plannedBookId = targetBookId
            )
        )
    }

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
            value = suggestion.existingBookId,
            plannedBookId = bookId
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
            chapters = book.chapters.take(chapterIndex),
            suggestion = null,
            mergedIntoBookId = null
        )
        var suffix = 2
        while (plan.books.any { it.id == "${book.id}#$suffix" }) suffix++
        val second = book.copy(
            id = "${book.id}#$suffix",
            title = "${book.title} (2)",
            chapters = book.chapters.drop(chapterIndex),
            suggestion = null,
            mergedIntoBookId = null
        )
        val splitCorrection = CorrectionDraft(
            mergeKey = bookKey(book),
            kind = "SPLIT",
            value = "${book.title} (2)",
            plannedBookId = bookId
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
        seriesIndex: Int? = null,
        clearSeriesIndex: Boolean = false
    ): ImportPlan {
        val book = plan.books.firstOrNull { it.id == bookId } ?: return plan
        val fieldCorrections = mutableListOf<CorrectionDraft>()
        if (title != null && title != book.title) {
            fieldCorrections += CorrectionDraft(mergeKey = bookKey(book), kind = "FIELD", value = "title=$title", plannedBookId = bookId)
        }
        if (author != null && author != book.author) {
            fieldCorrections += CorrectionDraft(mergeKey = bookKey(book), kind = "FIELD", value = "author=$author", plannedBookId = bookId)
        }
        if (narrator != null && narrator != book.narrator) {
            fieldCorrections += CorrectionDraft(mergeKey = bookKey(book), kind = "FIELD", value = "narrator=$narrator", plannedBookId = bookId)
        }
        if (seriesTitle != null && seriesTitle != book.seriesTitle) {
            fieldCorrections += CorrectionDraft(mergeKey = bookKey(book), kind = "FIELD", value = "series=$seriesTitle", plannedBookId = bookId)
        }
        if ((seriesIndex != null || clearSeriesIndex) && seriesIndex != book.seriesIndex) {
            fieldCorrections += CorrectionDraft(mergeKey = bookKey(book), kind = "FIELD", value = "seriesIndex=${seriesIndex ?: ""}", plannedBookId = bookId)
        }
        if (fieldCorrections.isEmpty()) return plan
        val identityChanged = (title != null && title != book.title) ||
            (author != null && author != book.author) || (narrator != null && narrator != book.narrator)
        return plan.copy(
            books = plan.books.map {
                if (it.id == bookId) it.copy(
                    title = title ?: it.title,
                    author = author ?: it.author,
                    narrator = narrator ?: it.narrator,
                    seriesTitle = seriesTitle ?: it.seriesTitle,
                    seriesIndex = if (clearSeriesIndex) seriesIndex else seriesIndex ?: it.seriesIndex,
                    suggestion = if (identityChanged) null else it.suggestion,
                    mergedIntoBookId = if (identityChanged) null else it.mergedIntoBookId
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

}
