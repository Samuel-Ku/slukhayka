package com.slukhayka.audiobooks.data.imports

/**
 * The staging structure of the smart import (wayfinder #29): scan -> plan ->
 * confirm -> apply. The plan is pure data - built from scanned entries,
 * rendered by the preview, consumed by [LibraryImport.applyImportPlan].
 * Nothing touches disk or Room until the user confirms; `apply` is the only
 * writer.
 *
 * Corrections the user makes in the preview (merge / split / never-match /
 * field edits) ride inside the plan and are written to the `corrections`
 * store at apply time - one write path, no orphan decisions (#54/#56).
 */
data class ImportPlan(
    val source: SourceRef,
    val books: List<PlannedBook>,
    val corrections: List<CorrectionDraft> = emptyList()
)

/** Where the scanned entries came from (the Step 1 source sheet). */
sealed class SourceRef {
    data class Folder(val treeUri: String) : SourceRef()
    data class Files(val uris: List<String>) : SourceRef()
    data class Rescan(val treeUri: String) : SourceRef()
}

/** One book the import would create - editable in the preview, applied on confirm. */
data class PlannedBook(
    // Stable within the plan (root file stem / relative folder path), so
    // merges, splits and reorders can address books without object identity.
    val id: String,
    // Recognised or edited; "" = needs attention.
    val title: String,
    // "" = unknown, editable.
    val author: String,
    val narrator: String = "",
    val seriesTitle: String? = null,
    val seriesIndex: Int? = null,
    val chapters: List<PlannedChapter>,
    // A T0/T1/T2 candidate from the #54 pipeline, if any - rendered as a
    // review row, never a silent merge.
    val suggestion: MergeSuggestion? = null,
    // Set when the user accepts the suggestion: the source will attach to
    // this existing Work instead of creating a new card.
    val mergedIntoBookId: String? = null
)

/** One chapter of a [PlannedBook] - lazy stream, no bytes touched in preview. */
data class PlannedChapter(
    val file: LocalAudioEntry,
    // Natural-sorted by file name (1, 2, 3, 10 - the existing rule).
    val title: String,
    // SHA-256 stream-hash computed in preview (no copy); null if not hashed.
    val contentHash: String? = null,
    // 0 = unknown until probe/playback; the preview shows "-" honestly.
    val durationSeconds: Long = 0L
)

/** A #54 identity candidate: the library Work this planned book would join. */
data class MergeSuggestion(
    val existingBookId: String,
    val existingTitle: String,
    // T0 = exact normalized MergeKey; T1/T2 = near candidates (review only).
    val tier: Int,
    // The one field that prevented T0, for the review row.
    val reason: String
)

/** A correction to persist at apply time (wayfinder #54 Q9 / #56). */
data class CorrectionDraft(
    val mergeKey: String,
    val kind: String,
    val value: String = "",
    val origin: String = "USER_MADE"
)
