package com.slukhayka.audiobooks.data.imports

import android.content.Context
import android.net.Uri
import android.util.Log
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.authors.AuthorIndex
import com.slukhayka.audiobooks.data.authors.RoomAuthorIndex
import com.slukhayka.audiobooks.data.EditionId
import com.slukhayka.audiobooks.data.HASH_BUFFER_SIZE
import com.slukhayka.audiobooks.data.contentHashOf
import com.slukhayka.audiobooks.data.sha256Hex
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookEntity
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.CorrectionEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.metadata.BookProfile
import com.slukhayka.audiobooks.data.metadata.BookProfileLimits
import com.slukhayka.audiobooks.data.metadata.BookProfileMapping
import com.slukhayka.audiobooks.data.metadata.MetadataAssertions
import com.slukhayka.audiobooks.data.metadata.ProfileFreshness
import com.slukhayka.audiobooks.data.metadata.ProfileProvenance
import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore
import com.slukhayka.audiobooks.data.source.SourceAdapter
import com.slukhayka.audiobooks.data.source.SourceBookDetail
import com.slukhayka.audiobooks.data.source.SourceChapter
import com.slukhayka.audiobooks.data.source.SeriesRef
import com.slukhayka.audiobooks.data.source.sourceIdForUrl
import com.slukhayka.audiobooks.data.source.streamOnlyFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ADR-0002 — Library Import: the deep module that owns all five import doors
 * and the shared mapping helpers they use. Constructs over the DAO, an
 * optional Context (local-file import/rescan), and the injected source-adapter
 * list — no adapter is constructed here; the composition root composes them.
 *
 * The five doors:
 *  1. Explicit source import — [importBookFromSource], [importFromSourceUrl],
 *     [importAudiobookFrom4ReadUrl];
 *  2. Captured-page import — [importWebSourcePage], [importAudiobookFromHtml];
 *  3. Local folder import — [importLocalAudioFile], [importLocalAudioStream],
 *     [importLocalAudioFolder], [planLocalAudioFolder], [applyImportPlan],
 *     [importAudioEntries];
 *  4. Rescan — [rescanLocalFolder], [rescanAudioEntries],
 *     [rescanAllLocalFolders];
 *  5. Catalog upsert on import — [upsertCatalogBook].
 *
 * Behaviour contracts kept exactly: explicit re-adds clear Tombstones, merging
 * happens by the Work-level merge key, local files dedupe by content hash.
 */
class LibraryImport(
    private val dao: AudiobookDao,
    private val context: Context?,
    private val sourceAdapters: List<SourceAdapter>,
    // Spec-26 T8 (#182): fired after a NEW work with a series enters the
    // library through the explicit import door — the import event trigger
    // the composition root wires to the universe chain validation. Silent
    // and best-effort by contract: a failing callback never breaks the
    // import.
    private val onWorkImported: (suspend (String) -> Unit)? = null,
    // Spec-32 T2 (#232): the shared book-profile store — a successfully
    // resolved page writes its full profile back so the next listener skips
    // the page fetch. Null without Firebase keys: imports behave exactly as
    // before. Best-effort by contract — a failing write never breaks import.
    private val profileStore: SharedBookMetaStore? = null
) {
    private val authorIndex: AuthorIndex = RoomAuthorIndex(dao)

    // ---------------------------------------------------------------------
    // Door 1 + 2 core: the shared import path (explicit + captured pages)
    // ---------------------------------------------------------------------

    /**
     * Spec-10 T2 + ADR-0011 — the multi-source import core. Turns a parsed
     * source book (from a [SourceAdapter]) into a Work row plus a Source row.
     * Dedup is per RENDITION: a book whose Edition (same narrator — the
     * rendition identity, [EditionId]) already exists in the library merges
     * into that card (the new source is attached to it); the SAME Work with a
     * DIFFERENT narration creates a NEW card — several rendition cards under
     * one Work, each with its own Edition, chapters, tracks and progress.
     */
    suspend fun importBookFromSource(
        sourceId: String,
        detail: SourceBookDetail,
        // Spec-32 T2/T3 (#232/#233): best-effort write-back of the resolved
        // profile. FALSE on the read-skip paths — a cache-derived import is
        // no resolution, and re-writing would roll freshness and burn the
        // free-tier write quota.
        writeBackProfile: Boolean = true
    ): AudiobookEntity =
        withContext(Dispatchers.IO) {
            // ADR-0010: the Work key is bibliographic (title|author) — the
            // narrator is an Edition property, never part of the Work.
            val mergeKey = MergeKey.keyFor(detail.title, detail.author)
            val narrator = MetadataAssertions.normalizeClaimedText(detail.narrator) ?: "$sourceId narrator"
            // ADR-0011: the Edition id is the rendition identity and is
            // deterministic for mergeable books (the bookId fallback applies
            // only to blank keys), so the same narration resolves to its card
            // and a different narration of the same Work resolves to nothing.
            val existing = if (mergeKey.isNotBlank()) {
                dao.findBookByEditionId(EditionId.forBook(mergeKey, "", narrator, ""))
            } else {
                null
            }
            val bookId = existing?.id ?: adapterBookId(sourceId, detail.url)
            // The canonical Edition id the stored rows use (the same formula
            // as the Edition insert below) — the shared profile is keyed by it.
            val editionId = EditionId.forBook(mergeKey, bookId, narrator)

            // Spec-32 T2 (#232): a resolved page contributes its full profile
            // to the shared base, best-effort — the next listener skips the
            // fetch (import, detail-open AND catalogue hydration all resolve
            // pages, so all contribute). A failing write never breaks the
            // import.
            if (writeBackProfile && detail.chapters.isNotEmpty()) {
                runCatching {
                    profileStore?.putProfile(
                        sourceId = sourceId,
                        editionId = editionId,
                        profile = BookProfileMapping.fromDetail(detail),
                        provenance = ProfileProvenance(
                            ProfileProvenance.SOURCE_RESOLVED,
                            System.currentTimeMillis()
                        )
                    )
                }
            }

            if (existing == null) {
                // Spec-14 T2/T3: the shared import path persists the enriched
                // profile the seam now provides (genres → genre, rating,
                // series) — every import door's card agrees with the source.
                // ADR-0004: claim normalization (brand-scrub, duration
                // sentinel) and materialization come from the one
                // MetadataAssertions module; the door keeps only its insert
                // defaults (id scheme, placeholder author/narrator, template).
                // ADR-0007: one domain Edition owns the logical chapter list;
                // the importing Source gets its physical tracks (1:1 by index).
                // ADR-0009: the audiobooks row carries only the persisted
                // metadata; the fused columns (series, mergeKey) are written
                // to the Works + Library Entry rows alongside it.
                // Spec-24 T1: the claimed title is scrubbed of SEO suffixes
                // (one rule, one module — the Work row below stores the same
                // clean title). The merge key keeps the RAW claim so stored
                // identities never churn under the scrub.
                val book = AudiobookEntity(
                    id = bookId,
                    title = MetadataAssertions.normalizeTitle(detail.title),
                    author = MetadataAssertions.normalizeClaimedText(detail.author) ?: sourceId,
                    narrator = narrator,
                    // #264: the claimed description passes the same one-rule
                    // treatment as the title — SEO templates scrub to empty
                    // and fall back to the honest source phrase.
                    description = MetadataAssertions.normalizeDescription(detail.description)
                        .ifBlank { "Аудіокнига з джерела $sourceId" },
                    coverDrawableRes = R.drawable.img_neuromancer_cover_1785247475170,
                    coverImageUrl = MetadataAssertions.coverDelta(detail.coverImageUrl),
                    genre = detail.genres.joinToString(" · ").ifBlank { "Каталог" },
                    sourceUrl = detail.url,
                    isDownloaded = false,
                    // The page-reported total ("Триває:" / schema.org) is the
                    // book's advertised length and wins; the chapter-duration
                    // sum is only a fallback for pages that carry none.
                    totalDurationSeconds = MetadataAssertions.normalizeDurationSeconds(detail.totalDurationSeconds)
                        ?: MetadataAssertions.normalizeDurationSeconds(detail.chapters.sumOf { it.durationSeconds })
                        ?: 0L,
                    totalChapters = detail.chapters.size,
                    rating = detail.rating?.toFloat() ?: 0f
                )
                dao.insertAudiobooks(listOf(book))
                // ADR-0009: the Works row and the Library Entry row are
                // written ALONGSIDE the audiobooks row — series/identity reads
                // join through the entry, so the card keeps its series and the
                // merge key stays the dedup identity.
                val workId = if (mergeKey.isNotBlank()) {
                    (dao.findWorkByMergeKey(mergeKey) ?: WorkEntity(
                        id = mergeKey,
                        mergeKey = mergeKey,
                        title = book.title,
                        author = book.author,
                        seriesTitle = detail.series?.name,
                        seriesUrl = detail.series?.url,
                        seriesIndex = detail.series?.position,
                        coverImageUrl = book.coverImageUrl,
                        addedAt = System.currentTimeMillis()
                    ).also { dao.upsertWork(it) }).id
                } else {
                    bookId
                }
                dao.getWorkById(workId)?.let { work -> authorIndex.indexWorks(listOf(work), sourceId) }
                dao.upsertLibraryEntry(
                    id = bookId,
                    workId = workId,
                    isFavorite = false,
                    createdAt = System.currentTimeMillis(),
                    downloadProgress = 0f
                )
                // Spec-26 T8 (#182): a NEW book with a series fires the
                // import event trigger (the callback is wired to the universe
                // chain validation in the composition root). Best-effort and
                // silent — a failing callback never breaks the import.
                if (workId.isNotBlank() && detail.series?.name?.isNotBlank() == true) {
                    runCatching { onWorkImported?.invoke(workId) }
                }
                // ADR-0010: the Edition id carries the narrator — two
                // narrations of the same Work keep distinct listening state.
                val editionId = EditionId.forBook(mergeKey, bookId, book.narrator)
                dao.insertEdition(
                    EditionEntity(
                        id = editionId,
                        workId = bookId,
                        narrator = book.narrator,
                        totalChapters = detail.chapters.size,
                        totalDurationSeconds = book.totalDurationSeconds
                    )
                )
                val materialized = MetadataAssertions.materializeChaptersAndTracks(
                    editionId = editionId,
                    sourceId = sourceId,
                    bookId = bookId,
                    bookTitle = book.title,
                    chapters = detail.chapters
                )
                dao.insertChapters(materialized.chapters)
                val source = sourceRow(sourceId, bookId, editionId, detail.url)
                dao.insertSources(listOf(source))
                dao.insertTracks(
                    materialized.tracks.map { it.copy(sourceId = source.id, id = MetadataAssertions.trackId(source.id, it.trackIndex)) }
                )
                // An explicit import is a user action: any tombstone of the
                // work is cleared so a re-added book never stays hidden.
                dao.deleteTombstone(bookId)
                // The JOINed projection carries the series/mergeKey the Works
                // row now holds, so callers get a fully shaped row.
                dao.getAudiobookById(bookId)?.toAudiobookEntity() ?: book
            } else {
                // Merge: attach the new source (unless it is already known)
                // and give it its physical tracks. The Edition's logical
                // chapter list stays the FIRST source's list (ADR-0007) —
                // no chapter re-materialization on merge.
                val known = dao.getSourcesForBookSync(existing.id).any { it.url == detail.url }
                if (!known) {
                    val storedEdition = dao.getEditionForWork(existing.id)
                    val editionId = storedEdition?.id ?: EditionId.forBook(mergeKey, existing.id, existing.narrator)
                    if (storedEdition == null) {
                        dao.insertEdition(
                            EditionEntity(
                                id = editionId,
                                workId = existing.id,
                                narrator = existing.narrator,
                                totalChapters = existing.totalChapters,
                                totalDurationSeconds = existing.totalDurationSeconds
                            )
                        )
                    }
                    val source = sourceRow(sourceId, existing.id, editionId, detail.url)
                    dao.insertSources(listOf(source))
                    dao.insertTracks(
                        MetadataAssertions.materializeTracks(source.id, detail.chapters)
                    )
                }
                dao.deleteTombstone(existing.id)
                existing.toAudiobookEntity()
            }
        }

    private fun sourceRow(sourceId: String, bookId: String, editionId: String, url: String) = SourceEntity(
        // ADR-0007: source ids are deterministic per (type, edition).
        id = "$sourceId-$editionId",
        bookId = bookId,
        editionId = editionId,
        type = sourceId,
        url = url,
        streamOnly = streamOnlyFor(sourceId),
        addedAt = System.currentTimeMillis()
    )

    /**
     * Spec-14 T5 — the book id for an import door: the source's adapter owns
     * its id scheme ([SourceAdapter.bookId]), so no import door derives ids
     * itself and no scheme can diverge. The generic "<sourceId>-<slug>"
     * fallback only covers a source without a registered adapter (defensive;
     * every live source has one).
     */
    private fun adapterBookId(sourceId: String, url: String): String =
        sourceAdapters.firstOrNull { it.sourceId == sourceId }?.bookId(url)
            ?: genericSourceBookId(sourceId, url)

    private fun genericSourceBookId(sourceId: String, url: String): String {
        val slug = url.substringAfterLast('/').substringBefore('?')
            .removeSuffix(".html")
            .removeSuffix(".m3u")
            .ifBlank { "book-${System.currentTimeMillis()}" }
        return "$sourceId-$slug"
    }

    /**
     * Spec-10 T4 — import-and-play entry point for a search result: fetch the
     * book page from the chosen source, import the Work (merging into an
     * existing card when the merge key matches), return the stored book. Null
     * when the source is unknown or the page yields nothing playable.
     *
     * Spec-32 T3 (#233) — read-skip: when the caller passes the card's known
     * identity ([known] — the search/catalogue card already carries it), a
     * FRESH shared profile for that Source×Edition imports WITHOUT fetching
     * the page, and a stale one is served fail-open when the re-fetch fails
     * (the source is down or blocked). Callers without the identity fall
     * through to the live fetch exactly as before.
     */
    suspend fun importFromSourceUrl(
        sourceId: String,
        url: String,
        known: KnownBookIdentity? = null
    ): AudiobookEntity? =
        withContext(Dispatchers.IO) {
            val adapter = sourceAdapters.firstOrNull { it.sourceId == sourceId }
                ?: return@withContext null
            val store = profileStore
            // Read-skip: the shared key is the Edition id, which derives from
            // title|author|narrator — so only a caller that knows the Work
            // identity can ask the shared base at all. The entry is fetched
            // ONCE and serves both the fresh check and the stale fail-open.
            val entry = if (known != null && store != null) {
                runCatching { store.getProfileEntry(sourceId, editionIdOf(sourceId, known)) }.getOrNull()
            } else null
            if (entry != null && ProfileFreshness.isFresh(entry.resolvedAt, System.currentTimeMillis()) &&
                entry.profile.chapters.isNotEmpty()
            ) {
                // A cache hit is imported WITHOUT the page — and never
                // re-written back (no resolution happened; a re-write would
                // roll the freshness forward and burn the write quota).
                val imported = runCatching {
                    importBookFromSource(sourceId, detailFromProfile(known!!, entry.profile, url), writeBackProfile = false)
                }.getOrNull()
                if (imported != null) return@withContext imported
            }
            try {
                val detail = adapter.fetchBookPage(url)
                if (detail.chapters.isEmpty()) return@withContext null
                // The card's cover survives a page that carries none (see
                // KnownBookIdentity.coverImageUrl).
                val withCover = if (detail.coverImageUrl == null && known?.coverImageUrl != null) {
                    detail.copy(coverImageUrl = known.coverImageUrl)
                } else detail
                importBookFromSource(sourceId, withCover)
            } catch (e: Exception) {
                // Fail-open: the re-fetch failed (source down / Cloudflare) —
                // serve the STALE profile when one exists, never nothing, and
                // never re-write it (the page was not resolved).
                if (entry != null && entry.profile.chapters.isNotEmpty()) {
                    runCatching {
                        importBookFromSource(sourceId, detailFromProfile(known!!, entry.profile, url), writeBackProfile = false)
                    }.getOrNull()
                } else null
            }
        }

    /**
     * The Edition id of a known card identity — the shared profile key. The
     * narrator goes through the SAME normalization as the import write path
     * (blank → the per-source placeholder), so the read key always matches
     * the key a resolution wrote back under.
     */
    private fun editionIdOf(sourceId: String, identity: KnownBookIdentity): String {
        val narrator = MetadataAssertions.normalizeClaimedText(identity.narrator) ?: "$sourceId narrator"
        return EditionId.forBook(MergeKey.keyFor(identity.title, identity.author), "", narrator, "")
    }

    /** Materialises a cached profile back into the import seam's detail shape. */
    private fun detailFromProfile(
        identity: KnownBookIdentity,
        profile: BookProfile,
        url: String
    ): SourceBookDetail = SourceBookDetail(
        title = identity.title,
        author = identity.author,
        narrator = identity.narrator,
        url = url,
        coverImageUrl = profile.coverImageUrl ?: identity.coverImageUrl,
        chapters = profile.chapters.map { chapter ->
            SourceChapter(title = chapter.title, streamUrl = chapter.streamUrl, durationSeconds = chapter.durationSeconds)
        },
        totalDurationSeconds = profile.totalDurationSeconds,
        rating = profile.rating,
        genres = profile.genres,
        series = profile.seriesTitle?.let { SeriesRef(name = it, position = profile.seriesIndex) },
        // #264: a profile read back from the shared base may predate the
        // description scrub — it passes the same rule before flowing on (the
        // import door below applies its fallback when this scrubs to empty).
        description = MetadataAssertions.normalizeDescription(profile.description)
    )

    /**
     * Spec-32 T4 (#234) — the self-healing door: a 404/403 stream failure
     * during playback re-resolves the book's source page and swaps the fresh
     * stream URL into the PRIMARY source's physical track row (ADR-0007 — the
     * track, never the logical chapter). Returns the fresh URL for ONE retry,
     * or null when nothing changed / nothing could be healed — the player then
     * surfaces the honest failure. The index pairing heals only while the
     * page still serves the OTHER chapters at their own indices (a reordered
     * page would play the wrong chapter). The refreshed page is written back
     * to the shared profile base best-effort (a resolved page contributes,
     * per T2 #232), so the next listener stops being served the dead link.
     */
    suspend fun refreshStreamUrl(bookId: String, chapterIndex: Int, failedUrl: String): String? =
        withContext(Dispatchers.IO) {
            val book = dao.getAudiobookById(bookId) ?: return@withContext null
            val sourceUrl = book.sourceUrl
            if (sourceUrl.isBlank()) return@withContext null
            val sourceId = sourceIdForUrl(sourceUrl)
            val adapter = sourceAdapters.firstOrNull { it.sourceId == sourceId }
                ?: return@withContext null
            // Fail-open: a dead page contributes nothing — the player keeps
            // the honest failure instead of a fabricated retry.
            val detail = try {
                adapter.fetchBookPage(sourceUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@withContext null
            if (detail.chapters.isEmpty()) return@withContext null
            // The physical track of the failed chapter, on the book's primary
            // source — the same pairing the player resolves chapter → track
            // by index (SourceCatalog.getPlayableChapters).
            val sources = dao.getSourcesForBookSync(bookId)
            val primary = sources.firstOrNull { it.type == sourceId } ?: sources.firstOrNull()
                ?: return@withContext null
            val edition = dao.getEditionForWork(bookId)
            if (edition != null && primary.editionId != null && primary.editionId != edition.id) {
                return@withContext null
            }
            val tracks = dao.getTracksForSourceSync(primary.id)
            // Order-stability guard (spec-32 T4): the index pairing is only
            // sound while the page still serves the OTHER chapters at their
            // own indices. A reordered page KEEPS the old URLs — just moved
            // to different slots — and healing by index would swap in the
            // WRONG chapter's stream URL: audio of another chapter under the
            // failed one's title. A bulk move (every URL replaced in place)
            // still heals — no URL survived, nothing to confuse.
            val others = tracks.filter { it.trackIndex != chapterIndex }
            val reordered = others.any { other ->
                if (detail.chapters.getOrNull(other.trackIndex)?.streamUrl == other.url) return@any false
                // The old URL survived but sits at another index — a swap.
                detail.chapters.any { it.streamUrl == other.url }
            }
            if (reordered) return@withContext null
            val fresh = detail.chapters.getOrNull(chapterIndex) ?: return@withContext null
            // The page still serves the same dead link (or a non-http one) —
            // nothing to heal, and no pointless retry.
            if (fresh.streamUrl == failedUrl || !BookProfileLimits.isHttpUrl(fresh.streamUrl)) {
                return@withContext null
            }
            val track = tracks.firstOrNull { it.trackIndex == chapterIndex }
                ?: return@withContext null
            if (track.url != failedUrl) return@withContext null
            dao.insertTracks(listOf(track.copy(url = fresh.streamUrl)))
            // A successfully re-resolved page refreshes the shared profile,
            // rolling its freshness — best-effort, silent on failure. Keyed by
            // the STORED source id (the same key the import doors use), so an
            // aliased URL never forks a second document per Source×Edition.
            runCatching {
                val editionId = edition?.id
                if (editionId != null) {
                    profileStore?.putProfile(
                        sourceId = primary.type,
                        editionId = editionId,
                        profile = BookProfileMapping.fromDetail(detail),
                        provenance = ProfileProvenance(
                            ProfileProvenance.SOURCE_RESOLVED,
                            System.currentTimeMillis()
                        )
                    )
                }
            }
            fresh.streamUrl
        }

    /**
     * Spec-13 T3 / ADR-0006 — import a WebView-source book from its CAPTURED
     * page HTML through the [SourceAdapter.parseCapturedPage] seam: the page
     * HTML comes from the live browser session (past the Cloudflare challenge
     * — server-fetch would 403); the adapter's captured-page path parses
     * metadata + the inline Playerjs playlist and fetches the playlist with
     * the source Referer. Null when the source is unknown, does not support
     * the door (default "not mine"), the page is unparseable or yields
     * nothing playable. No import door downcasts an adapter to a concrete
     * class — a future WebView-pattern source works through the same door.
     */
    suspend fun importWebSourcePage(
        sourceId: String,
        url: String,
        html: String,
        capturedAudioUrls: List<String> = emptyList()
    ): AudiobookEntity? =
        withContext(Dispatchers.IO) {
            val adapter = sourceAdapters.firstOrNull { it.sourceId == sourceId }
                ?: return@withContext null
            try {
                val parsed = adapter.parseCapturedPage(html, url) ?: return@withContext null
                val detail = parsed.withCapturedAudioUrls(capturedAudioUrls)
                if (detail.chapters.isEmpty()) return@withContext null
                if (detail.chapters.any { !it.streamUrl.isPlayableSourceUrl() }) return@withContext null
                // A captured 4read page is session material. Keep its profile
                // local until playback has actually succeeded; this avoids
                // publishing a Cloudflare/challenge artefact to the shared
                // metadata base.
                importBookFromSource(sourceId, detail, writeBackProfile = sourceId != "4read")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Applies a freshly captured browser page to an existing Edition. This is
     * the recovery door used after a 4read stream expires: logical chapters,
     * listening state and downloaded files stay put; only the physical URLs
     * owned by the matching Source are refreshed. A chapter-count mismatch is
     * rejected so a reordered page can never silently corrupt progress.
     */
    suspend fun recoverWebSourcePage(
        bookId: String,
        sourceId: String,
        url: String,
        html: String,
        capturedAudioUrls: List<String> = emptyList()
    ): AudiobookEntity? = withContext(Dispatchers.IO) {
        val adapter = sourceAdapters.firstOrNull { it.sourceId == sourceId }
            ?: return@withContext null
        val parsed = try {
            adapter.parseCapturedPage(html, url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        val detail = parsed.withCapturedAudioUrls(capturedAudioUrls)
        if (detail.chapters.isEmpty()) return@withContext null
        if (detail.chapters.any { !it.streamUrl.isPlayableSourceUrl() }) return@withContext null
        val source = dao.getSourcesForBookSync(bookId).firstOrNull { it.type == sourceId && it.url == url }
            ?: dao.getSourcesForBookSync(bookId).firstOrNull { it.type == sourceId }
            ?: return@withContext null
        val edition = dao.getEditionForWork(bookId)
        if (edition != null && source.editionId != null && source.editionId != edition.id) {
            return@withContext null
        }
        if (edition != null && edition.narrator.isNotBlank() && detail.narrator.isNotBlank() &&
            !edition.narrator.equals(detail.narrator, ignoreCase = true)
        ) {
            return@withContext null
        }
        val logicalChapters = dao.getChaptersListForBook(bookId).sortedBy { it.chapterIndex }
        if (logicalChapters.size != detail.chapters.size || logicalChapters.indices.any { index ->
                val storedTitle = logicalChapters[index].title.trim()
                val capturedTitle = detail.chapters[index].title.trim()
                storedTitle.isNotBlank() && capturedTitle.isNotBlank() &&
                    !storedTitle.equals(capturedTitle, ignoreCase = true)
            }) {
            // Same-count pages can still be reordered. Refuse the update rather
            // than attaching a new URL to the wrong logical chapter.
            return@withContext null
        }
        val tracks = dao.getTracksForSourceSync(source.id).sortedBy { it.trackIndex }
        if (tracks.size != detail.chapters.size || tracks.map { it.trackIndex } != detail.chapters.indices.toList()) {
            return@withContext null
        }
        val refreshed = detail.chapters.mapIndexed { index, chapter ->
            tracks[index].copy(url = chapter.streamUrl)
        }
        dao.insertTracks(refreshed)
        dao.getAudiobookById(bookId)?.toAudiobookEntity()
    }

    /**
     * Uses observed browser requests only when the page parser did not expose
     * any playable URLs. WebView request order includes prefetches and range
     * retries, so it is never safe to replace a parsed chapter list by arrival
     * order. The parser remains the source of chapter-to-track identity.
     */
    private fun SourceBookDetail.withCapturedAudioUrls(urls: List<String>): SourceBookDetail {
        val captured = urls.asSequence()
            .map(String::trim)
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .toList()
        if (captured.size != chapters.size || captured.isEmpty()) return this
        if (chapters.any { it.streamUrl.isPlayableSourceUrl() }) return this
        return copy(chapters = chapters.mapIndexed { index, chapter -> chapter.copy(streamUrl = captured[index]) })
    }

    private fun String.isPlayableSourceUrl(): Boolean {
        val normalized = trim().lowercase()
        return normalized.startsWith("http://") || normalized.startsWith("https://")
    }

    /**
     * Spec-14 T4/T5 / ADR-0006 — the 4read WebView door is the same door:
     * it rides the [SourceAdapter.parseCapturedPage] seam (playlist content
     * resolved through the adapter's own transport), and the shared import
     * path persists the Work with the same merge key / id shape. The
     * repository performs no 4read parsing or transport. A captured page that
     * yields nothing playable surfaces as absent (null) — never a forged card.
     */
    suspend fun importAudiobookFromHtml(urlOrSlug: String, html: String): AudiobookEntity? {
        val cleanInput = urlOrSlug.trim()
        val sourceUrl = if (cleanInput.startsWith("http")) cleanInput else "https://4read.org/$cleanInput"
        return importWebSourcePage("4read", sourceUrl, html)
    }

    /**
     * Spec-14 T3/T5 — the link-import door rides the source seam: the adapter
     * owns fetching and extraction (fetchBookPage); the repository only
     * persists through the shared import path (same merge key, same source row
     * as every other door). A page that yields nothing playable surfaces as
     * absent (null) — the fabricated fallback card is gone.
     */
    suspend fun importAudiobookFrom4ReadUrl(urlOrSlug: String): AudiobookEntity? {
        val cleanInput = urlOrSlug.trim()
        val sourceUrl = if (cleanInput.startsWith("http")) cleanInput else "https://4read.org/$cleanInput"
        return importFromSourceUrl("4read", sourceUrl)
    }

    /**
     * Spec-14 T1 — catalogue upsert on import (and on catalogue sync). Inserts
     * or updates a catalogue book row; a known row is enriched with real
     * duration/series data this source carries, never clobbered with 0.
     */
    /**
     * ADR-0005 — the catalog write path enforces tombstones at the
     * persistence layer: a tombstoned Work is a no-op here. The guard is an
     * insert-unless-tombstoned statement ([AudiobookDao.insertCatalogBookIfNotTombstoned])
     * plus a single-row [AudiobookDao.isBookTombstoned] check for an existing
     * row — no call site consults a tombstone set anymore. Returns null for a
     * tombstoned Work (nothing landed), the stored row otherwise. Explicit
     * imports remain the resurrection door (they clear the marker, unchanged).
     */
    internal suspend fun upsertCatalogBook(book: com.slukhayka.audiobooks.data.catalog.CatalogBook): AudiobookEntity? {
        val existing = dao.getAudiobookById(book.id)
        if (existing != null) {
            // Even an existing row is not enriched while its Work is
            // tombstoned (defensive: delete removes the row, but a stale
            // tombstone must never silently re-enrich a deleted Work).
            if (dao.isBookTombstoned(book.id)) return null
            var updated = existing
            // ADR-0004: the field-precedence delta comes from the one
            // MetadataAssertions module — never re-derived here.
            //
            // Legacy placeholder cleanup: catalogue books were once seeded with
            // a fabricated 4:00:00 (14400s) and 5 chapters. The sentinel is
            // treated as unknown (durationDelta never lets it survive), so it
            // never renders as real; the real duration is back-filled from the
            // book page (refreshBookCoverAndDetails). The stored chapter count
            // may already be REAL (a page fetch with no parseable duration
            // keeps the sentinel but writes the true chapter count), so only
            // the duration is reset — never the chapters.
            val storedDuration = MetadataAssertions.normalizeDurationSeconds(existing.totalDurationSeconds)
            if (storedDuration == null) {
                dao.updateBookStats(book.id, existing.totalChapters, 0L)
                updated = updated.copy(totalDurationSeconds = 0L)
            }
            // Enrich with a real duration this source carries (e.g. the ТОП 100
            // page's "Триває:") — never clobber a known value with an unknown.
            val enrichedDuration = MetadataAssertions.durationDelta(updated.totalDurationSeconds, book.totalDurationSeconds)
            if (enrichedDuration != updated.totalDurationSeconds) {
                dao.updateBookStats(book.id, updated.totalChapters, enrichedDuration)
                updated = updated.copy(totalDurationSeconds = enrichedDuration)
            }
            // ADR-0009: make sure the Works row and the Library Entry row
            // exist before the series delta — series now persists on the
            // Work, and reads join through the entry.
            ensureWorkAndEntry(book, book.id)
            // Series applies only when its URL changed (the membership signal).
            val series = MetadataAssertions.seriesDelta(
                existingSeriesUrl = updated.seriesUrl,
                claimedUrl = book.seriesUrl,
                claimedTitle = book.seriesTitle,
                claimedIndex = book.seriesIndex
            )
            if (series != null) {
                dao.updateSeriesFields(book.id, series.title, series.url, series.index)
                updated = updated.copy(
                    seriesTitle = series.title,
                    seriesUrl = series.url,
                    seriesIndex = series.index
                )
            }
            // Return the known updated shape instead of re-querying: the
            // row may be deleted concurrently and `!!` on a re-query would
            // crash the whole catalogue sync.
            return updated.toAudiobookEntity()
        }
        // ADR-0009: the audiobooks row carries only the persisted metadata —
        // the catalogue book's series lands on the Works row via
        // ensureWorkAndEntry below, and the returned row is the JOINed
        // projection (so the series reads resolve immediately).
        val newBook = AudiobookEntity(
            id = book.id,
            // Spec-24 T1: the claimed poster title is scrubbed of SEO
            // suffixes at the catalog write path — the stored row is clean
            // by construction.
            title = MetadataAssertions.normalizeTitle(book.title),
            author = book.author.ifBlank { "4read.org" },
            narrator = "4read Voice Narrator",
            // #264: the constant catalog phrase passes the rule like every
            // other stored description — the invariant is uniform, the call
            // is a no-op on honest text.
            description = MetadataAssertions.normalizeDescription("Аудіокнига з каталогу 4read.org"),
            coverDrawableRes = R.drawable.img_neuromancer_cover_1785247475170,
            coverImageUrl = MetadataAssertions.coverDelta(book.coverImageUrl),
            genre = "4read Каталог",
            sourceUrl = book.url,
            isDownloaded = false,
            // The catalogue homepage doesn't know the chapter count or total
            // duration — they're back-filled from the real chapter list once
            // the book page is fetched (see getChaptersList). Sources that DO
            // carry a real duration (ТОП 100's "Триває:") keep it; the legacy
            // 14400 s sentinel counts as unknown (ADR-0004) — never a
            // fabricated "5 Ch. • 4:00:00".
            totalDurationSeconds = MetadataAssertions.normalizeDurationSeconds(book.totalDurationSeconds) ?: 0L,
            totalChapters = 0,
            rating = 0f
        )
        // ADR-0005: the insert-unless-tombstoned statement — a tombstoned Work
        // is a no-op (the guarded INSERT lands nothing), confirmed by what
        // actually exists afterwards. Never resurrects a deleted Work.
        dao.insertCatalogBookIfNotTombstoned(
            id = newBook.id,
            title = newBook.title,
            author = newBook.author,
            narrator = newBook.narrator,
            description = newBook.description,
            coverDrawableRes = newBook.coverDrawableRes,
            coverImageUrl = newBook.coverImageUrl,
            genre = newBook.genre,
            sourceUrl = newBook.sourceUrl,
            isDownloaded = newBook.isDownloaded,
            totalDurationSeconds = newBook.totalDurationSeconds,
            totalChapters = newBook.totalChapters,
            rating = newBook.rating,
            sourceTreeUri = newBook.sourceTreeUri
        )
        // Only when the guarded insert actually landed: the Works + Library
        // Entry rows are written alongside (ADR-0009), so a tombstoned Work
        // gains nothing — not even a browse-row that could resurrect it.
        return if (dao.hasAudiobookRow(book.id)) {
            ensureWorkAndEntry(book, book.id)
            // The JOINed projection carries the series the Works row now holds
            // (and the entry's createdAt/favorite), so callers get a fully
            // shaped row without a second read by them.
            dao.getAudiobookById(book.id)?.toAudiobookEntity() ?: newBook
        } else {
            null
        }
    }

    /**
     * ADR-0009 — links a library row to its Work and writes its Library Entry
     * alongside. The Works row is found-or-created by the same identity the
     * browse layer uses (normalized title|author, narrator '' for the
     * catalogue), so one Work per identity and the entry anchors to it; a
     * blank identity anchors the entry to the book itself (no Works row). The
     * Work also gets a work_source for the book's own URL under the
     * deterministic (work, source, url) id [SourceCatalog.writeWorkEdition]
     * uses, so a later crawl no-ops on the same row and the merged feed card
     * can open the Work. Idempotent by construction.
     */
    private suspend fun ensureWorkAndEntry(book: com.slukhayka.audiobooks.data.catalog.CatalogBook, bookId: String) {
        val mergeKey = MergeKey.keyFor(book.title, book.author)
        val work = if (mergeKey.isNotBlank()) {
            dao.findWorkByMergeKey(mergeKey) ?: WorkEntity(
                id = mergeKey,
                mergeKey = mergeKey,
                // Spec-24 T1: the Work row stores the scrubbed title too.
                title = MetadataAssertions.normalizeTitle(book.title),
                author = book.author.trim(),
                seriesTitle = book.seriesTitle,
                seriesUrl = book.seriesUrl,
                seriesIndex = book.seriesIndex,
                coverImageUrl = book.coverImageUrl,
                addedAt = System.currentTimeMillis()
            ).also { dao.upsertWork(it) }
        } else {
            null
        }
        val workId = work?.id ?: bookId
        dao.getWorkById(workId)?.let { work ->
            authorIndex.indexWorks(
                listOf(work),
                sourceId = book.url.takeIf(String::isNotBlank)?.let(::sourceIdForUrl) ?: "catalog-union"
            )
        }
        dao.upsertLibraryEntry(
            id = bookId,
            workId = workId,
            isFavorite = false,
            createdAt = System.currentTimeMillis(),
            downloadProgress = 0f
        )
        // #388 — blank-key books have no Works row (workId == bookId), so
        // a work_source would violate the FK (workId → works.id). Skip it.
        if (mergeKey.isNotBlank() && workId.isNotBlank() && book.url.isNotBlank()) {
            val sourceId = sourceIdForUrl(book.url)
            dao.safeUpsertWorkSource(
                WorkSourceEntity(
                    id = "$workId|$sourceId|${Integer.toHexString(book.url.hashCode())}",
                    workId = workId,
                    sourceId = sourceId,
                    sourceUrl = book.url,
                    streamOnly = streamOnlyFor(sourceId),
                    coverImageUrl = book.coverImageUrl,
                    addedAt = System.currentTimeMillis()
                )
            )
        }
    }

    // ---------------------------------------------------------------------
    // Door 3: local folder/file import
    // ---------------------------------------------------------------------

    /**
     * Copies a user-picked audio file (SAF content Uri) into private app
     * storage and creates a single-chapter book whose chapter points at the
     * local file.
     */
    suspend fun importLocalAudioFile(uri: Uri): AudiobookEntity = withContext(Dispatchers.IO) {
        val ctx = context ?: throw IllegalStateException("importLocalAudioFile called without Context")
        val displayName = queryDisplayName(ctx, uri) ?: "Аудіокнига"
        val input = ctx.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("Не вдалося відкрити файл $uri")
        importLocalAudioStream(displayName, input)
    }

    /**
     * Core of the single-file import, exposed as a suspend stream so JVM tests
     * drive it without a content resolver (spec #8 ticket T7).
     *
     * Dedupe (wayfinder #48): if the copied bytes already exist in the
     * library, the fresh copy is deleted and the existing book is returned —
     * importing the same file twice never duplicates storage.
     */
    suspend fun importLocalAudioStream(displayName: String, stream: java.io.InputStream): AudiobookEntity =
        withContext(Dispatchers.IO) {
            val base = sanitizeLocalBaseName(displayName)
            val dest = copyLocalAudioStream(base, localFileExtension(displayName), stream)
            // ADR-0007: the content hash lives on the TRACK rows (a local
            // import is a "local" Source whose tracks carry the copied files).
            val existing = dao.getTrackByContentHash(dest.sha256Hex)
            if (existing != null) {
                File(dest.path).delete()
                // ADR-0007: the track's owner is its SOURCE, not a bookId on
                // the track row — resolve the owner book through the source.
                val ownerBookId = dao.getBookIdBySourceId(existing.sourceId)
                    ?: throw java.io.IOException("Дублікат файлу, але книгу не знайдено")
                return@withContext dao.getAudiobookById(ownerBookId)?.toAudiobookEntity()
                    ?: throw java.io.IOException("Дублікат файлу, але книгу не знайдено")
            }
            insertLocalBook(
                title = base,
                author = LOCAL_FILE_AUTHOR,
                description = "Імпортований аудіофайл: $displayName",
                chapters = listOf(LocalChapterInput(title = base, filePath = dest.path, contentHash = dest.sha256Hex))
            )
        }

    /**
     * Folder import (spec #8 Block 4): walks the SAF tree picked via
     * `OpenDocumentTree` (recursively collecting mp3/m4a/ogg audio files) and
     * delegates the grouping/insertion to the testable [importAudioEntries]
     * core.
     */
    suspend fun importLocalAudioFolder(treeUri: Uri): LocalImportResult = withContext(Dispatchers.IO) {
        val ctx = context ?: throw IllegalStateException("importLocalAudioFolder called without Context")
        val entries = LocalFolderScanner.scan(ctx, treeUri)
        importAudioEntries(entries, treeUri.toString())
    }

    /**
     * Step 2 of the smart import (wayfinder #29): scans a picked SAF tree and
     * builds the pure [ImportPlan] — grouping, natural sort and T0 merge
     * suggestions against the existing library — WITHOUT touching disk or
     * Room. The user reviews and edits this plan; only [applyImportPlan]
     * writes.
     */
    suspend fun planLocalAudioFolder(treeUri: Uri): ImportPlan = withContext(Dispatchers.IO) {
        val ctx = context ?: throw IllegalStateException("planLocalAudioFolder called without Context")
        val entries = LocalFolderScanner.scan(ctx, treeUri)
        val works = dao.getAllAudiobooksOnce().map { ImportPlanner.ExistingWork(id = it.id, title = it.title, mergeKey = it.mergeKey ?: "") }
        ImportPlanner.buildPlan(
            source = SourceRef.Folder(treeUri.toString()),
            entries = entries,
            existingWorks = works
        )
    }

    /**
     * Step 4 of the smart import (wayfinder #29): applies a confirmed
     * [ImportPlan] — the ONLY writer of the smart import. Reuses the same
     * copy-then-hash + [insertLocalBook] core as the direct import, so a
     * plan confirmed without edits behaves exactly like [importAudioEntries].
     *
     * A book with `mergedIntoBookId` set attaches its chapters to the
     * existing Work instead of creating a new card (the #54 merge path, the
     * user's explicit choice). The plan's corrections are persisted to the
     * `corrections` store at apply time — one write path, no orphan decisions.
     */
    suspend fun applyImportPlan(plan: ImportPlan, sourceTreeUri: String? = null): LocalImportResult =
        withContext(Dispatchers.IO) {
            var booksImported = 0
            var filesImported = 0
            var skippedFiles = 0
            var duplicateFiles = 0
            val seenHashes = mutableSetOf<String>()

            suspend fun copyUnlessDuplicate(
                baseName: String,
                chapterTitle: String,
                extension: String,
                openStream: () -> java.io.InputStream
            ): LocalChapterInput? {
                val dest = try {
                    copyLocalAudioStream(baseName, extension, openStream())
                } catch (e: Exception) {
                    Log.w("AudiobookRepo", "Plan import failed", e)
                    skippedFiles++
                    return null
                }
                if (!seenHashes.add(dest.sha256Hex) || dao.getTrackByContentHash(dest.sha256Hex) != null) {
                    File(dest.path).delete()
                    duplicateFiles++
                    return null
                }
                return LocalChapterInput(title = chapterTitle, filePath = dest.path, contentHash = dest.sha256Hex)
            }

            for (book in plan.books) {
                val targetBookId = book.mergedIntoBookId
                val chapters = mutableListOf<LocalChapterInput>()
                for (chapter in book.chapters) {
                    val chapterTitle = chapter.title.ifBlank { "Розділ ${chapters.size + 1}" }
                    val base = sanitizeLocalBaseName(chapter.file.fileName)
                    val copied = copyUnlessDuplicate(
                        if (targetBookId != null) "$targetBookId-$base" else "${book.title}-$base",
                        chapterTitle,
                        localFileExtension(chapter.file.fileName),
                        chapter.file.openStream
                    ) ?: continue
                    chapters += copied
                    filesImported++
                }
                if (chapters.isEmpty()) continue

                if (targetBookId != null) {
                    // #54 merge: attach the local source to the existing Work —
                    // no new card, chapters join the existing book's logical
                    // chapter list, and the tracks carry the copied files
                    // (ADR-0007: download state lives on tracks, never on
                    // chapter rows). The Edition is the existing book's
                    // rendition; a local source already present is reused so
                    // the merged tracks form one contiguous track list.
                    val existing = dao.getAudiobookById(targetBookId)
                    val baseIndex = existing?.totalChapters ?: 0
                    val storedEdition = dao.getEditionForWork(targetBookId)
                    val editionId = storedEdition?.id ?: EditionId.forBook(
                        existing?.mergeKey ?: "", targetBookId, existing?.narrator ?: ""
                    )
                    if (storedEdition == null) {
                        dao.insertEdition(
                            EditionEntity(
                                id = editionId,
                                workId = targetBookId,
                                narrator = existing?.narrator ?: "",
                                totalChapters = (baseIndex + chapters.size),
                                totalDurationSeconds = 0L
                            )
                        )
                    }
                    val localSource = dao.getSourcesForBookSync(targetBookId).firstOrNull { it.type == "local" }
                        ?: SourceEntity(
                            id = "local-$editionId",
                            bookId = targetBookId,
                            editionId = editionId,
                            type = "local",
                            url = "",
                            streamOnly = false,
                            addedAt = System.currentTimeMillis()
                        ).also { dao.insertSources(listOf(it)) }
                    dao.insertChapters(
                        chapters.mapIndexed { index, chapter ->
                            ChapterEntity(
                                id = "$targetBookId-ch${baseIndex + index + 1}",
                                bookId = targetBookId,
                                editionId = editionId,
                                chapterIndex = baseIndex + index,
                                title = chapter.title,
                                durationSeconds = 0L
                            )
                        }
                    )
                    dao.insertTracks(
                        chapters.mapIndexed { index, chapter ->
                            SourceTrackEntity(
                                id = MetadataAssertions.trackId(localSource.id, baseIndex + index),
                                sourceId = localSource.id,
                                trackIndex = baseIndex + index,
                                url = chapter.filePath,
                                localFilePath = chapter.filePath,
                                contentHash = chapter.contentHash,
                                isDownloaded = true
                            )
                        }
                    )
                } else {
                    insertLocalBook(
                        title = book.title.ifBlank { "Аудіокнига" },
                        author = book.author.ifBlank { LOCAL_FILE_AUTHOR },
                        description = "Імпортовано через прев'ю — ${chapters.size} файл(ів)",
                        chapters = chapters,
                        sourceTreeUri = sourceTreeUri
                    )
                    booksImported++
                }
            }

            // Persist the plan's corrections (MERGE / SPLIT / NEVER_MATCH /
            // FIELD) — the preview decisions become remembered, synced memory.
            for (correction in plan.corrections) {
                if (correction.mergeKey.isBlank()) continue
                dao.upsertCorrection(
                    CorrectionEntity(
                        mergeKey = correction.mergeKey,
                        kind = correction.kind,
                        value = correction.value,
                        origin = correction.origin
                    )
                )
            }

            LocalImportResult(
                booksImported = booksImported,
                filesImported = filesImported,
                skippedFiles = skippedFiles,
                duplicateFiles = duplicateFiles
            )
        }

    /**
     * Core of the local import (T7 single-file + Block 4 folder): groups the
     * scanned files and materialises them as books in Room.
     *
     * Grouping rule: files at the root of the picked tree become one
     * single-chapter book each (exactly like the single-file import); every
     * sub-folder becomes one multi-chapter book whose chapters are its audio
     * files sorted naturally by file name (track1 → track2 → … → track10).
     * Unreadable files are skipped without failing the whole import.
     *
     * Dedupe (wayfinder #48): a file whose bytes already exist in the library
     * is never copied again — the fresh copy is deleted on the spot and
     * counted in [LocalImportResult.duplicateFiles].
     */
    suspend fun importAudioEntries(entries: List<LocalAudioEntry>, sourceTreeUri: String? = null): LocalImportResult =
        withContext(Dispatchers.IO) {
            var booksImported = 0
            var filesImported = 0
            var skippedFiles = 0
            var duplicateFiles = 0
            // Hashes seen earlier in THIS run (same-folder repeated files), so
            // dedupe is consistent even before the folder's chapters hit the DB.
            val seenHashes = mutableSetOf<String>()

            // Copy-then-hash; when the bytes already exist, delete the copy
            // and report a duplicate instead of a new chapter. `baseName` is
            // the copied-file stem; `chapterTitle` is what users see.
            suspend fun copyUnlessDuplicate(
                baseName: String,
                chapterTitle: String,
                extension: String,
                openStream: () -> java.io.InputStream
            ): LocalChapterInput? {
                val dest = try {
                    copyLocalAudioStream(baseName, extension, openStream())
                } catch (e: Exception) {
                    Log.w("AudiobookRepo", "Local import failed", e)
                    skippedFiles++
                    return null
                }
                if (!seenHashes.add(dest.sha256Hex) || dao.getTrackByContentHash(dest.sha256Hex) != null) {
                    File(dest.path).delete()
                    duplicateFiles++
                    return null
                }
                return LocalChapterInput(title = chapterTitle, filePath = dest.path, contentHash = dest.sha256Hex)
            }

            // 1) Loose files at the tree root → one single-chapter book each.
            for (entry in entries.filter { it.parentFolder.isNullOrBlank() }) {
                val base = sanitizeLocalBaseName(entry.fileName)
                val chapter = copyUnlessDuplicate(base, base, localFileExtension(entry.fileName), entry.openStream)
                    ?: continue
                insertLocalBook(
                    title = base,
                    author = LOCAL_FILE_AUTHOR,
                    description = "Імпортований аудіофайл: ${entry.fileName}",
                    chapters = listOf(chapter),
                    sourceTreeUri = sourceTreeUri
                )
                booksImported++
                filesImported++
            }

            // 2) Each sub-folder → one book; files become naturally-sorted chapters.
            for ((folder, files) in entries.filter { !it.parentFolder.isNullOrBlank() }.groupBy { it.parentFolder }) {
                if (folder.isNullOrBlank()) continue
                // Title from the last path segment so a relative path like
                // "SeriesA/Кобзар" still yields a clean "Кобзар" book name.
                val bookTitle = sanitizeLocalBaseName(folder.substringAfterLast('/')).ifBlank { "Аудіокнига" }
                val chapters = mutableListOf<LocalChapterInput>()
                for (entry in files.sortedWith(Comparator { a, b -> compareNatural(a.fileName, b.fileName) })) {
                    val chapterTitle = sanitizeLocalBaseName(entry.fileName).ifBlank { entry.fileName }
                    val chapter = copyUnlessDuplicate("$bookTitle-$chapterTitle", chapterTitle, localFileExtension(entry.fileName), entry.openStream)
                        ?: continue
                    chapters.add(chapter)
                    filesImported++
                }
                if (chapters.isNotEmpty()) {
                    insertLocalBook(
                        title = bookTitle,
                        author = LOCAL_FOLDER_AUTHOR,
                        description = "Імпортовано з папки «$folder» — ${chapters.size} файл(ів)",
                        chapters = chapters,
                        sourceTreeUri = sourceTreeUri
                    )
                    booksImported++
                }
            }

            LocalImportResult(
                booksImported = booksImported,
                filesImported = filesImported,
                skippedFiles = skippedFiles,
                duplicateFiles = duplicateFiles
            )
        }

    /** Strips the extension and unsafe characters from a file/folder display name. */
    private fun sanitizeLocalBaseName(displayName: String): String {
        val cleanBase = displayName.substringBeforeLast('.').trim().ifBlank { displayName }
        return cleanBase
            .replace(Regex("""[^\p{L}\p{N} _\-]"""), "")
            .ifBlank { "audiobook-${System.currentTimeMillis()}" }
    }

    /** Original extension of an audio file (lowercased), defaulting to mp3. */
    private fun localFileExtension(fileName: String): String =
        fileName.substringAfterLast('.', "").ifBlank { "mp3" }.lowercase().take(5)

    /** Copies a stream into the private local-imports dir under a unique name. */
    private fun copyLocalAudioStream(baseName: String, extension: String, stream: java.io.InputStream): CopiedLocalFile {
        val ctx = context ?: throw IllegalStateException("local import requires Context")
        val audioDir = File(ctx.filesDir, LOCAL_AUDIO_DIR)
        if (!audioDir.exists()) audioDir.mkdirs()
        // Unique suffix (counter-based, unlike the old timestamp-only one) so
        // rapid folder imports never collide within the same millisecond. The
        // original extension is preserved so ExoPlayer detects the container.
        val destFile = File(audioDir, "$baseName-${localImportSeq.incrementAndGet()}.$extension")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        stream.use { input ->
            destFile.outputStream().use { output ->
                val buffer = ByteArray(HASH_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                }
            }
        }
        return CopiedLocalFile(path = destFile.absolutePath, sha256Hex = sha256Hex(digest.digest()))
    }

    /** Creates one local book with the given chapters (title, localFilePath). */
    private suspend fun insertLocalBook(
        title: String,
        author: String,
        description: String,
        chapters: List<LocalChapterInput>,
        sourceTreeUri: String? = null
    ): AudiobookEntity {
        val bookId = "local-${System.currentTimeMillis()}-${localImportSeq.incrementAndGet()}"
        // ADR-0009: downloadProgress is a Library Entry concern — written to
        // the entry row below (1f — a local copy is present by definition).
        val book = AudiobookEntity(
            id = bookId,
            title = title,
            author = author,
            narrator = "Локальний аудіофайл",
            description = description,
            coverDrawableRes = R.drawable.img_neuromancer_cover_1785247475170,
            coverImageUrl = null,
            genre = LOCAL_GENRE,
            sourceUrl = "",
            isDownloaded = true,
            totalDurationSeconds = 0L,
            totalChapters = chapters.size,
            rating = 0f,
            sourceTreeUri = sourceTreeUri
        )
        dao.insertAudiobooks(listOf(book))
        // ADR-0009: a local book is a Library Entry anchored to its own id
        // (blank identity — no Works row), carrying the download progress the
        // audiobooks row no longer holds.
        dao.upsertLibraryEntry(
            id = bookId,
            workId = bookId,
            isFavorite = false,
            createdAt = System.currentTimeMillis(),
            downloadProgress = 1f
        )
        // ADR-0007: a local import is a Source of type "local" whose tracks
        // carry the copied files; the Edition owns the logical chapter list.
        // ADR-0010: the edition id carries the rendition narrator.
        val editionId = EditionId.forBook(mergeKey = "", bookId = bookId, narrator = book.narrator)
        dao.insertEdition(
            EditionEntity(
                id = editionId,
                workId = bookId,
                narrator = book.narrator,
                totalChapters = chapters.size,
                totalDurationSeconds = 0L
            )
        )
        dao.insertChapters(
            chapters.mapIndexed { index, chapter ->
                ChapterEntity(
                    id = "${bookId}_ch${index + 1}",
                    bookId = bookId,
                    editionId = editionId,
                    chapterIndex = index,
                    title = chapter.title,
                    durationSeconds = 0L
                )
            }
        )
        val localSourceId = "local-$editionId"
        dao.insertSources(
            listOf(
                SourceEntity(
                    id = localSourceId,
                    bookId = bookId,
                    editionId = editionId,
                    type = "local",
                    url = "",
                    streamOnly = false,
                    addedAt = System.currentTimeMillis()
                )
            )
        )
        dao.insertTracks(
            chapters.mapIndexed { index, chapter ->
                SourceTrackEntity(
                    id = MetadataAssertions.trackId(localSourceId, index),
                    sourceId = localSourceId,
                    trackIndex = index,
                    url = chapter.filePath,
                    localFilePath = chapter.filePath,
                    contentHash = chapter.contentHash,
                    isDownloaded = true
                )
            }
        )
        // An explicit local import is a user action: any tombstone of the
        // work is cleared so a re-added book never stays hidden.
        dao.deleteTombstone(bookId)
        return book
    }

    /** Natural (human) file-name comparison: track2 < track10. */
    private fun compareNatural(a: String, b: String): Int {
        val chunksA = SPLIT_CHUNKS.findAll(a.lowercase()).map { it.value }.toList()
        val chunksB = SPLIT_CHUNKS.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(chunksA.size, chunksB.size)) {
            val ca = chunksA[i]
            val cb = chunksB[i]
            val cmp = if (ca.first().isDigit() && cb.first().isDigit()) {
                (ca.toLongOrNull() ?: 0L).compareTo(cb.toLongOrNull() ?: 0L)
            } else {
                ca.compareTo(cb)
            }
            if (cmp != 0) return cmp
        }
        return chunksA.size - chunksB.size
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------------
    // Door 4: re-scan of previously imported local folders
    // ---------------------------------------------------------------------

    /** Outcome of one [rescanLocalFolder] run — what changed in the tree. */
    data class RescanReport(
        val treeUri: String,
        val newChapters: Int = 0,
        val newBooks: Int = 0,
        val missingFiles: Int = 0,
        val movedFiles: Int = 0,
        val duplicateFiles: Int = 0
    )

    /**
     * Re-scans one previously imported SAF tree (wayfinder #42): walks it,
     * hashes every stream (no copies — files the library already knows never
     * touch disk), diffs against the stored chapters by content hash, applies
     * new files as chapters/books, and reports missing/moved/duplicate files.
     * The library entry and its private copies survive every outcome
     * (wayfinder #59): nothing here ever deletes a row or a file.
     */
    suspend fun rescanLocalFolder(treeUri: String): RescanReport = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext RescanReport(treeUri)
        val entries = runCatching { LocalFolderScanner.scan(ctx, Uri.parse(treeUri)) }.getOrElse {
            Log.w("AudiobookRepo", "Re-scan could not open tree $treeUri", it)
            return@withContext RescanReport(treeUri)
        }
        if (entries.isEmpty()) return@withContext RescanReport(treeUri)
        rescanAudioEntries(entries, treeUri)
    }

    /**
     * Testable core of the re-scan (wayfinder #42): hashes every stream (no
     * copies — files the library already knows never touch disk), diffs
     * against the stored chapters by content hash, applies new files as
     * chapters/books, and reports missing/moved/duplicate files. The library
     * entry and its private copies survive every outcome (wayfinder #59):
     * nothing here ever deletes a row or a file.
     */
    suspend fun rescanAudioEntries(entries: List<LocalAudioEntry>, treeUri: String): RescanReport =
        withContext(Dispatchers.IO) {
        // Hash every file once — pure stream read, the re-scan baseline.
        val scanned = entries.mapNotNull { entry ->
            val hash = runCatching { contentHashOf(entry.openStream()) }.getOrNull()
            if (hash.isNullOrBlank()) null else FolderRescan.RescanFile(entry.fileName, entry.parentFolder, hash)
        }
        if (scanned.isEmpty()) return@withContext RescanReport(treeUri)

        // ADR-0007: hashes live on the track rows — the library-wide dedupe
        // pool is every track's content hash.
        val libraryHashSet = dao.getAllTrackContentHashes().toSet()
        val existingBooks = dao.getAudiobooksBySourceTree(treeUri)
        var report = RescanReport(treeUri)

        // Same grouping as the import: root files are single-chapter books,
        // each sub-folder is one multi-chapter book by its last path segment.
        val groups = scanned.groupBy { file ->
            file.parentFolder?.let { "folder:$it" } ?: "root:${sanitizeLocalBaseName(file.fileName)}"
        }
        for ((groupKey, files) in groups) {
            val isRoot = groupKey.startsWith("root:")
            val title = if (isRoot) groupKey.removePrefix("root:")
            else files.first().parentFolder?.substringAfterLast('/')?.let { sanitizeLocalBaseName(it) }.orEmpty()
            val book = existingBooks.firstOrNull { it.title == title }

            if (book == null) {
                // A book the library doesn't know from this tree yet: copy its
                // files through the shared dedupe core, then create the book.
                val newInputs = mutableListOf<LocalChapterInput>()
                for (file in files) {
                    val entry = entries.first { it.fileName == file.fileName && it.parentFolder == file.parentFolder }
                    copyNewLocalChapter(entry, sanitizeLocalBaseName(file.fileName), file.contentHash)?.let { newInputs.add(it) }
                }
                if (newInputs.isEmpty()) {
                    report = report.copy(duplicateFiles = report.duplicateFiles + files.size)
                    continue
                }
                val created = insertLocalBook(
                    title = title,
                    author = LOCAL_FOLDER_AUTHOR,
                    description = "Імпортовано з папки «${files.first().parentFolder ?: title}» — ${newInputs.size} файл(ів)",
                    chapters = newInputs,
                    sourceTreeUri = treeUri
                )
                report = report.copy(
                    newBooks = report.newBooks + 1,
                    newChapters = report.newChapters + newInputs.size,
                    duplicateFiles = report.duplicateFiles + (files.size - newInputs.size)
                )
                updateFingerprintFor(created.id)
                continue
            }

            // Known book: diff its stored tracks (chapter titles + track
            // hashes) against this group's live files (ADR-0007: the physical
            // playback data lives on the tracks).
            val chapters = dao.getChaptersListForBook(book.id)
            val bookTracks = dao.getTracksForBookSync(book.id)
            val storedTracks = chapters.map { ch ->
                val track = bookTracks.firstOrNull { it.trackIndex == ch.chapterIndex }
                FolderRescan.StoredTrack(title = ch.title, contentHash = track?.contentHash)
            }
            val diff = FolderRescan.computeDiff(storedTracks, libraryHashSet, files)
            report = report.copy(
                missingFiles = report.missingFiles + diff.missingTracks.size,
                movedFiles = report.movedFiles + diff.movedFiles.size,
                duplicateFiles = report.duplicateFiles + diff.duplicateFiles.size
            )
            if (diff.newFiles.isNotEmpty()) {
                val newInputs = mutableListOf<LocalChapterInput>()
                for (file in diff.newFiles) {
                    val entry = entries.first { it.fileName == file.fileName && it.parentFolder == file.parentFolder }
                    copyNewLocalChapter(entry, sanitizeLocalBaseName(file.fileName), file.contentHash)?.let { newInputs.add(it) }
                }
                if (newInputs.isNotEmpty()) {
                    val merged = chapters.map { ch ->
                        val track = bookTracks.firstOrNull { it.trackIndex == ch.chapterIndex }
                        LocalChapterInput(
                            title = ch.title,
                            filePath = track?.localFilePath ?: track?.url.orEmpty(),
                            contentHash = track?.contentHash.orEmpty()
                        )
                    } + newInputs
                    rewriteBookChapters(book.id, merged)
                    report = report.copy(newChapters = report.newChapters + newInputs.size)
                    updateFingerprintFor(book.id)
                } else {
                    report = report.copy(duplicateFiles = report.duplicateFiles + diff.newFiles.size)
                }
            }
        }
        report
    }

    /**
     * Re-scans every previously imported local tree, best-effort per tree:
     * one dead SAF grant (moved folder) fails that tree alone, never the rest.
     */
    suspend fun rescanAllLocalFolders(): List<RescanReport> = withContext(Dispatchers.IO) {
        dao.getImportedSourceTrees().map { tree ->
            runCatching { rescanLocalFolder(tree) }.getOrElse {
                Log.w("AudiobookRepo", "Re-scan failed for $tree", it)
                RescanReport(tree)
            }
        }
    }

    /** Copies a NEW local file to private storage, deduped against the library. */
    private suspend fun copyNewLocalChapter(
        entry: LocalAudioEntry,
        chapterTitle: String,
        contentHash: String
    ): LocalChapterInput? {
        // The diff classified it new, but a concurrent import may have landed
        // the same bytes — never copy twice (ADR-0007: the hash lives on the
        // track rows).
        if (dao.getTrackByContentHash(contentHash) != null) return null
        val base = sanitizeLocalBaseName(entry.fileName)
        val dest = try {
            copyLocalAudioStream("$base-re${localImportSeq.incrementAndGet()}", localFileExtension(entry.fileName), entry.openStream())
        } catch (e: Exception) {
            Log.w("AudiobookRepo", "Re-scan copy failed for ${entry.fileName}", e)
            return null
        }
        return LocalChapterInput(title = chapterTitle, filePath = dest.path, contentHash = dest.sha256Hex)
    }

    /** Re-indexes a local book's chapters + tracks naturally (rebuild). */
    private suspend fun rewriteBookChapters(bookId: String, chapters: List<LocalChapterInput>) {
        val sorted = chapters.sortedWith(Comparator { a, b -> compareNatural(a.title, b.title) })
        val storedEdition = dao.getEditionForWork(bookId)
        val bookRow = dao.getAudiobookById(bookId)
        val editionId = storedEdition?.id ?: EditionId.forBook(
            bookRow?.mergeKey ?: "",
            bookId,
            bookRow?.narrator ?: ""
        )
        if (storedEdition == null) {
            dao.insertEdition(
                EditionEntity(
                    id = editionId,
                    workId = bookId,
                    narrator = bookRow?.narrator ?: "",
                    totalChapters = sorted.size,
                    totalDurationSeconds = 0L
                )
            )
        }
        dao.deleteChaptersForBook(bookId)
        dao.insertChapters(
            sorted.mapIndexed { index, ch ->
                ChapterEntity(
                    id = "${bookId}_ch${index + 1}",
                    bookId = bookId,
                    editionId = editionId,
                    chapterIndex = index,
                    title = ch.title,
                    durationSeconds = 0L
                )
            }
        )
        // Rebuild the local source's tracks from the new list (ADR-0007).
        val localSource = dao.getSourcesForBookSync(bookId).firstOrNull { it.type == "local" }
            ?: SourceEntity(
                id = "local-$editionId",
                bookId = bookId,
                editionId = editionId,
                type = "local",
                url = "",
                streamOnly = false,
                addedAt = System.currentTimeMillis()
            ).also { dao.insertSources(listOf(it)) }
        dao.deleteTracksForBook(bookId)
        dao.insertTracks(
            sorted.mapIndexed { index, ch ->
                SourceTrackEntity(
                    id = MetadataAssertions.trackId(localSource.id, index),
                    sourceId = localSource.id,
                    trackIndex = index,
                    url = ch.filePath,
                    localFilePath = ch.filePath,
                    contentHash = ch.contentHash.ifBlank { null },
                    isDownloaded = true
                )
            }
        )
        dao.updateBookStats(bookId, sorted.size, 0L)
    }

    /** Refreshes the local source's re-scan fingerprint from its stored tracks. */
    private suspend fun updateFingerprintFor(bookId: String) {
        val chapters = dao.getChaptersListForBook(bookId)
        val localSource = dao.getSourcesForBookSync(bookId).firstOrNull { it.type == "local" } ?: return
        val tracks = dao.getTracksForSourceSync(localSource.id)
        val fingerprint = chapters.mapNotNull { ch ->
            val track = tracks.firstOrNull { it.trackIndex == ch.chapterIndex } ?: return@mapNotNull null
            "${ch.title.lowercase()}|${track.contentHash.orEmpty()}"
        }
            .sorted()
            .joinToString("\n")
            .ifBlank { null }
            ?.let { sha256Hex(it.toByteArray()) }
        dao.updateSourceFingerprint(localSource.id, fingerprint)
    }

    companion object {
        /** Directory holding user-imported local audio files (spec #8 T7). */
        const val LOCAL_AUDIO_DIR = "local_imports"

        /** Author/genre labels for locally-imported books. */
        private const val LOCAL_FILE_AUTHOR = "Локальний файл"
        private const val LOCAL_FOLDER_AUTHOR = "Локальна папка"
        private const val LOCAL_GENRE = "Локальні"

        /** Monotonic counter guaranteeing unique local ids/names within a burst of imports. */
        private val localImportSeq = java.util.concurrent.atomic.AtomicInteger(0)

        /** Splits a file name into numeric and non-numeric chunks for natural sorting. */
        private val SPLIT_CHUNKS = Regex("""\d+|\D+""")
    }
}

/** Outcome of a local folder/file import (spec #8 Block 4). */
data class LocalImportResult(
    val booksImported: Int,
    val filesImported: Int,
    val skippedFiles: Int,
    // wayfinder #48: files whose bytes already existed in the library; they
    // were never copied, so no storage was consumed.
    val duplicateFiles: Int = 0
)

/**
 * A local audio file materialised into the library (wayfinder #48): the
 * chapter title, the copied file path, and the SHA-256 that made the copy
 * dedupe-able against earlier imports.
 */
data class LocalChapterInput(
    val title: String,
    val filePath: String,
    val contentHash: String
)

/** A local file copied into private storage, with its content digest. */
data class CopiedLocalFile(
    val path: String,
    val sha256Hex: String
)

/**
 * Spec-32 T3 (#233) — the Work identity a search/catalogue card already
 * carries, passed to the import so the shared profile (keyed by Edition id,
 * which derives from title|author|narrator) can be consulted instead of
 * fetching the source page. Callers without the card pass null and the live
 * fetch runs exactly as before.
 */
data class KnownBookIdentity(
    val title: String,
    val author: String,
    val narrator: String = "",
    // The card's own cover (the feed already parsed it) — carried into the
    // import as a fallback for sources whose book PAGE has no cover signal
    // (audiobook-mp3: the cover lives on the listing tile, not the page).
    val coverImageUrl: String? = null
)
