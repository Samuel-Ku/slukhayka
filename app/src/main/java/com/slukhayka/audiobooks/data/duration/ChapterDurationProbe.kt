package com.slukhayka.audiobooks.data.duration

import android.util.Log
import com.slukhayka.audiobooks.data.EditionId
import com.slukhayka.audiobooks.data.catalog.SourceCatalog
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.BookRow
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.metadata.DurationProvenance
import com.slukhayka.audiobooks.data.metadata.DurationSanity
import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * spec-24 T8 (#169) — the background chapter-duration probing pass, sibling
 * of [DurationEnrichment] (same policy shape: atomic single-flight CAS
 * throttle, bounded batch, per-row failure isolation, honest writes).
 *
 * Chapter durations stop being playback-only knowledge: for every library
 * book with at least one unknown-duration chapter and a stream URL (local
 * imports have none — nothing to probe), the pass probes each unknown track
 * through [StreamProber]: HEAD → Content-Length, ranged GET of the stream
 * head → CBR MPEG frame header (bitrate + sample rate, two-frame gate —
 * VBR/Xing is never guessed), then writes duration = size × 8 / bitrate
 * through the existing per-chapter seam ([AudiobookDao.updateChapterDuration])
 * — the chapter lists (book page, «Розділи» sheet) pick it up from the Room
 * flow automatically, no UI change.
 *
 * When every chapter of a book is now known and the book's total duration
 * was unknown (0), the book total becomes the sum of the chapters — a
 * site-provided total is never overwritten.
 *
 * The pass is bounded (batch ≥ 20 books since #350), sweeps newest books
 * first and probes one book's tracks with bounded parallelism
 * ([DEFAULT_PROBE_CONCURRENCY]); it is throttled to one pass per 30 minutes
 * in memory only (same doctrine as the enrichment pass); a failing probe
 * leaves the row untouched and never aborts the batch. The chapter→track
 * pairing rides the catalog seam ([SourceCatalog.getPlayableChapters]) —
 * the same 1:1-by-index pairing the player uses, never a duplicated copy.
 *
 * #349 adds the sibling targeted probe [probeBookNow]: fired detached by the
 * import doors so a fresh card is filled in seconds, bypassing the sweep's
 * throttle with its own per-book single-flight.
 *
 * #1187 — local imports are no longer skipped. A local book's chapters are
 * measured from the files themselves through [LocalFileDurationReader]
 * ([MediaMetadataRetrieverLocalFileDuration] in production: the container's
 * metadata, offline, without reading the audio bytes), by the sweep and by the
 * targeted post-import probe alike — the same door that repairs books imported
 * before this existed, without asking anyone to re-listen. Without a reader a
 * local book keeps its old no-op path; either way the library no longer takes
 * the partial sum for the book's total
 * ([com.slukhayka.audiobooks.data.listening.knownBookTotalSeconds] refuses it),
 * so an unmeasured local book shows the honest unknown state instead of a
 * length that climbs as it is listened to.
 */
class ChapterDurationProbe(
    private val dao: AudiobookDao,
    private val playableChapters: suspend (String) -> List<SourceCatalog.PlayableChapter>,
    private val prober: StreamProber,
    // Spec-30 T4 (#219): the shared book-metadata store — a book whose total
    // becomes known from probed chapters contributes it to the shared base.
    // Null without Firebase keys: probing behaves exactly as before.
    // Best-effort by contract — a failing write never breaks a pass.
    private val sharedStore: SharedBookMetaStore? = null,
    // #1187 — how long the audio in one LOCAL file is. Local imports have no
    // stream, so before this seam their chapters only got a duration from
    // playback; the book's total therefore grew with what had been heard, and
    // the library finished a book nobody had finished. The default is the
    // production retriever (it needs no Context) rather than an argument wired
    // in App.kt: `App.kt` sits in the root package, so any diff there maps the
    // WHOLE test matrix (docs/agents/local-environment.md). Null turns the
    // local half off — the pre-#1187 no-op path, kept for tests and for a
    // platform without a retriever.
    private val localDurations: LocalFileDurationReader? = MediaMetadataRetrieverLocalFileDuration()
) {

    /** Timestamp of the last completed pass, as an atomic CAS gate. */
    private val lastProbeRunEpochMs = AtomicLong(0L)

    /** #349: books with a targeted probe currently in flight — per-book single-flight. */
    private val targetedInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * #349 — the targeted post-import probe of ONE book, fired detached by
     * the import doors: a fresh card gets its chapter durations in seconds
     * instead of waiting for the hourly sweep. It ignores the pass throttle
     * (that CAS gate serves the bounded background sweep, not one eager user
     * action) and is single-flight per book — a concurrent second call for
     * the same book loses the race and probes nothing. Best-effort like the
     * pass: any failure leaves rows untouched.
     *
     * #1187 — a LOCAL import reaches it too, and that is the repair door for
     * books imported before the local reader existed: the reader measures the
     * copied files themselves, without the listener playing anything. Without a
     * [LocalFileDurationReader] a local book has no probe target and the call
     * is a no-op, exactly as it was.
     *
     * @return how many chapters received a duration.
     */
    suspend fun probeBookNow(bookId: String): Int = withContext(Dispatchers.IO) {
        if (!targetedInFlight.add(bookId)) return@withContext 0
        try {
            val book = dao.getAllAudiobooksOnce().firstOrNull { it.id == bookId }
                ?: return@withContext 0
            probeBook(book)
        } catch (e: Exception) {
            Log.w(TAG, "Targeted duration probe failed for $bookId", e)
            0
        } finally {
            targetedInFlight.remove(bookId)
        }
    }

    /**
     * @return how many chapters received a duration this pass.
     */
    suspend fun probeUnknownChapters(
        batchLimit: Int = DEFAULT_PROBE_BATCH,
        now: () -> Long = System::currentTimeMillis
    ): Int = withContext(Dispatchers.IO) {
        val runAt = now()
        val lastRun = lastProbeRunEpochMs.get()
        if (runAt - lastRun < MIN_PROBE_INTERVAL_MS) return@withContext 0
        // Reserve the pass atomically: a concurrent trigger loses the CAS and
        // backs off, so overlapping passes can never probe the same book twice.
        if (!lastProbeRunEpochMs.compareAndSet(lastRun, runAt)) return@withContext 0

        // Candidates: books with at least one unknown-duration chapter (0 is
        // the unknown placeholder). A streamed book goes to the transport; a
        // local import (#1187) goes to the file reader, and without a reader it
        // has no probe target at all and stays out.
        // #350: newest books first — a freshly imported card must not sit at
        // the tail of the batch behind stale ones.
        val unknownBookIds = dao.getBookIdsWithUnknownChapterDurations().toSet()
        val candidates = dao.getAllAudiobooksOnce()
            .filter { it.id in unknownBookIds && (it.sourceUrl.isNotBlank() || localDurations != null) }
            .sortedByDescending { it.createdAt }
            .take(batchLimit.coerceAtLeast(1))

        var probed = 0
        for (book in candidates) {
            try {
                probed += probeBook(book)
            } catch (e: Exception) {
                Log.w("ChapterDurationProbe", "Chapter duration probe failed for ${book.id}", e)
            }
        }
        probed
    }

    /** @return how many chapters of one book received a duration. */
    private suspend fun probeBook(book: BookRow): Int {
        // #350: the book's unknown tracks probe with bounded parallelism
        // (a semaphore caps the in-flight probes) instead of one by one — a
        // long book converges in minutes, not a quarter of an hour. Each
        // launch stays failure-isolated: an unexpected throw kills only its
        // own probe, never the sibling chapters or the sweep.
        // #1187 — a local import is measured from its own files; everything
        // else goes to the transport. Which one is the book's, not the
        // chapter's, decision: a local import's tracks are all files.
        val local = book.sourceUrl.isBlank()
        val pairsToProbe = playableChapters(book.id).filter { pair ->
            // #812 — не лише «невідома», а й «підозріло коротка».
            //
            // Раніше критерій був `durationSeconds <= 0L`, тобто хибна, але
            // ненульова тривалість вважалася «вже відомою» — і 52-секундна
            // врізка, яку колись виміряли як тривалість розділу, лишалася
            // назавжди. Проба нижче пише виміряне БЕЗУМОВНО, тож справді
            // короткий розділ просто отримає своє справжнє значення.
            val track = pair.track ?: return@filter false // no stream for this chapter
            val unknownOrSuspect =
                pair.chapter.durationSeconds <= 0L ||
                    pair.chapter.durationSeconds <
                    com.slukhayka.audiobooks.data.metadata.DurationSanity.MIN_SHARED_SECONDS
            unknownOrSuspect && if (local) {
                localDurations != null && localAddressOf(track).isNotBlank()
            } else {
                track.url.isNotBlank() // no probe target
            }
        }
        val limiter = Semaphore(DEFAULT_PROBE_CONCURRENCY)
        val probed = AtomicInteger(0)
        coroutineScope {
            for (pair in pairsToProbe) {
                val track = requireNotNull(pair.track)
                launch {
                    try {
                        limiter.withPermit {
                            val durationSeconds = if (local) {
                                // #1187 — the file's own container, never a
                                // guess from its size: the byte arithmetic
                                // belongs to streams, where a CBR frame header
                                // proves the bitrate.
                                localDurations?.durationSeconds(localAddressOf(track))
                                    ?: return@withPermit
                            } else {
                                // #516 — the source identity rides the probe so
                                // the per-source header seam (Referer-gated
                                // CDNs) applies.
                                val sourceId = pair.sourceId
                                    ?: com.slukhayka.audiobooks.data.source.sourceIdForUrl(book.sourceUrl)
                                val result = prober.probe(sourceId, track.url) ?: return@withPermit
                                result.contentLength * 8L / (result.frame.bitrateKbps * 1000L)
                            }
                            if (durationSeconds <= 0L) return@withPermit
                            dao.updateChapterDuration(pair.chapter.id, durationSeconds)
                            probed.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Chapter probe failed for ${pair.chapter.id}", e)
                    }
                }
            }
        }
        val probedCount = probed.get()
        // Book total: only when it was unknown (0) and EVERY chapter is now
        // known — a site-provided total is never overwritten.
        if (book.totalDurationSeconds == 0L) {
            val chapters = dao.getChaptersListForBook(book.id)
            if (chapters.isNotEmpty() && chapters.all { it.durationSeconds > 0L }) {
                val total = chapters.sumOf { it.durationSeconds }
                dao.updateBookStats(book.id, chapters.size, total)
                // Spec-30 T4 (#219): a book total derived from probed chapters
                // contributes to the shared base (sanity-gated) — the most
                // expensive derivation, so the one that must never repeat.
                // #1187 — except for a LOCAL total: it is this device's
                // measurement of its own copied files, and the EditionId it
                // would be filed under carries the local book id, so no other
                // listener could ever read it back. Streams still contribute.
                if (book.sourceUrl.isNotBlank() && DurationSanity.isPlausible(total)) {
                    runCatching {
                        sharedStore?.putDuration(
                            editionId = EditionId.forBook(book.mergeKey ?: "", book.id, book.narrator),
                            durationSeconds = total,
                            provenance = DurationProvenance(
                                source = sourceIdForUrl(book.sourceUrl),
                                derivedAt = System.currentTimeMillis(),
                                method = DurationProvenance.METHOD_TECHNICAL_PROBE
                            )
                        )
                    }
                }
            }
        }
        return probedCount
    }

    /**
     * #1187 — the address that plays one LOCAL chapter. ADR-0061: a
     * by-reference track's only address is its SAF document URI (`url`); a copy
     * written by an older version keeps it in
     * [SourceTrackEntity.localFilePath]. A row that carries only one of the two
     * is still measured; a blank answer means there is nothing to open.
     */
    private fun localAddressOf(track: SourceTrackEntity): String =
        track.localFilePath?.takeIf { it.isNotBlank() } ?: track.url

    companion object {
        /**
         * #350 — one pass per 30 minutes (halved from an hour by decision):
         * the sweep is still cheap (a HEAD + a head window per track) and a
         * fresher cadence keeps a growing library converging.
         */
        const val MIN_PROBE_INTERVAL_MS = 30L * 60 * 1000
        /** Aggressive by decision, more so since #350: ≥ 20 books per pass. */
        const val DEFAULT_PROBE_BATCH = 20

        /** #350 — at most this many tracks of ONE book probe concurrently. */
        const val DEFAULT_PROBE_CONCURRENCY = 3

        /** Log tag shared by the pass and the targeted probe. */
        private const val TAG = "ChapterDurationProbe"
    }
}
