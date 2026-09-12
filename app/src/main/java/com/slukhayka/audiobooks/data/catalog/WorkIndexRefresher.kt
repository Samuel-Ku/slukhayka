package com.slukhayka.audiobooks.data.catalog

import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.source.HttpFetcher
import com.slukhayka.audiobooks.data.source.SourceBook
import com.slukhayka.audiobooks.data.source.SourceCookieProvider
import com.slukhayka.audiobooks.data.source.SourceRequestClass
import com.slukhayka.audiobooks.data.source.cookieHeadersFor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spec-49 follow-up (2026-09-10) — the Work index refresher, layer B: every
 * enumerated source contributes its book URLs to one local [CatalogWorkIndex],
 * refreshed under the catalog TTL through the Source Request Gate as
 * BACKGROUND traffic.
 *
 * Two carriers:
 * - **Sitemaps** (measured 2026-09-10 / 2026-09-11): audiobook.co.ua publishes
 *   2 193 book URLs over three post-sitemaps; chytaylo.com.ua publishes
 *   `/books/<slug>` in one; sluhay.com publishes 5 826 book URLs in
 *   `news_pages.xml`. URL slugs only, no page fetches; matched by
 *   [com.slukhayka.audiobooks.data.merge.SlugMatch]. A session-bound sitemap
 *   reads the live WebView cookie through [cookieProvider] and contributes
 *   nothing without it.
 * - **Catalogue cards** through the adapters' own `fetchCatalog` for sources
 *   that expose no book sitemap (knigi-online, sound-books, sluhayua): real
 *   title/author pairs arrive, so the entry carries the exact [MergeKey] and
 *   matches without transliteration heuristics.
 *
 * The built index is persisted through [store] so a launch inside the TTL
 * serves lookups without a single sitemap request. #526 — the sitemap lane's
 * TTL is a WEEK ([SitemapPolicy.SITEMAP_TTL_MS]): a URL inventory moves
 * slowly, and the parsed document is bounded, canonicalized and
 * host-allowlisted by [SitemapParser]. Best-effort by contract: a failing,
 * malformed or oversized carrier contributes nothing and never touches the
 * previous index; one refresh runs at a time.
 */
class WorkIndexRefresher(
    private val fetcher: HttpFetcher,
    private val store: WorkIndexStore? = null,
    /** Catalogue-card carriers keyed by source id (their adapters' fetchCatalog). */
    private val cardSources: Map<String, CardSource> = emptyMap(),
    /**
     * Spec-42 #427 — the ONE shared host-aware session cookie provider. A
     * session-bound sitemap (sluhay.com sits behind Cloudflare) answers only
     * with the live WebView session's cookies; without them the fetch is a
     * best-effort miss and the carrier contributes nothing. Direct-source
     * sitemaps read "" here and stay cookie-free. Per grill 2026-09-11
     * (#725) this fetch is ADR-0039 §8 session traffic: at most one request
     * per source per catalog TTL, host-scoped, no tokens.
     */
    private val cookieProvider: SourceCookieProvider = NO_COOKIES,
    /** #526 — persisted ETag/Last-Modified validators of the sitemap lane. */
    private val validatorStore: SitemapValidatorStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = SitemapPolicy.SITEMAP_TTL_MS,
    private val cardLimit: Int = DEFAULT_CARD_LIMIT
) {

    @Volatile
    private var current: CatalogWorkIndex? = null

    @Volatile
    private var refreshedAtMs: Long = 0L

    /** Guards the one-time store read across concurrent refreshes. */
    private val storeRead = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Single-flight: concurrent refreshes never double-fetch a sitemap. */
    private val refreshMutex = Mutex()

    fun lookup(title: String, author: String): CatalogIndexEntry? = current?.lookup(title, author)

    /**
     * #526 — the bounded candidate list of one explicit action: at most three
     * canonical URLs, all of the SAME source as the best match, exact
     * MergeKey hits first. Reading the index makes no request at all.
     */
    fun candidates(
        title: String,
        author: String,
        limit: Int = CatalogWorkIndex.MAX_CANDIDATES
    ): List<CatalogIndexEntry> = current?.candidates(title, author, limit).orEmpty()

    /**
     * Refreshes when the in-memory or persisted index is absent or older
     * than the TTL (or when [force]). Returns the served index, or null when
     * nothing was ever built.
     */
    suspend fun refreshIfStale(force: Boolean = false): CatalogWorkIndex? {
        val existing = current
        if (!force && existing != null && clock() - refreshedAtMs < ttlMillis) return existing

        return refreshMutex.withLock {
            val inside = current
            if (!force && inside != null && clock() - refreshedAtMs < ttlMillis) return@withLock inside

            if (storeRead.compareAndSet(false, true)) {
                store?.load()?.let { persisted ->
                    current = CatalogWorkIndex(persisted.entries)
                    refreshedAtMs = persisted.refreshedAtMs
                }
            }
            val persistedNow = current
            if (!force && persistedNow != null && clock() - refreshedAtMs < ttlMillis) {
                return@withLock persistedNow
            }

            val sitemap = sitemapEntries()
            val cards = cardEntries()
            val entries = buildList {
                addAll(sitemap.entries)
                addAll(cards)
            }.distinctBy { it.sourceId to it.url }

            // #526 — every sitemap answered 304: the persisted index still
            // describes every carrier, so extend its life instead of dropping it.
            if (entries.isEmpty() && sitemap.allNotModified && current != null) {
                val extended = clock()
                refreshedAtMs = extended
                store?.save(PersistedWorkIndex(current!!.allEntriesSnapshot, extended))
                return@withLock current
            }
            if (entries.isEmpty()) return@withLock current
            val built = CatalogWorkIndex(entries)
            val builtAt = clock()
            current = built
            refreshedAtMs = builtAt
            store?.save(PersistedWorkIndex(entries, builtAt))
            built
        }
    }

    /** One sitemap sweep: the entries plus whether every carrier answered 304. */
    private data class SitemapScan(
        val entries: List<CatalogIndexEntry>,
        val allNotModified: Boolean
    )

    private suspend fun sitemapEntries(): SitemapScan {
        val validators = validatorStore?.load().orEmpty()
        val nextValidators = mutableMapOf<String, SitemapValidator>()
        val entries = mutableListOf<CatalogIndexEntry>()
        var carriers = 0
        var notModified = 0
        for ((sourceId, spec) in SITEMAP_SPECS) {
            for (sitemapUrl in spec.sitemapUrls) {
                carriers++
                // Spec-42 #427 — just-in-time, host-aware: a session-bound
                // sitemap carries the concrete URL's own cookie, never another
                // host's; no cookie means no Cookie header at all.
                val headers = cookieProvider.cookieHeadersFor(sitemapUrl)
                val known = validators[sitemapUrl]
                val response = fetcher.getTextConditional(
                    sitemapUrl,
                    headers,
                    known?.etag,
                    known?.lastModified
                )
                if (response.status == HTTP_NOT_MODIFIED) {
                    notModified++
                    known?.let { nextValidators[sitemapUrl] = it }
                    // The stored index still holds this carrier's URLs.
                    entries += current?.entriesFor(sourceId).orEmpty()
                        .filter { entry -> spec.accept(SitemapParser.canonicalUrl(entry.url) ?: entry.url) }
                    continue
                }
                if (response.body.isBlank()) continue
                response.etag?.takeIf { it.isNotBlank() }?.let { etag ->
                    nextValidators[sitemapUrl] = SitemapValidator(etag, response.lastModified)
                }
                // #526 — bounded, canonicalized, host-allowlisted. An
                // oversized or malformed document contributes nothing here;
                // the previous good index is never erased.
                val parsed = SitemapParser.parse(response.body, spec.accept) ?: continue
                for (entry in parsed) {
                    val slug = entry.canonicalUrl.substringAfterLast('/')
                    if (slug.isBlank()) continue
                    entries += CatalogIndexEntry(sourceId = sourceId, url = entry.url, slug = slug)
                }
            }
        }
        if (nextValidators.isNotEmpty()) validatorStore?.save(nextValidators)
        return SitemapScan(entries, carriers > 0 && notModified == carriers)
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

        /** #526 — the sitemap's own "nothing changed" answer. */
        private const val HTTP_NOT_MODIFIED = 304

        /** One source's sitemap URLs plus the book-URL filter. */
        data class SitemapSpec(
            val sitemapUrls: List<String>,
            val accept: (path: String) -> Boolean
        )

        /**
         * The measured carriers: co.ua posts are all book pages; chytaylo
         * keeps its books under `/books/`; sluhay.com publishes 5 826 book
         * URLs in `news_pages.xml` (the only child sitemap carrying books;
         * the index itself is `/sitemap.xml`).
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
            ) { path -> path.contains("/books/") },
            "sluhay" to SitemapSpec(
                sitemapUrls = listOf("https://sluhay.com/news_pages.xml")
            ) { path -> SLUHAY_BOOK_PATH_RE.containsMatchIn(path) }
        )

        /** `https://sluhay.com/<category>/<id>-<slug>.html` — the book inventory. */
        private val SLUHAY_BOOK_PATH_RE =
            Regex("""^https://sluhay\.com/[a-z0-9-]+/\d+-.+\.html$""")

        /** The pure-JVM default: no session, every sitemap stays cookie-free. */
        private val NO_COOKIES = object : SourceCookieProvider {
            override fun cookieFor(url: String): String = ""
        }
    }
}
