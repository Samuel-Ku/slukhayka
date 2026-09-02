package com.slukhayka.audiobooks.data.source

import com.slukhayka.audiobooks.data.privacy.PacingPolicy
import kotlinx.coroutines.delay

/**
 * Spec #462 ID4 (#466) — the feed cursor over a paged source feed (today:
 * sluhayua's `/find/allcards?sort=time&order=desc&page=N` through
 * [SluhayuaAdapter.fetchNewPage]).
 *
 * One [fetchNext] call pulls successive pages until the configured book
 * limit ([maxBooks]), an empty page (the feed's honest end) or the
 * [maxPages] safety bound. NOTHING calls it on its own: pagination is
 * initiated only by a user action (scroll / pull-to-refresh) — never a
 * background crawl (spec #462, Implementation Decision 4).
 *
 * Every page fetch rides the caller's lambda — the adapter's [HttpFetcher],
 * i.e. the ONE shared transport on the listener's privacy route
 * (TransportPrivacy, spec-38) — and the pauses BETWEEN pages come from
 * [PacingPolicy], so a multi-page pull keeps the human rhythm (spec-38 T5:
 * bulk fetching never looks like scraping).
 *
 * Pure JVM and deterministic by injection: tests pass a fake fetch lambda,
 * a seeded policy and a no-op pause (spec-38 Testing Decisions).
 */
class FeedCursor(
    private val fetchPage: suspend (page: Int) -> List<SourceBook>,
    private val maxBooks: Int = DEFAULT_MAX_BOOKS,
    private val maxPages: Int = DEFAULT_MAX_PAGES,
    private val pacing: PacingPolicy = PacingPolicy(),
    private val pauseMillis: suspend (Long) -> Unit = { delay(it) }
) {

    /** Pages consumed so far; [nextPage] is the one the next pull starts at. */
    var fetchedPages: Int = 0
        private set

    /** The feed's honest end: an empty page came back — nothing more to pull. */
    var isExhausted: Boolean = false
        private set

    /** The next page number [fetchNext] will request. */
    val nextPage: Int get() = fetchedPages + 1

    /**
     * Pulls successive pages — USER-initiated only (see the class doc) —
     * until [maxBooks] books accumulate, a page comes back empty or
     * [maxPages] pages are consumed. Returns the newly pulled books only;
     * merging/dedup stays with the caller.
     */
    suspend fun fetchNext(): List<SourceBook> {
        if (isExhausted) return emptyList()
        val books = mutableListOf<SourceBook>()
        while (books.size < maxBooks && fetchedPages < maxPages) {
            if (fetchedPages > 0) pauseMillis(pacing.nextPauseMillis())
            val page = fetchPage(nextPage)
            fetchedPages = nextPage
            if (page.isEmpty()) {
                isExhausted = true
                break
            }
            books += page.take(maxBooks - books.size)
        }
        return books
    }

    companion object {
        /** Default book ceiling of one user-initiated pull (configurable). */
        const val DEFAULT_MAX_BOOKS: Int = 60

        /** Safety bound on pages per pull — never a background crawl. */
        const val DEFAULT_MAX_PAGES: Int = 5
    }
}
