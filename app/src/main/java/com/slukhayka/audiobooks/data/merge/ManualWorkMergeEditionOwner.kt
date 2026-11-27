package com.slukhayka.audiobooks.data.merge

internal sealed interface ManualWorkMergeEditionOwner {
    data class Library(val workId: String, val bookId: String) : ManualWorkMergeEditionOwner
    data class Catalog(val workId: String) : ManualWorkMergeEditionOwner
}

internal fun manualMergeEditionOwner(
    preview: ManualWorkMergePreview,
    workId: String,
    edition: ManualWorkMergeEditionPreview,
): ManualWorkMergeEditionOwner? {
    val library = preview.snapshot.library
    val bookId = edition.bookId
    if (bookId != null) {
        return if (edition.edition.workId == bookId &&
            library.entries.any { it.id == bookId && it.workId == workId } &&
            library.books.any { it.id == bookId } &&
            hasProvenLibraryEditionOwner(library, workId, edition.edition)) {
            ManualWorkMergeEditionOwner.Library(workId, bookId)
        } else null
    }
    val id = edition.edition.id
    if (edition.edition.workId != workId ||
        library.entries.any { it.id == workId && it.workId != workId } || library.books.any { it.id == workId } ||
        library.sources.any { it.editionId == id } || library.chapters.any { it.editionId == id } ||
        library.progress.any { it.editionId == id } || library.bookmarks.any { it.editionId == id }) return null
    return ManualWorkMergeEditionOwner.Catalog(workId)
}

/** Actual physical ownership, never a personal Readthrough Entry or an ID-shape heuristic. */
internal fun hasProvenLibraryEditionOwner(
    library: ManualWorkMergeLibraryRows,
    workId: String,
    edition: com.slukhayka.audiobooks.data.db.EditionEntity,
): Boolean {
    val bookId = edition.workId
    if (library.books.none { it.id == bookId } ||
        library.entries.none { it.id == bookId && it.workId == workId }) return false
    val owners = library.sources.filter { it.editionId == edition.id }.map { it.bookId } +
        library.chapters.filter { it.editionId == edition.id }.map { it.bookId } +
        library.progress.filter { it.editionId == edition.id }.map { it.bookId } +
        library.bookmarks.filter { it.editionId == edition.id }.map { it.bookId }
    if (owners.any { it != bookId }) return false
    // A distinct Book anchor is explicit. Same-string Work/Book additionally
    // requires positive physical-edge evidence to distinguish a catalog anchor.
    return bookId != workId || owners.isNotEmpty()
}
