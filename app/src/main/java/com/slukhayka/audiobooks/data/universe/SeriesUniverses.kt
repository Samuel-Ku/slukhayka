package com.slukhayka.audiobooks.data.universe

import android.util.Log
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.CorrectionEntity
import com.slukhayka.audiobooks.data.db.CorrectionKind
import com.slukhayka.audiobooks.data.db.CorrectionOrigin
import com.slukhayka.audiobooks.data.db.SeriesEntity
import com.slukhayka.audiobooks.data.db.UniverseEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spec-25 (#171/#173) — the lazy series-universe resolution. The source
 * provides a book's series (title + page URL) but nothing about the series'
 * context; this module answers "which universe does this series belong to,
 * and where in it does it sit" and caches the result in the `series` /
 * `series_members` / `universes` tables — so a resolution never repeats (the
 * cache is the read model, the matching rule is the provider).
 *
 * Three layers behind one seam, client-first: the curated [UniverseList]
 * assets (local, offline, alias-aware), then the shared [SharedUniverseStore]
 * (spec-26 T5 — Firestore: a resolution another user already wrote back is
 * read here BEFORE Wikidata, mirrored into the Room cache, and never pays
 * for a Wikidata call), then the [SeriesUniverseProvider] fallback
 * (Wikidata, T2) for unseeded series — best-effort and gated on the cache: a

 * work whose membership is fresh is resolved (one resolution per book; the
 * membership carries a [com.slukhayka.audiobooks.data.db.SeriesMemberEntity.resolvedAt]
 * stamp, and spec-26 T7 refreshes stale rows on a TIERED schedule — hot
 * ~7 days for a young series at the chain tail, warm ~30 days for a tail or
 * young one, cold ~180 days (the floor) for the rest — instead of a flat
 * TTL, so Wikidata changes eventually reach every cache). A book/series no
 * layer knows, or a failing layer, contributes NOTHING (no row, no error
 * surfaced). The persistence is idempotent REPLACE upserts, so a
 * re-resolution overwrites the same rows.
 *
 * Resolution is triggered on book-page open / series-page open, background,
 * best-effort.
 */
class SeriesUniverses(
    private val dao: AudiobookDao,
    private val curated: List<UniverseList>,

    private val wikidata: SeriesUniverseProvider? = null,
    // Spec-26 T5: the shared (Firestore) read layer between the Room cache
    // and Wikidata. Null — the layer is absent (no Firebase keys), and the
    // read path is exactly the pre-Firestore one.
    private val sharedStore: SharedUniverseStore? = null,
    // Spec-26 T7 (#181): the tiered refresh rule — how stale a membership
    // must be before it re-resolves (hot ~7d for a young series at the chain
    // tail, warm ~30d for tail-or-young, cold ~180d floor for the rest), so
    // the universe view eventually tracks Wikidata changes instead of
    // persisting forever. Injectable for tests; the flat TTL is gone.
    private val tierTtlMillis: (Boolean, Int?, Int) -> Long = UniverseRefreshTier::tierTtlMillis,
    // Injectable clock so the tier gate is testable.
    private val now: () -> Long = System::currentTimeMillis
) {

    /** Resolves one library book's series → universe and caches the result. */
    suspend fun resolveForBook(bookId: String) = withContext(Dispatchers.IO) {
        val book = dao.getAudiobookById(bookId) ?: return@withContext
        resolveWork(
            workId = book.workId ?: book.id,
            title = book.title,
            author = book.author,
            seriesTitle = book.seriesTitle,
            seriesUrl = book.seriesUrl,
            seriesIndex = book.seriesIndex
        )
    }

    /**
     * Spec-26 T7 (#181) — resolves one WORK's series → universe with no
     * library book: the background refresh pass re-resolves stale
     * memberships straight from the work row. The exact same path as
     * [resolveForBook].
     */
    suspend fun resolveForWork(workId: String) = withContext(Dispatchers.IO) {
        val work = dao.getWorkById(workId) ?: return@withContext
        resolveWork(
            workId = work.id,
            title = work.title,
            author = work.author,
            seriesTitle = work.seriesTitle,
            seriesUrl = work.seriesUrl,
            seriesIndex = work.seriesIndex
        )
    }

    private suspend fun resolveWork(
        workId: String,
        title: String,
        author: String,
        seriesTitle: String?,
        seriesUrl: String?,
        seriesIndex: Int?
    ) {
        val series = seriesTitle?.takeIf { it.isNotBlank() } ?: return
        // Curated first (local, offline, alias-aware).
        val curatedMatch = UniverseMatcher.resolve(curated, series, seriesUrl)
        if (curatedMatch != null) {
            persist(curatedMatch.toResolution(), workId, seriesIndex)
            return
        }

        // The shared (Firestore) layer, then the Wikidata fallback — both
        // gated on the cache: a work whose membership is fresh under its
        // tier (spec-26 T7) is resolved; stale or missing memberships
        // re-resolve, so the universe view eventually refreshes. A shared-
        // store hit is mirrored into the Room cache and never pays for
        // Wikidata (spec-26 T5 AC2); a shared miss or failure (null) falls
        // through silently to Wikidata exactly as before (AC3). A failing
        // re-resolution leaves the stale row in place — the next open (book
        // or pass) retries.
        val currentNow = now()
        val memberships = dao.getSeriesMembersForWork(workId)
        if (memberships.isNotEmpty() && freshUnderTier(memberships, currentNow)) return
        val shared = sharedStore
        if (shared != null) {
            val sharedResolution = shared.getResolution(workId)
            if (sharedResolution != null) {
                persist(sharedResolution, workId, seriesIndex)
                return
            }
        }
        val provider = wikidata ?: return
        val resolution = provider.resolve(title, author) ?: return
        persist(resolution, workId, seriesIndex)
        writeBack(workId, resolution)
    }

    /**
     * Spec-26 T8 (#182) — the import event trigger: a NEW book whose series
     * belongs to a CACHED universe immediately re-validates that universe's
     * chain through the provider (cheap — one resolve), so a new series or a
     * reordered chain lands for everyone: the fresh resolution REPLACEs the
     * cached rows (precedes/follows and new series appear) and writes back
     * to the shared base with provenance. An unknown series, a missing
     * provider, or a failing resolve is a SILENT no-op — the cached chain
     * stays exactly as it is.
     */
    suspend fun validateChainFor(workId: String) = withContext(Dispatchers.IO) {
        val work = dao.getWorkById(workId) ?: return@withContext
        val seriesTitle = work.seriesTitle?.takeIf { it.isNotBlank() } ?: return@withContext
        // The trigger fires only for a series that belongs to a CACHED
        // universe — the cached-series row is the "known universe" signal
        // (AC1); anything else is a silent no-op (AC3).
        val cached = cachedSeriesRow(seriesTitle, work.seriesUrl) ?: return@withContext
        if (cached.universeId == null) return@withContext
        val provider = wikidata ?: return@withContext
        val resolution = provider.resolve(work.title, work.author) ?: return@withContext
        persist(resolution, workId, work.seriesIndex)
        writeBack(workId, resolution)
    }

    /**
     * Spec-26 T9 (#183) — the «wrong universe» feedback channel. The user's
     * complaint pins the work as reported (a USER_MADE WRONG_UNIVERSE
     * correction whose `value` carries the cached universe id the user says
     * is wrong), the resolution hides, and the work re-resolves immediately
     * to VERIFY the complaint:
     *
     *  - **mismatch** — the re-resolution yields a DIFFERENT universe: the
     *    fresh chain replaces the cache AND the shared-base document (T6
     *    write-back), so the correction reaches every user;
     *  - **match** — the re-resolution agrees with the cached universe: the
     *    complaint was a false positive, nothing changes;
     *  - **failure** — the report stays (resolution hidden) and the next book
     *    open / refresh pass retries.
     *
     * Either verdict clears the complaint, so a mistaken report never hides a
     * correct universe forever. Best-effort and silent throughout.
     */
    suspend fun reportWrongUniverseForBook(bookId: String) = withContext(Dispatchers.IO) {
        val book = dao.getAudiobookById(bookId) ?: return@withContext
        reportWrongUniverse(book.workId ?: book.id)
    }

    suspend fun reportWrongUniverse(workId: String) = withContext(Dispatchers.IO) {
        val work = dao.getWorkById(workId) ?: return@withContext
        // The universe the user says is wrong: the cached membership's
        // universe (the affordance only renders when one is cached).
        val allSeries = dao.getAllSeries()
        val reportedUniverseId = dao.getSeriesMembersForWork(workId)
            .mapNotNull { member -> allSeries.firstOrNull { it.id == member.seriesId }?.universeId }
            .firstOrNull()
        // Pin the complaint: reported → hidden (the contextOfBook gate).
        dao.upsertCorrection(
            CorrectionEntity(
                mergeKey = workId,
                kind = CorrectionKind.WRONG_UNIVERSE,
                value = reportedUniverseId ?: "",
                origin = CorrectionOrigin.USER_MADE,
                updatedAt = now()
            )
        )
        // Re-resolve to verify. A failing resolve keeps the report — the
        // next open retries; there is never a wrong verdict from a failure.
        val provider = wikidata ?: return@withContext
        val resolution = provider.resolve(work.title, work.author) ?: return@withContext
        val sameUniverse = reportedUniverseId != null && resolution.universe.id == reportedUniverseId
        if (!sameUniverse) {
            persist(resolution, workId, work.seriesIndex)
            writeBack(workId, resolution)
        }
        dao.deleteCorrection(workId, CorrectionKind.WRONG_UNIVERSE)
    }

    /** True while a «wrong universe» complaint pins this work (hidden). */
    private suspend fun isWrongUniverseReported(workId: String): Boolean =
        dao.getCorrectionsForMergeKey(workId).any { it.kind == CorrectionKind.WRONG_UNIVERSE }

    /**
     * Spec-26 T6 (#180) — the shared-base write-back of a fresh resolution.
     * Best-effort and silent — the local cache persisted first, so a failing
     * write never touches it (AC5). Idempotent: the same workId key replaces.
     * The provider returns only P50-author-verified works, hence
     * authorVerified = true.
     */
    private suspend fun writeBack(workId: String, resolution: UniverseResolution) {
        try {
            sharedStore?.putResolution(
                workId,
                resolution,
                ResolutionProvenance(
                    source = ResolutionProvenance.SOURCE_WIKIDATA,
                    authorVerified = true,
                    resolvedAt = now()
                )
            )
        } catch (e: Exception) {
            Log.w("SeriesUniverses", "Universe write-back failed for $workId", e)
        }
    }

    /** Resolves a series page's universe (no book context) and caches it. */
    suspend fun resolveForSeries(title: String, url: String?) = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext
        // The series page has no book to search Wikidata by — curated only.
        val match = UniverseMatcher.resolve(curated, title, url) ?: return@withContext
        persist(match.toResolution(), workId = null, volumeIndex = null)
    }

    /**
     * The cached universe context of one series — the read model for the
     * book page's «Всесвіт» line and the series screen's header block. Null
     * when the series is unresolved (nothing cached — the UI stays silent).
     * The cache is the source of truth: the curated match (asset, alias-
     * aware) first, then the cached series rows (URL first, normalized title
     * second) — so Wikidata-resolved universes surface exactly like curated
     * ones.
     */
    suspend fun contextOf(seriesTitle: String?, seriesUrl: String?): SeriesUniverseContext? =
        withContext(Dispatchers.IO) {
            val title = seriesTitle?.takeIf { it.isNotBlank() } ?: return@withContext null
            val series = curatedSeriesRow(title, seriesUrl) ?: cachedSeriesRow(title, seriesUrl)
                ?: return@withContext null
            contextOfSeriesRow(series)
        }

    /**
     * The cached universe context of one library book (its series' context).
     * The book → series membership is the exact link (the source's volume
     * index) — preferred over the fuzzy title read, which cannot match a
     * Wikidata-resolved series label to the source's claimed series title.
     * Falls back to the title/URL read when the book carries no membership.
     */
    suspend fun contextOfBook(bookId: String): SeriesUniverseContext? = withContext(Dispatchers.IO) {
        val book = dao.getAudiobookById(bookId) ?: return@withContext null
        val workId = book.workId ?: book.id
        // Spec-26 T9 (#183): a reported «wrong universe» hides the resolution
        // until the re-resolution verdict — mismatch replaces it, match
        // clears the complaint (the cached one was right).
        if (isWrongUniverseReported(workId)) return@withContext null
        val memberSeriesId = dao.getSeriesMembersForWork(workId).firstOrNull()?.seriesId
        if (memberSeriesId != null) {
            val series = dao.getAllSeries().firstOrNull { it.id == memberSeriesId }
                ?: return@withContext null
            return@withContext contextOfSeriesRow(series)
        }
        contextOf(book.seriesTitle, book.seriesUrl)
    }

    /** The context of one cached series row: universe, position, neighbors. */
    private suspend fun contextOfSeriesRow(series: SeriesEntity): SeriesUniverseContext? {
        val universeId = series.universeId ?: return null
        val universe = dao.getUniverseById(universeId) ?: return null
        val ordered = dao.getSeriesInUniverse(universeId)
        val position = ordered.indexOfFirst { it.id == series.id }
        if (position < 0) return null
        val precedes = ordered.getOrNull(position - 1)
        val follows = ordered.getOrNull(position + 1)
        return SeriesUniverseContext(
            universeName = universe.name,
            seriesTitle = series.title,
            position = position + 1,
            totalInUniverse = ordered.size,
            precedes = precedes?.let { SeriesRef(it.title, it.url) },
            follows = follows?.let { SeriesRef(it.title, it.url) }
        )
    }

    /** The cached series row of a curated match (alias-aware, via the asset). */
    private suspend fun curatedSeriesRow(title: String, url: String?): SeriesEntity? {
        val match = UniverseMatcher.resolve(curated, title, url) ?: return null
        return dao.getSeriesInUniverse(match.universe.id)
            .firstOrNull { it.id == seriesId(match.universe.id, match.position) }
    }

    /** The cached series row by URL first, normalized title second — the
     *  Wikidata-resolved rows (and curated rows the claimed title spells
     *  slightly differently) land here. */
    private suspend fun cachedSeriesRow(title: String, url: String?): SeriesEntity? {
        val all = dao.getAllSeries()
        val normalizedUrl = url?.takeIf { it.isNotBlank() }?.let { UniverseMatcher.normalizeUrl(it) }
        normalizedUrl?.let { u ->
            all.firstOrNull { series ->
                series.url?.let { UniverseMatcher.normalizeUrl(it) == u } == true
            }?.let { return it }
        }
        val normalizedTitle = UniverseMatcher.normalizeSeriesTitle(title)
        return all.firstOrNull { UniverseMatcher.normalizeSeriesTitle(it.title) == normalizedTitle }
    }

    /**
     * Spec-26 T7 — true when ANY of the work's memberships is fresh under
     * its tier. The tier comes from the cached series row: the chain-tail
     * signal (the series sits at its universe's end — a continuation may
     * appear) and the series' P577 publication year (captured at
     * resolution). A tail + young series refreshes fastest (hot ~7 days), a
     * cold one only after the 180-day floor — so even the coldest cached
     * membership eventually re-resolves and spreads Wikidata fixes.
     */
    private suspend fun freshUnderTier(
        memberships: List<com.slukhayka.audiobooks.data.db.SeriesMemberEntity>,
        currentNow: Long
    ): Boolean {
        val allSeries = dao.getAllSeries()
        val byId = allSeries.associateBy { it.id }
        val universeSizes = allSeries
            .filter { it.universeId != null }
            .groupBy { it.universeId!! }
            .mapValues { it.value.size }
        val year = UniverseRefreshTier.epochYear(currentNow)
        return memberships.any { membership ->
            val series = byId[membership.seriesId]
            val isTail = series?.universeId?.let { universeId ->
                series.positionInUniverse != null &&
                    series.positionInUniverse == universeSizes[universeId]
            } ?: false
            (membership.resolvedAt ?: 0L) > currentNow - tierTtlMillis(isTail, series?.publicationYear, year)
        }
    }

    private suspend fun persist(resolution: UniverseResolution, workId: String?, volumeIndex: Int?) {
        try {
            dao.upsertUniverse(UniverseEntity(id = resolution.universe.id, name = resolution.universe.name))
            // One row per series, deterministically keyed by (universe,
            // position) — re-resolution REPLACEs the same rows.
            resolution.universe.series.forEachIndexed { index, series ->
                dao.upsertSeries(
                    SeriesEntity(
                        id = seriesId(resolution.universe.id, index + 1),
                        title = series.title,
                        url = series.urls.firstOrNull(),
                        universeId = resolution.universe.id,
                        positionInUniverse = index + 1,
                        // Spec-26 T7: the P577 year captured at resolution —
                        // the tier rule's age signal (null for curated series).
                        publicationYear = series.publicationYear
                    )
                )
            }
            // Book → series membership with the source's volume index; the
            // series-page resolution has no book, so nothing to link. The
            // membership reflects the CURRENT resolution: a re-resolution
            // that moves the book to a different series replaces the old
            // membership (a corrected universe never leaves the stale one
            // behind — spec-26 T9). The resolvedAt stamp drives the TTL gate
            // on the Wikidata fallback.
            if (workId != null && volumeIndex != null && volumeIndex > 0) {
                dao.deleteSeriesMembersForWork(workId)
                dao.upsertSeriesMember(
                    com.slukhayka.audiobooks.data.db.SeriesMemberEntity(
                        workId = workId,
                        seriesId = seriesId(resolution.universe.id, resolution.position),
                        position = volumeIndex,
                        resolvedAt = now()
                    )
                )
            }
        } catch (e: Exception) {
            Log.w("SeriesUniverses", "Universe resolution failed for ${resolution.universe.id}", e)
        }
    }

    private fun UniverseMatcher.Match.toResolution() = UniverseResolution(universe, series, position)

    private fun seriesId(universeId: String, position: Int): String = "$universeId:$position"
}

/** A tappable neighbor series of the universe («Передує» / «Продовжує»). */
data class SeriesRef(
    val title: String,
    val url: String? = null
)

/**
 * The resolved universe context of one series — what the book page and the
 * series screen render. `position`/`totalInUniverse` are 1-based; the
 * precedes/follows neighbors come from the universe's ordered series.
 */
data class SeriesUniverseContext(
    val universeName: String,
    val seriesTitle: String,
    val position: Int,
    val totalInUniverse: Int,
    val precedes: SeriesRef? = null,
    val follows: SeriesRef? = null
)
