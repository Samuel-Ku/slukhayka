package com.slukhayka.audiobooks.data.entries

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.listening.WorkRelationshipsSync
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.metadata.CoverOverride
import com.slukhayka.audiobooks.data.metadata.CoverOverrideStore

/**
 * ADR-0053 / #854 (T1) — the tracked Work: a personal Library Entry over a
 * Work that NO source carries audio for yet.
 *
 * The model adds nothing: the Work row, the Library Entry and the card row
 * are the ordinary ones, and there is deliberately NO Edition, NO Source and
 * NO Track — the app never invents audio. The card therefore reads the honest
 * «аудіо недоступне» state (ADR-0042), and the manual «Прослухано» mark is the
 * ordinary Listening State flag (issue #752), not a second truth.
 *
 * [bookId] is the merge key itself: the card IS the Work's card, so a Work
 * tombstoned at deletion is tombstoned under the exact identity the write path
 * reads back ([AudiobookDao.isBookTombstoned]).
 */
data class TrackedWork(
    val bookId: String,
    val workId: String,
    val mergeKey: String,
    val title: String,
    val author: String,
    /**
     * #855 (T2) — the cover the listener knows from day one: their own URL, or
     * the one the bibliography candidate they picked carried. Null means
     * honestly absent — the card renders no cover, never a placeholder.
     */
    val coverUrl: String? = null
)

object TrackedWorkPolicy {

    const val REASON_NO_IDENTITY = "no-identity"
    const val REASON_NO_MOMENT = "no-moment"
    const val REASON_TOMBSTONED = "tombstoned"

    /**
     * The Work identity is the same `title|author` key the whole app merges
     * on ([MergeKey]); a nameless pair has no identity and is refused instead
     * of guessed.
     */
    fun identity(title: String, author: String, coverUrl: String? = null): TrackedWork? {
        val cleanTitle = title.trim()
        val cleanAuthor = author.trim()
        val key = MergeKey.keyFor(cleanTitle, cleanAuthor)
        if (cleanTitle.isBlank() || cleanAuthor.isBlank() || key.isBlank()) return null
        return TrackedWork(
            bookId = key,
            workId = key,
            mergeKey = key,
            title = cleanTitle,
            author = cleanAuthor,
            // #855 (T2) — only what the listener (or the candidate they picked)
            // actually supplied; a blank claim is absent, not an empty string.
            coverUrl = coverUrl?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * ADR-0014/0053 — the card carries ONLY what the listener actually knows.
     * Duration, chapter count, genre, description and source URL are honestly
     * absent: a fabricated duration is exactly what the milestone forbids.
     * The cover is present only when a real URL was given (#855 T2), and it
     * rides the same guarded card insert every other card uses.
     */
    fun card(work: TrackedWork): AudiobookEntity = AudiobookEntity(
        id = work.bookId,
        title = work.title,
        author = work.author,
        narrator = "",
        description = "",
        coverDrawableRes = 0,
        coverImageUrl = work.coverUrl,
        genre = "",
        sourceUrl = "",
        isDownloaded = false,
        totalDurationSeconds = 0L,
        totalChapters = 0,
        rating = 0f
    )
}

/**
 * ADR-0053 шов 1 (#854) — the manual tracked-Work write path. It uses the same
 * persistence doors as the import upsert (the guarded card insert, the Work
 * upsert, the Library Entry with its origin), MINUS the Source branch, so the
 * merge-on-write dedup and the tombstone gate stay exactly where they are.
 *
 * The write is idempotent by identity: the same `title|author` never forks a
 * second card, whether it is added twice by hand or the Work already lives in
 * the library with real audio.
 */
class TrackedWorks(
    private val dao: AudiobookDao,
    // ADR-0053 / US12 — the corner syncs through the existing mechanism: the
    // Work entering the library mirrors as an `entry` row. Null in tests /
    // without Firebase — then the write behaves exactly as before, silently.
    private val workRelationshipsSync: WorkRelationshipsSync? = null,
    /**
     * The Room transaction seam, the same one the import write path rides
     * (#618): a failure rolls the whole tracked write back instead of leaving
     * a card without its Work. Tests inject an in-memory transaction; the
     * default is a pass-through.
     */
    private val writeBatchRunner: suspend (suspend () -> Unit) -> Unit = { it() }
) {

    sealed interface Result {
        /** A new tracked card landed. */
        data class Added(val work: TrackedWork) : Result

        /** The identity already lives in the library — nothing was written. */
        data class AlreadyTracked(val work: TrackedWork) : Result

        data class Refused(val reason: String) : Result
    }

    /**
     * Creates (or resolves) the tracked Work behind [title] + [author], with
     * an optional [coverUrl] the listener already knows (#855 T2 — a cover
     * from day one, or a bibliography candidate's cover; no URL = honestly
     * absent).
     *
     * Refused for a blank identity, a missing moment, or a TOMBSTONED Work:
     * deletion is a decision, and the catalog door must never silently
     * resurrect it. A Work that already has a library card is returned as
     * [Result.AlreadyTracked] instead of duplicated.
     */
    suspend fun ensureTrackedWork(
        title: String,
        author: String,
        coverUrl: String? = null,
        now: Long = System.currentTimeMillis()
    ): Result {
        val work = TrackedWorkPolicy.identity(title, author, coverUrl)
            ?: return Result.Refused(TrackedWorkPolicy.REASON_NO_IDENTITY)
        if (now <= 0L) return Result.Refused(TrackedWorkPolicy.REASON_NO_MOMENT)
        // ADR-0005 — the tombstone gate stays: a deleted Work is a decision,
        // not a cache miss.
        if (dao.isBookTombstoned(work.mergeKey)) {
            return Result.Refused(TrackedWorkPolicy.REASON_TOMBSTONED)
        }

        // Dedup is by the WORK identity, not by the card id: a Work already
        // in the library (any rendition, any source) is not forked.
        val existing = dao.findByMergeKey(work.mergeKey)
        if (existing != null) {
            // #855 (T2) — the external claim may FILL a blank cover of an
            // already-tracked Work, never replace one.
            fillCoverGap(
                bookId = existing.id,
                mergeKey = work.mergeKey,
                claimed = work.coverUrl,
                current = existing.coverImageUrl
            )
            return Result.AlreadyTracked(
                work.copy(
                    bookId = existing.id,
                    workId = existing.workId?.takeIf { it.isNotBlank() } ?: work.workId
                )
            )
        }

        writeBatchRunner {
            val card = TrackedWorkPolicy.card(work)
            // ADR-0005: the same insert-unless-tombstoned door the catalog
            // write path uses — belt and braces behind the check above.
            dao.insertCatalogBookIfNotTombstoned(
                id = card.id,
                title = card.title,
                author = card.author,
                narrator = card.narrator,
                description = card.description,
                coverDrawableRes = card.coverDrawableRes,
                coverImageUrl = card.coverImageUrl,
                genre = card.genre,
                sourceUrl = card.sourceUrl,
                isDownloaded = card.isDownloaded,
                totalDurationSeconds = card.totalDurationSeconds,
                totalChapters = card.totalChapters,
                rating = card.rating,
                sourceTreeUri = card.sourceTreeUri
            )
            // Nothing hangs off a missing Work: create it before the link.
            if (dao.getWorkById(work.workId) == null) {
                dao.upsertWork(
                    WorkEntity(
                        id = work.workId,
                        mergeKey = work.mergeKey,
                        title = work.title,
                        author = work.author,
                        addedAt = now
                    )
                )
            }
            // ADR-0047 §1 — the listener typed it on purpose: the link begins
            // as an EXPLICIT save, so it never waits in «Імпортоване».
            if (dao.libraryEntryById(work.bookId) == null) {
                dao.insertLibraryEntryWithOrigin(
                    id = work.bookId,
                    workId = work.workId,
                    origin = LibraryEntryOrigin.EXPLICIT_SAVE.name,
                    createdAt = now
                )
            } else if (dao.libraryEntryById(work.bookId)?.origin != LibraryEntryOrigin.EXPLICIT_SAVE.name) {
                dao.updateLibraryEntryOrigin(work.bookId, LibraryEntryOrigin.EXPLICIT_SAVE.name)
            }
        }

        // Best-effort and silent by the seam's contract: a failing mirror
        // never breaks the manual write.
        runCatching { workRelationshipsSync?.pushEntry(work.mergeKey, work.title, work.author) }

        return Result.Added(work)
    }

    /**
     * #855 (T2) — puts a claimed cover on an already-tracked Work through the
     * ordinary cover write path ([AudiobookDao.updateCoverImageUrl], the same
     * door the import and the resolvers use). The GAP only: a locally known
     * cover and, above all, the listener's Override ([CoverOverride]) outrank
     * the claim. Best-effort and silent — a cover never breaks a write that
     * already landed.
     */
    private suspend fun fillCoverGap(
        bookId: String,
        mergeKey: String,
        claimed: String?,
        current: String?
    ) {
        if (claimed == null || !current.isNullOrBlank()) return
        // A failing Override read counts as «decided»: degrade-never, so it
        // never licenses a write the listener may have forbidden.
        val decided = runCatching {
            CoverOverride.blocksWrite(CoverOverrideStore(dao).pinned(mergeKey))
        }.getOrDefault(true)
        if (decided) return
        runCatching { dao.updateCoverImageUrl(bookId, claimed) }
    }
}
