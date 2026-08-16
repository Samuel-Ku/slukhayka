package com.example.data.universe

import android.util.Log
import com.example.data.db.AudiobookDao
import com.example.data.db.SeriesEntity
import com.example.data.db.UniverseEntity
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
 * membership carries a [com.example.data.db.SeriesMemberEntity.resolvedAt]
 * stamp so stale rows re-resolve after the TTL instead of persisting
 * forever). A book/series no layer knows, or a failing layer, contributes
 * NOTHING (no row, no error surfaced). The persistence is idempotent REPLACE
 * upserts, so a re-resolution overwrites the same rows.
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
    // Spec-25: a cached Wikidata resolution is re-resolved once it is older
    // than the TTL, so the universe view eventually tracks Wikidata changes
    // instead of persisting forever. Curated resolutions are exempt by
    // construction — the asset is local and re-persists on every book open.
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    // Injectable clock so the TTL gate is testable.
    private val now: () -> Long = System::currentTimeMillis
) {

    /** Resolves one library book's series → universe and caches the result. */
    suspend fun resolveForBook(bookId: String) = withContext(Dispatchers.IO) {
        val book = dao.getAudiobookById(bookId) ?: return@withContext
        val seriesTitle = book.seriesTitle?.takeIf { it.isNotBlank() } ?: return@withContext
        val workId = book.workId ?: book.id
        // Curated first (local, offline, alias-aware).
        val curatedMatch = UniverseMatcher.resolve(curated, seriesTitle, book.seriesUrl)
        if (curatedMatch != null) {
            persist(curatedMatch.toResolution(), workId, book.seriesIndex)
            return@withContext
        }
        // The shared (Firestore) layer, then the Wikidata fallback — both
        // gated on the cache: a work whose membership is fresh (resolved
        // within the TTL) is resolved; stale or missing memberships re-resolve,
        // so the universe view eventually refreshes. A shared-store hit is
        // mirrored into the Room cache and never pays for Wikidata (spec-26
        // T5 AC2); a shared miss or failure (null) falls through silently to
        // Wikidata exactly as before (AC3). A failing re-resolution leaves
        // the stale row in place — the next book open retries.
        val cutoff = now() - ttlMillis
        if (dao.getSeriesMembersForWork(workId).any { (it.resolvedAt ?: 0L) > cutoff }) return@withContext
        val shared = sharedStore
        if (shared != null) {
            val sharedResolution = shared.getResolution(workId)
            if (sharedResolution != null) {
                persist(sharedResolution, workId, book.seriesIndex)
                return@withContext
            }
        }
        val provider = wikidata ?: return@withContext
        val resolution = provider.resolve(book.title, book.author) ?: return@withContext
        persist(resolution, workId, book.seriesIndex)
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
                        positionInUniverse = index + 1
                    )
                )
            }
            // Book → series membership with the source's volume index; the
            // series-page resolution has no book, so nothing to link. The
            // resolvedAt stamp drives the TTL gate on the Wikidata fallback.
            if (workId != null && volumeIndex != null && volumeIndex > 0) {
                dao.upsertSeriesMember(
                    com.example.data.db.SeriesMemberEntity(
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

/** Default TTL of a cached Wikidata universe resolution: 30 days. */
private const val DEFAULT_TTL_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

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
