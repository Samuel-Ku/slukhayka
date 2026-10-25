package com.slukhayka.audiobooks.data.imports

/**
 * #1230 — the ONE recovery language for «аудіо книги більше не читається».
 * The same seam is what #1185/T2 («тека недоступна» + «відновити доступ»)
 * will speak: a local book is recovered by pointing it at the listener's own
 * files again, never by silently playing another narration and never by
 * asking the network first (ADR-0037: the network search is the LAST explicit
 * step, not the first suggestion).
 *
 * The order is the owner decision of 2026-10-11:
 *   1. re-pick a FILE for this book,
 *   2. re-pick a FOLDER (the folder may have moved to another tree),
 *   3. re-scan the KNOWN folder when the app still remembers it,
 *   4. only then the Source Watch / network search.
 *
 * Identity is byte identity. ADR-0007 §4 and the owner decision say that a
 * matching title, a matching file name or the same chapter count is not proof
 * of the same rendition; only a stored [contentHash] equal to the picked
 * file's hash can relink a track. When no exact hash matches, nothing is
 * written — the listener must choose the other narration explicitly through
 * the ordinary import door.
 *
 * Pure JVM, no Android, no I/O: the callers hash the files and feed the
 * observations here. [com.slukhayka.audiobooks.data.imports.LibraryImport]
 * owns the only writer, so this unit cannot create a second card or a silent
 * Edition fork by construction.
 */
object LocalAccessRecovery {

    enum class Action {
        RESELECT_FILE,
        RESELECT_FOLDER,
        RESCAN_KNOWN_FOLDER,
        SOURCE_WATCH
    }

    /**
     * What the open book looks like to the recovery surface.
     *
     * [otherPlayableSource] is true when some non-local Source can still play
     * the Edition: recovery is only for a book whose local audio is the
     * reason it does not play, not a replacement for a healthy remote path.
     */
    data class State(
        val localBook: Boolean,
        val hasKnownFolder: Boolean,
        val localFilesUnreadable: Boolean,
        val otherPlayableSource: Boolean
    ) {
        val recoverable: Boolean
            get() = localBook && localFilesUnreadable && !otherPlayableSource

        /** Ordered suggestions; empty when this is not a local-access failure. */
        fun actions(): List<Action> {
            if (!recoverable) return emptyList()
            return buildList {
                add(Action.RESELECT_FILE)
                add(Action.RESELECT_FOLDER)
                if (hasKnownFolder) add(Action.RESCAN_KNOWN_FOLDER)
                // The network is an explicit follow-up, never the first step.
                add(Action.SOURCE_WATCH)
            }
        }
    }

    /** One stored physical track of the Edition, identified by its bytes. */
    data class StoredTrack(val trackIndex: Int, val contentHash: String?)

    /** One file the listener picked, already hashed by the caller. */
    data class PickedFile(
        val fileName: String,
        val contentHash: String,
        /** SAF document URI that becomes the track's playable address. */
        val locator: String? = null
    )

    data class Relink(val trackIndex: Int, val picked: PickedFile)

    data class MatchPlan(
        val relinks: List<Relink>,
        /** Picked files whose bytes this book has never stored. */
        val unprovenFiles: List<PickedFile>
    ) {
        /** Only exact bytes prove the same rendition; names never do. */
        val identityProven: Boolean
            get() = relinks.isNotEmpty()
    }

    /**
     * Pairs stored tracks with picked files by exact SHA-256 only.
     *
     * A picked file can satisfy several stored tracks only when those tracks
     * carried the same hash (duplicate audio); each track is relinked once.
     * Files with an unknown hash are never matched — an absent hash is not
     * evidence (ADR-0014).
     */
    fun match(stored: List<StoredTrack>, picked: List<PickedFile>): MatchPlan {
        val byHash = picked
            .filter { it.contentHash.isNotBlank() }
            .groupBy { it.contentHash }
        val relinks = stored.mapNotNull { track ->
            val hash = track.contentHash?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val file = byHash[hash]?.firstOrNull() ?: return@mapNotNull null
            Relink(track.trackIndex, file)
        }
        val storedHashes = stored.mapNotNull { it.contentHash?.takeIf(String::isNotBlank) }.toSet()
        val unproven = picked.filter { it.contentHash.isBlank() || it.contentHash !in storedHashes }
        return MatchPlan(relinks = relinks, unprovenFiles = unproven)
    }
}
