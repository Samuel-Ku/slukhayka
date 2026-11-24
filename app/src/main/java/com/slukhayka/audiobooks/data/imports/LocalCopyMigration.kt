package com.slukhayka.audiobooks.data.imports

import android.util.Log
import com.slukhayka.audiobooks.data.contentHashOf
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.CorrectionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ADR-0061 §2 / #1185 T4 — the one-time pass that frees the private copies an
 * older version wrote.
 *
 * For every local book whose tracks still carry a copied file AND whose
 * remembered tree grant is alive, the original document is proven by content
 * hash — the folder memory's current hash is used where the SAF metadata still
 * matches (T3, #1475), and only a miss reads the stream — and the track moves
 * onto the SAF document URI. The copy is deleted only AFTER the rows commit.
 *
 * A dead or unavailable grant keeps its copies exactly as they are (ADR-0061
 * §2: the copy is then the only audio the listener has), and a single-file
 * import is kept too — its document URI was never stored, so there is nothing
 * to migrate TO. Both boundaries are counted in the report instead of being
 * silently skipped.
 */
class LocalCopyMigration(
    private val dao: AudiobookDao,
    private val copyRoot: File,
    private val scanTree: suspend (String) -> List<LocalAudioEntry>,
    private val writeBatchRunner: suspend (suspend () -> Unit) -> Unit
) {

    /** What one pass did — the listener-facing report numbers. */
    data class Report(
        val relinkedTracks: Int = 0,
        val freedBytes: Long = 0L,
        val booksMigrated: Int = 0,
        /**
         * Tracks whose copy stays: no tree (a single-file import), a dead or
         * unavailable grant, bytes that no longer match, a foreign path, or a
         * rolled-back transaction. The report never guesses which one it was.
         */
        val keptTracks: Int = 0,
        /** Trees that could not be opened at all (grant revoked / provider gone). */
        val unavailableTrees: Int = 0,
        /** The pass already ran on this device. */
        val alreadyRan: Boolean = false,
        /**
         * A transient failure (an unreadable tree, a rolled-back transaction)
         * means the pass will try again on the next launch: the once-per-device
         * marker is only written for a clean run.
         */
        val retryPlanned: Boolean = false
    ) {
        val changed: Boolean get() = relinkedTracks > 0
    }

    /**
     * Runs the migration once per device. The marker lives in correction memory
     * (the app's durable store) so a reinstall or a new backup may run it again;
     * the pass itself is idempotent — an already-migrated track has no copy left.
     */
    suspend fun runOnce(): Report = withContext(Dispatchers.IO) {
        if (hasRun()) return@withContext Report(alreadyRan = true)
        val report = migrate()
        if (report.retryPlanned) {
            Log.w(TAG, "copy migration deferred: a transient failure kept some copies")
        } else {
            markRun()
        }
        report
    }

    private suspend fun migrate(): Report {
        var relinked = 0
        var freed = 0L
        var books = 0
        var kept = 0

        // The migration walks the LOCAL SOURCE, not just books with a blank
        // sourceUrl: a legacy local source merged into a networked book still
        // owns copied files, and skipping it silently would hide them forever.
        val localBooks = dao.getAllAudiobooksOnce()
        // One walk and one hash pass per TREE: every book of the same import
        // shares the tree, and on the legacy (v2) memory a miss would otherwise
        // re-read the whole folder once per book.
        val addressesByTree = mutableMapOf<String, Map<String, String>>()
        val unavailableTrees = mutableSetOf<String>()
        var rolledBack = false
        for (book in localBooks) {
            val localSource = dao.getSourcesForBookSync(book.id).firstOrNull { it.type == "local" }
                ?: continue
            val allTracks = dao.getTracksForSourceSync(localSource.id)
            // Only the private copy dir this pass owns is a candidate: a path
            // anywhere else is not ours to relink or delete. Every other track
            // that still carries a copied file is kept and reported, never
            // silently skipped.
            val copied = allTracks.filter { track ->
                val path = track.localFilePath
                !path.isNullOrBlank() && !track.contentHash.isNullOrBlank() &&
                    File(path).startsWith(copyRoot) && File(path).isFile
            }
            val keptByPolicy = allTracks.count { track ->
                !track.localFilePath.isNullOrBlank() && track !in copied
            }
            if (copied.isEmpty()) {
                kept += keptByPolicy
                continue
            }

            val tree = book.sourceTreeUri?.takeIf { it.isNotBlank() }
            if (tree == null) {
                // A single-file import never stored its document URI: no address
                // to move to, so the copy stays and is reported, not guessed.
                kept += keptByPolicy + copied.size
                continue
            }
            if (tree in unavailableTrees) {
                kept += keptByPolicy + copied.size
                continue
            }

            val addresses = addressesByTree[tree] ?: run {
                val entries = try {
                    scanTree(tree)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "copy migration could not open $tree", e)
                    unavailableTrees += tree
                    null
                }
                if (entries == null) {
                    null
                } else {
                    resolveAddresses(tree, entries).also { addressesByTree[tree] = it }
                }
            }
            if (addresses == null) {
                kept += keptByPolicy + copied.size
                continue
            }

            val migratable = copied.mapNotNull { track ->
                addresses[track.contentHash]?.let { address -> track to address }
            }
            if (migratable.isEmpty()) {
                kept += keptByPolicy + copied.size
                continue
            }

            try {
                writeBatchRunner {
                    dao.insertTracks(
                        migratable.map { (track, address) ->
                            track.copy(url = address, localFilePath = null)
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The rows did not commit: every file of THIS book stays, and
                // the next launch retries (the marker is not written).
                Log.w(TAG, "copy migration rolled back for ${book.id}", e)
                rolledBack = true
                kept += keptByPolicy + copied.size
                continue
            }

            books++
            relinked += migratable.size
            kept += keptByPolicy + (copied.size - migratable.size)
            migratable.forEach { (track, _) ->
                val path = track.localFilePath ?: return@forEach
                val file = File(path)
                val size = if (file.isFile) file.length() else 0L
                val deleted = runCatching { file.delete() }.getOrDefault(false)
                if (deleted || !file.exists()) freed += size
            }
        }

        return Report(
            relinkedTracks = relinked,
            freedBytes = freed,
            booksMigrated = books,
            keptTracks = kept,
            unavailableTrees = unavailableTrees.size,
            retryPlanned = unavailableTrees.isNotEmpty() || rolledBack
        )
    }

    /**
     * Which hash lives at which document URI. The folder memory answers first
     * (T3: an unchanged file keeps its remembered hash and is not read); only a
     * metadata miss or an unknown path reads the stream.
     */
    private suspend fun resolveAddresses(tree: String, entries: List<LocalAudioEntry>): Map<String, String> {
        val remembered = mergedRecords(LocalFolderMemory(dao).read(tree))
        val addresses = mutableMapOf<String, String>()
        for (entry in entries) {
            val uri = entry.uri ?: continue
            val cached = rememberedHashFor(
                remembered,
                entry.fileName,
                entry.parentFolder,
                entry.sizeBytes,
                entry.lastModifiedMs
            )
            if (cached != null) {
                addresses.putIfAbsent(cached, uri)
                continue
            }
            val hash = try {
                entry.openStream().use { contentHashOf(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "copy migration could not read ${entry.fileName}", e)
                null
            }
            if (!hash.isNullOrBlank()) addresses.putIfAbsent(hash, uri)
        }
        return addresses
    }

    private suspend fun hasRun(): Boolean =
        runCatching { dao.getCorrectionsForMergeKey(GUARD_KEY).isNotEmpty() }.getOrDefault(false)

    private suspend fun markRun() {
        runCatching {
            dao.upsertCorrection(
                CorrectionEntity(
                    mergeKey = GUARD_KEY,
                    kind = "FIELD",
                    value = "1",
                    origin = "USER_MADE"
                )
            )
        }.onFailure { Log.w(TAG, "copy migration marker not written", it) }
    }

    private companion object {
        const val TAG = "LocalCopyMigration"

        /** One row = the pass ran; corrections are the app's durable memory. */
        const val GUARD_KEY = "local-copy-migration"
    }
}
