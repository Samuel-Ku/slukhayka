package com.slukhayka.audiobooks.data.facets

import android.content.Context
import com.slukhayka.audiobooks.data.metadata.FacetAssertion
import com.slukhayka.audiobooks.data.metadata.FacetCompleteness
import com.slukhayka.audiobooks.data.metadata.FacetCursor
import com.slukhayka.audiobooks.data.metadata.FacetPageLimits
import com.slukhayka.audiobooks.data.metadata.FacetPerson
import com.slukhayka.audiobooks.data.metadata.SharedBookMetaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Last remote page fully committed to the local facet projection. */
interface FacetSyncCursorStore {
    fun load(): FacetCursor?
    fun save(cursor: FacetCursor)
}

class InMemoryFacetSyncCursorStore(
    initial: FacetCursor? = null
) : FacetSyncCursorStore {
    private var cursor = initial

    override fun load(): FacetCursor? = cursor

    override fun save(cursor: FacetCursor) {
        this.cursor = cursor
    }
}

/** Durable high-water mark without coupling the sync lane to Room schema. */
class SharedPreferencesFacetSyncCursorStore(context: Context) : FacetSyncCursorStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): FacetCursor? {
        if (!prefs.contains(KEY_UPDATED_AT) || !prefs.contains(KEY_DOCUMENT_ID)) return null
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, -1)
        val documentId = prefs.getString(KEY_DOCUMENT_ID, null).orEmpty()
        return FacetCursor(updatedAt, documentId)
            .takeIf { it.updatedAt >= 0 && it.documentId.isNotBlank() }
    }

    override fun save(cursor: FacetCursor) {
        require(cursor.updatedAt >= 0 && cursor.documentId.isNotBlank())
        check(
            prefs.edit()
                .putLong(KEY_UPDATED_AT, cursor.updatedAt)
                .putString(KEY_DOCUMENT_ID, cursor.documentId)
                .commit()
        ) { "Facet sync cursor was not persisted" }
    }

    companion object {
        internal const val PREFS_NAME = "facet_sync_cursor"
        private const val KEY_UPDATED_AT = "updated_at"
        private const val KEY_DOCUMENT_ID = "document_id"
    }
}

/** Applies one bounded shared facet page through the frozen local writer. */
class FacetDeltaSync(
    private val sharedStore: SharedBookMetaStore,
    private val localWriter: LocalFacetWriter,
    private val cursorStore: FacetSyncCursorStore,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val syncMutex = Mutex()

    data class ChainResult(
        val pagesApplied: Int,
        val assertionsApplied: Int
    )

    sealed interface PageResult {
        data class Applied(val assertionCount: Int) : PageResult
        data object NoChanges : PageResult
        data object Failed : PageResult
    }

    suspend fun syncPage(pageSize: Int = FacetPageLimits.MAX_PAGE_SIZE): PageResult =
        syncMutex.withLock { syncPageLocked(pageSize) }

    private suspend fun syncPageLocked(pageSize: Int): PageResult {
        return try {
            val page = sharedStore.getFacetPage(cursorStore.load(), FacetPageLimits.bounded(pageSize))
            val nextCursor = page.nextCursor ?: return PageResult.NoChanges
            val deltas = page.assertions.mapNotNull(::toLocalDelta)
            if (deltas.isNotEmpty()) localWriter.apply(deltas)
            withContext(Dispatchers.IO) { cursorStore.save(nextCursor) }
            PageResult.Applied(deltas.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PageResult.Failed
        }
    }

    suspend fun syncAvailablePages(
        pageSize: Int = FacetPageLimits.MAX_PAGE_SIZE,
        maxPages: Int = MAX_PAGES_PER_SESSION
    ): ChainResult = syncMutex.withLock {
        var pagesApplied = 0
        var assertionsApplied = 0
        repeat(maxPages.coerceIn(0, MAX_PAGES_PER_SESSION)) {
            when (val result = syncPageLocked(pageSize)) {
                is PageResult.Applied -> {
                    pagesApplied++
                    assertionsApplied += result.assertionCount
                }

                PageResult.NoChanges,
                PageResult.Failed -> return ChainResult(pagesApplied, assertionsApplied)
            }
        }
        return ChainResult(pagesApplied, assertionsApplied)
    }

    private fun toLocalDelta(assertion: FacetAssertion): LocalFacetDelta? {
        return when (assertion) {
            is FacetAssertion.Work -> {
                if (!hasMaterializableWorkIdentity(assertion)) return null
                val author = assertion.author?.takeIf(::isMaterializableAuthor)
                LocalFacetDelta(
                    work = WorkFacetDelta(
                        workId = assertion.workId,
                        genreSourceReplacements = listOf(
                            GenreSourceFacetReplacement(
                                sourceId = assertion.sourceId,
                                documentUpdatedAt = assertion.updatedAt,
                                assertions = assertion.genres.mapNotNull { genre ->
                                    if (genre.id.length > 80 ||
                                        GenreIdentity.fromCanonical(genre.id, genre.rawText) == null
                                    ) return@mapNotNull null
                                    CanonicalGenreFacetAssertion(
                                        genreId = genre.id,
                                        rawText = genre.rawText,
                                        sourceId = assertion.sourceId,
                                        observedAt = assertion.observedAt,
                                        assertionId = FacetIdentity.boundedId(
                                            "shared-genre",
                                            "${assertion.documentId}|${genre.id}"
                                        ),
                                        documentUpdatedAt = assertion.updatedAt
                                    )
                                }
                            )
                        ),
                        canonicalAuthorId = author?.id,
                        seriesIds = assertion.seriesMemberships
                            .mapNotNullTo(linkedSetOf()) { membership ->
                                membership.seriesId.takeIf { it.length <= 80 }
                            },
                        updatedAt = assertion.updatedAt
                    ),
                    authors = author?.let { localAuthor ->
                        listOf(
                            AuthorFacetDelta(
                                authorId = localAuthor.id,
                                displayName = localAuthor.name,
                                aliases = localAuthor.aliases.mapNotNull { alias ->
                                    alias.takeIf { it.length <= 120 }?.let {
                                        AuthorAliasDelta(
                                            rawText = it,
                                            sourceId = assertion.sourceId,
                                            observedAt = assertion.observedAt
                                        )
                                    }
                                },
                                updatedAt = assertion.updatedAt
                            )
                        )
                    }.orEmpty()
                )
            }

            is FacetAssertion.Edition -> {
                if (!hasMaterializableEditionIdentity(assertion)) return null
                val freshAvailability = assertion.availability?.takeIf { it.isFreshAt(nowMillis()) }
                LocalFacetDelta(
                    work = WorkFacetDelta(
                        workId = assertion.workId,
                        updatedAt = assertion.updatedAt
                    ),
                    editions = listOf(
                        EditionFacetDelta(
                            editionId = assertion.editionId,
                            workId = assertion.workId,
                            narratorId = assertion.narrator?.id?.takeIf { it.length <= 80 },
                            language = assertion.language?.takeIf { it.length <= 24 },
                            durationBucketId = assertion.durationBucket?.wireName?.takeIf { it.length <= 40 },
                            chapterCount = assertion.chapterCount,
                            isAbridged = assertion.completeness?.let { it == FacetCompleteness.ABRIDGED },
                            availabilityAvailable = freshAvailability?.available,
                            availabilityObservedAtMillis = freshAvailability?.observedAt,
                            availabilityTtlSeconds = freshAvailability?.ttlSeconds,
                            updatedAt = assertion.updatedAt
                        )
                    )
                )
            }
        }
    }

    private fun hasMaterializableWorkIdentity(assertion: FacetAssertion.Work): Boolean =
        assertion.workId.isNotBlank() && assertion.workId.length <= 240 &&
            assertion.sourceId.isNotBlank() && assertion.sourceId.length <= 80 &&
            assertion.observedAt >= 0 && assertion.updatedAt >= 0

    private fun hasMaterializableEditionIdentity(assertion: FacetAssertion.Edition): Boolean =
        assertion.editionId.isNotBlank() && assertion.editionId.length <= 240 &&
            assertion.workId.isNotBlank() && assertion.workId.length <= 240 &&
            assertion.sourceId.isNotBlank() && assertion.sourceId.length <= 80 &&
            assertion.observedAt >= 0 && assertion.updatedAt >= 0

    private fun isMaterializableAuthor(author: FacetPerson): Boolean =
        author.id.isNotBlank() && author.id.length <= 80 &&
            author.name.length <= 120 && FacetIdentity.normalizedText(author.name) != null

    private companion object {
        const val MAX_PAGES_PER_SESSION = 20
    }
}
