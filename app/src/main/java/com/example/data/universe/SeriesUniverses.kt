package com.example.data.universe

import android.util.Log
import com.example.data.db.AudiobookDao
import com.example.data.db.SeriesEntity
import com.example.data.db.UniverseEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Spec-25 (#171) — the lazy series-universe resolution. The source provides
 * a book's series (title + page URL) but nothing about the series' context;
 * this module answers "which universe does this series belong to, and where
 * in it does it sit" from the curated [UniverseList] assets and caches the
 * result in the `series` / `series_members` / `universes` tables — so a
 * resolution never repeats (the cache is the read model, the asset is the
 * matching rule).
 *
 * Resolution is triggered on book-page open / series-page open, background,
 * best-effort: a book/series the curated set does not know, or a failure,
 * contributes NOTHING (no row, no error surfaced). The persistence is
 * idempotent REPLACE upserts, so re-resolution is a no-op by construction.
 *
 * The Wikidata provider (a later ticket) slots behind this same module: it
 * resolves the series the curated set does not know, and the cache + reads
 * below stay unchanged.
 */
class SeriesUniverses(
    private val dao: AudiobookDao,
    private val universes: List<UniverseList>
) {

    /** Resolves one library book's series → universe and caches the result. */
    suspend fun resolveForBook(bookId: String) = withContext(Dispatchers.IO) {
        val book = dao.getAudiobookById(bookId) ?: return@withContext
        val title = book.seriesTitle?.takeIf { it.isNotBlank() } ?: return@withContext
        val match = UniverseMatcher.resolve(universes, title, book.seriesUrl) ?: return@withContext
        persist(match, workId = book.workId ?: book.id, volumeIndex = book.seriesIndex)
    }

    /** Resolves a series page's universe (no book context) and caches it. */
    suspend fun resolveForSeries(title: String, url: String?) = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext
        val match = UniverseMatcher.resolve(universes, title, url) ?: return@withContext
        persist(match, workId = null, volumeIndex = null)
    }

    /**
     * The cached universe context of one series — the read model for the
     * book page's «Всесвіт» line and the series screen's header block. Null
     * when the series is unseeded (nothing cached — the UI stays silent).
     * Matching reuses the same pure [UniverseMatcher], so the claimed title
     * or URL resolves regardless of how the book words it.
     */
    suspend fun contextOf(seriesTitle: String?, seriesUrl: String?): SeriesUniverseContext? =
        withContext(Dispatchers.IO) {
            val title = seriesTitle?.takeIf { it.isNotBlank() } ?: return@withContext null
            val match = UniverseMatcher.resolve(universes, title, seriesUrl) ?: return@withContext null
            val universe = dao.getUniverseById(match.universe.id) ?: return@withContext null
            val ordered = dao.getSeriesInUniverse(match.universe.id)
            val position = ordered.indexOfFirst { it.id == seriesId(match.universe.id, match.position) }
            if (position < 0) return@withContext null
            val precedes = ordered.getOrNull(position - 1)
            val follows = ordered.getOrNull(position + 1)
            SeriesUniverseContext(
                universeName = universe.name,
                seriesTitle = match.series.title,
                position = position + 1,
                totalInUniverse = ordered.size,
                precedes = precedes?.let { SeriesRef(it.title, it.url) },
                follows = follows?.let { SeriesRef(it.title, it.url) }
            )
        }

    /** The cached universe context of one library book (its series' context). */
    suspend fun contextOfBook(bookId: String): SeriesUniverseContext? = withContext(Dispatchers.IO) {
        val book = dao.getAudiobookById(bookId) ?: return@withContext null
        contextOf(book.seriesTitle, book.seriesUrl)
    }

    private suspend fun persist(match: UniverseMatcher.Match, workId: String?, volumeIndex: Int?) {
        try {
            dao.upsertUniverse(UniverseEntity(id = match.universe.id, name = match.universe.name))
            // One row per curated series, deterministically keyed by
            // (universe, position) — re-resolution REPLACEs the same rows.
            match.universe.series.forEachIndexed { index, series ->
                dao.upsertSeries(
                    SeriesEntity(
                        id = seriesId(match.universe.id, index + 1),
                        title = series.title,
                        url = series.urls.firstOrNull(),
                        universeId = match.universe.id,
                        positionInUniverse = index + 1
                    )
                )
            }
            // Book → series membership with the source's volume index; the
            // series-page resolution has no book, so nothing to link.
            if (workId != null && volumeIndex != null && volumeIndex > 0) {
                dao.upsertSeriesMember(
                    com.example.data.db.SeriesMemberEntity(
                        workId = workId,
                        seriesId = seriesId(match.universe.id, match.position),
                        position = volumeIndex
                    )
                )
            }
        } catch (e: Exception) {
            Log.w("SeriesUniverses", "Universe resolution failed for $match", e)
        }
    }

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
