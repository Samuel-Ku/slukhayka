package com.slukhayka.audiobooks.data.entries

/**
 * ADR-0046 §§3–4 / ADR-0047 §1 / spec-54 T15 (#870) — adding a book of ANY
 * format by hand. Two things are deliberately hard here:
 *
 * - a FORMAT is not an Edition and not a Source: a paper or e-book record must
 *   carry neither, so nothing fictitious ever reaches the audio tables;
 * - the app never invents a "want to read" for the listener: a Readthrough is
 *   created only when they asked for one.
 */
data class ManualBookAddRequest(
    val title: String,
    val author: String,
    val format: ReadingFormat,
    /** The EXISTING audio narration; only an audio add may name one. */
    val editionId: String? = null,
    /** The listener's own file/folder import (an explicit action, ADR-0047 §1). */
    val importedOwnFile: Boolean = false,
    /** The listener explicitly said "I want to read this". Never assumed. */
    val wantsToRead: Boolean = false,
    val now: Long = 0L
)

/** What the add will actually write — decided before a single row exists. */
sealed interface ManualBookAddPlan {
    data class Add(
        val format: ReadingFormat,
        val origin: LibraryEntryOrigin,
        /** Null when the listener did NOT ask to start: no invented state. */
        val readthrough: Readthrough?
    ) : ManualBookAddPlan

    data class Refused(val reason: String) : ManualBookAddPlan
}

object ManualBookAddPolicy {

    const val REASON_NO_IDENTITY = "no-identity"
    const val REASON_AUDIO_NEEDS_EDITION = "audio-needs-edition"
    const val REASON_EDITION_NOT_ALLOWED = "edition-not-allowed"
    const val REASON_NO_MOMENT = "no-moment"

    /**
     * A Work needs a real identity (the same `title|author` key the rest of the
     * app merges on), so a nameless add is refused instead of guessed.
     */
    fun plan(request: ManualBookAddRequest): ManualBookAddPlan {
        if (request.title.isBlank() || request.author.isBlank()) {
            return ManualBookAddPlan.Refused(REASON_NO_IDENTITY)
        }
        // No Work identity means no Work to attach the entry to: refuse rather
        // than create an unmergeable orphan.
        if (workKey(request).isBlank()) return ManualBookAddPlan.Refused(REASON_NO_IDENTITY)
        if (request.now <= 0L) return ManualBookAddPlan.Refused(REASON_NO_MOMENT)

        return when {
            // An audio add IS an existing narration: without an Edition there is
            // no audio to point at, and inventing one would be a lie.
            ReadthroughPolicy.editionAllowed(request.format) -> {
                val editionId = request.editionId
                if (editionId.isNullOrBlank()) {
                    ManualBookAddPlan.Refused(REASON_AUDIO_NEEDS_EDITION)
                } else {
                    ManualBookAddPlan.Add(
                        format = request.format,
                        origin = originFor(request),
                        readthrough = readthroughFor(request, editionId)
                    )
                }
            }
            // A paper or e-book record creates NEITHER an Edition NOR a Source.
            request.editionId != null ->
                ManualBookAddPlan.Refused(REASON_EDITION_NOT_ALLOWED)
            else -> ManualBookAddPlan.Add(
                format = request.format,
                origin = originFor(request),
                readthrough = readthroughFor(request, editionId = null)
            )
        }
    }

    /**
     * ADR-0047 §1 — the link began EXPLICITLY either way; the ONLY difference
     * is whether the listener imported their own file. It is never AUTO_SEED.
     */
    private fun originFor(request: ManualBookAddRequest): LibraryEntryOrigin =
        if (request.importedOwnFile) {
            LibraryEntryOrigin.EXPLICIT_IMPORT
        } else {
            LibraryEntryOrigin.EXPLICIT_SAVE
        }

    /**
     * The listener's OWN choice decides whether a pass exists at all. Saying
     * nothing means no pass — the app must not answer "Хочу прочитати" for
     * them (AC: «власний імпорт не створює особистий намір за слухача»).
     */
    private fun readthroughFor(request: ManualBookAddRequest, editionId: String?): Readthrough? {
        if (!request.wantsToRead) return null
        return ReadthroughPolicy.start(
            id = readthroughId(request),
            libraryEntryId = libraryEntryId(request),
            workId = workKey(request),
            format = request.format,
            startedAt = request.now,
            editionId = editionId
        )
    }

    /**
     * The Work identity the rest of the app merges on (`MergeKey`), so a manual
     * add joins the existing Work instead of forking a private one.
     */
    fun workKey(request: ManualBookAddRequest): String =
        com.slukhayka.audiobooks.data.merge.MergeKey.keyFor(request.title, request.author)

    /** Deterministic, derived from the identity: an idempotent manual add. */
    fun libraryEntryId(request: ManualBookAddRequest): String =
        "manual:" + request.format.name.lowercase() + ":" + workKey(request)

    fun readthroughId(request: ManualBookAddRequest): String = "rt-" + libraryEntryId(request)
}
