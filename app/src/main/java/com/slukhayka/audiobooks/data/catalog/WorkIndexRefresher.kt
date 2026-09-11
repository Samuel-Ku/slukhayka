package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceRequestClass

/**
 * Spec-49 follow-up (2026-09-10) — the Work index refresher, layer B: every
 * direct source contributes its book URLs to one local [CatalogWorkIndex],
 * refreshed under the catalog TTL through the Source Request Gate as
 * BACKGROUND traffic.
 *
 * Two carriers:
 * - **Sitemaps** (measured 2026-09-10): audiobook.co.ua publishes 2 193 book
 *   URLs over three post-sitemaps; chytaylo.com.ua publishes `/books/<slug>`
 *   in one. URL slugs only, no page fetches; matched by [com.slukhayka.audiobooks.data.merge.SlugMatch].
 * - **Catalogue cards** through the adapters' own `fetchCatalog` for sources
 *   that expose no book sitemap (knigi-online, sound-books, sluhayua): real
 *   title/author pairs arrive, so the entry carries the exact [MergeKey] and
 *   matches without transliteration heuristics.
 *
 * The built index is persisted through [store] so a launch inside the TTL
 * serves lookups without a single sitemap request. Best-effort by contract:
 * a failing carrier contributes nothing and never touches the previous index.
 */
class WorkIndexRefresher(
    private val fetcher: HttpFetcher,
    private val store: WorkIndexStore? = null,
    /** Catalogue-card carriers keyed by source id (their adapters' fetchCatalog). */
    private val cardSources: Map<String, CardSource> = emptyMap(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = FeedSnapshotPolicy.CATALOG_TTL_MS,
    private val cardLimit: Int = DEFAULT_CARD_LIMIT
) {

    @Volatile
    private var current: CatalogWorkIndex? = null

    @Volatile
    private var refreshedAtMs: Long = 0L

    /** Guards the one-time store read across concurrent refreshes. */
    private val storeRead = java.util.concurrent.atomic.AtomicBoolean(false)

    fun lookup(title: String, author: String): CatalogIndexEntry? = current?.lookup(title, author)

    /**
     * Refreshes when the in-memory or persisted index is absent or older
     * than the TTL (or when [force]). Returns the served index, or null when
     * nothing was ever built.
     */
    suspend fun refreshIfStale(force: Boolean = false): CatalogWorkIndex? {
        val existing = current
        if (!force && existing != null && clock() - refreshedAtMs < ttlMillis) return existing

        if (storeRead.compareAndSet(false, true)) {
            store?.load()?.let { persisted ->
                current = CatalogWorkIndex(persisted.entries)
                refreshedAtMs = persisted.refreshedAtMs
            }
        }
        val persistedNow = current
        if (!force && persistedNow != null && clock() - refreshedAtMs < ttlMillis) {
            return persistedNow
        }

        val sitemap = sitemapEntries()
        val cards = cardEntries()
        val entries = buildList {
            addAll(sitemap)
            addAll(cards)
        }.distinctBy { it.sourceId to it.url }

        if (entries.isEmpty()) return current
        val built = CatalogWorkIndex(entries)
        val builtAt = clock()
        current = built
        refreshedAtMs = builtAt
        store?.save(PersistedWorkIndex(entries, builtAt))
        return built
    }

    private suspend fun sitemapEntries(): List<CatalogIndexEntry> = buildList {
        for ((sourceId, spec) in SITEMAP_SPECS) {
            for (sitemapUrl in spec.sitemapUrls) {
                val xml = fetcher.getText(
                    sitemapUrl,
                    emptyMap(),
                    SourceRequestClass.BACKGROUND,
                    ttlMillis
                )
                if (xml.isBlank()) continue
                for (match in LOC.findAll(xml)) {
                    val loc = match.groupValues[1].trim()
                    val path = loc.substringBefore('?').trimEnd('/')
                    if (!spec.accept(path)) continue
                    val slug = path.substringAfterLast('/')
                    if (slug.isBlank()) continue
                    add(CatalogIndexEntry(sourceId = sourceId, url = loc, slug = slug))
                }
            }
        }
    }

    /** A source's catalogue-card enumeration — its adapter's `fetchCatalog`. */
    fun interface CardSource {
        suspend fun catalog(limit: Int): List<SourceBook>
    }

    private suspend fun cardEntries(): List<CatalogIndexEntry> = buildList {
        for ((sourceId, fetchCatalog) in cardSources) {
            val books = runCatching { fetchCatalog.catalog(cardLimit) }
                .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else emptyList() }
            for (book in books) {
                if (book.url.isBlank()) continue
                add(
                    CatalogIndexEntry(
                        sourceId = sourceId,
                        url = book.url,
                        slug = "",
                        mergeKey = MergeKey.keyFor(book.title, book.author)
                    )
                )
            }
        }
    }

    companion object {

        /** Bounded: the index is a discovery shortcut, not a full crawl. */
        const val DEFAULT_CARD_LIMIT = 100

        private val LOC = Regex("<loc>\\s*([^<\\s]+)\\s*</loc>")

        /** One source's sitemap URLs plus the book-URL filter. */
        data class SitemapSpec(
            val sitemapUrls: List<String>,
            val accept: (path: String) -> Boolean
        )

        /**
         * The measured carriers: co.ua posts are all book pages; chytaylo
         * keeps its books under `/books/` (pages, authors and categories in
         * the same sitemap).
         */
        val SITEMAP_SPECS: Map<String, SitemapSpec> = mapOf(
            "audiobookcoua" to SitemapSpec(
                sitemapUrls = listOf(
                    "https://audiobook.co.ua/post-sitemap.xml",
                    "https://audiobook.co.ua/post-sitemap2.xml",
                    "https://audiobook.co.ua/post-sitemap3.xml"
                )
            ) { path -> path.startsWith("https://audiobook.co.ua/") },
            "chytaylo" to SitemapSpec(
                sitemapUrls = listOf("https://chytaylo.com.ua/sitemap.xml")
            ) { path -> path.contains("/books/") }
        )
    }
}
