package com.slukhayka.audiobooks.data.source

/**
 * One request kind an adapter performs (ADR-0040). The adapter declares a
 * [SourceRequestProfile] per endpoint; the politeness seam reads the
 * declaration — features never classify requests and never know the numbers.
 */
enum class SourceEndpoint {
    /** [SourceAdapter.search] — a listener-initiated query. */
    SEARCH,

    /** [SourceAdapter.fetchNew] — the «Новинки» feed refresh. */
    NEW_FEED,

    /** [SourceAdapter.fetchCatalog] — sitemap/listing enumeration (pages included). */
    CATALOG,

    /** [SourceAdapter.fetchBookPage] — a book-page resolve (import/play/prepare). */
    BOOK_PAGE,

    /** Cover-image fetches — cache-first, the lowest queue class (ADR-0039). */
    COVER
}

/**
 * The politeness profile one adapter endpoint declares (ADR-0040): the
 * request class and the fresh-cache TTL the gate applies. The numbers are
 * the ADR's starting settings, not measured truths — calibration is a
 * separate pass after delivery.
 */
data class SourceRequestProfile(
    val requestClass: SourceRequestClass,
    val cacheTtlMillis: Long
) {
    companion object {
        /** Search answers stay fresh for 24 h; a fresh hit costs zero requests. */
        const val SEARCH_TTL_MS = 24 * 60 * 60 * 1000L

        /** Новинки feeds stay fresh for 6 h (FeedSnapshotPolicy parity). */
        const val NEW_FEED_TTL_MS = 6 * 60 * 60 * 1000L

        /** Catalogue enumeration stays fresh for 24 h. */
        const val CATALOG_TTL_MS = 24 * 60 * 60 * 1000L
    }
}