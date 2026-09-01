package com.slukhayka.audiobooks.data.authors

import com.slukhayka.audiobooks.data.db.AuthorAliasEntity
import com.slukhayka.audiobooks.data.db.AuthorFacetEntity
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.db.WorkFacetEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AuthorSummary(
    val id: String,
    val displayName: String,
    val normalizedName: String,
    val workCount: Int
)

interface AuthorIndex {
    val authors: Flow<List<AuthorSummary>>

    suspend fun search(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<AuthorSummary>
    suspend fun works(authorId: String): List<WorkEntity>
    suspend fun authorForWork(workId: String): AuthorSummary?
    suspend fun indexWorks(works: List<WorkEntity>, sourceId: String)
    suspend fun applyAssertion(
        canonicalId: String,
        displayName: String,
        aliases: List<String>,
        workIds: Set<String>,
        sourceId: String,
        observedAt: Long
    )

    companion object {
        const val DEFAULT_SEARCH_LIMIT = 6
        const val MAX_FULL_LIST = 10_000
        const val MAX_INDEX_BATCH = 500
    }
}

/** Room-backed canonical author read model; all interaction queries stay local. */
class RoomAuthorIndex(
    private val dao: AudiobookDao,
    private val backfillScope: CoroutineScope = BACKFILL_SCOPE
) : AuthorIndex {
    @Volatile private var backfillChecked = false
    private val backfillMutex = Mutex()

    override val authors: Flow<List<AuthorSummary>> = flow {
        ensureBackfilled()
        emitAll(dao.observeAuthorIndex())
    }

    override suspend fun search(query: String, limit: Int): List<AuthorSummary> {
        val normalized = AuthorIdentity.searchableQuery(query) ?: return emptyList()
        ensureBackfilled()
        return dao.searchAuthors(
            lowerBound = normalized,
            upperBound = "$normalized\uFFFF",
            limit = limit.coerceIn(1, AuthorIndex.MAX_FULL_LIST)
        )
    }

    override suspend fun works(authorId: String): List<WorkEntity> {
        ensureBackfilled()
        return dao.worksForAuthor(authorId)
    }

    override suspend fun authorForWork(workId: String): AuthorSummary? {
        ensureBackfilled()
        return dao.authorForWork(workId)
    }

    override suspend fun indexWorks(works: List<WorkEntity>, sourceId: String) {
        require(sourceId.isNotBlank() && sourceId.length <= 80)
        works.chunked(AuthorIndex.MAX_INDEX_BATCH).forEach { batch ->
            val authors = linkedMapOf<String, AuthorFacetEntity>()
            val aliases = linkedSetOf<AuthorAliasEntity>()
            val workFacets = mutableListOf<WorkFacetEntity>()
            batch.forEach workLoop@ { work ->
                val identity = runCatching { AuthorIdentity.fromWorkName(work.author) }.getOrNull()
                    ?: return@workLoop
                authors[identity.id] = AuthorFacetEntity(
                    identity.id,
                    identity.displayName,
                    identity.normalizedName,
                    updatedAt = 0
                )
                aliases += searchAliasRows(identity.id, identity.displayName, sourceId, observedAt = 0)
                workFacets += WorkFacetEntity(work.id, identity.id, updatedAt = 0)
            }
            dao.applyAuthorIndexRows(authors.values.toList(), aliases.toList(), workFacets)
        }
    }

    override suspend fun applyAssertion(
        canonicalId: String,
        displayName: String,
        aliases: List<String>,
        workIds: Set<String>,
        sourceId: String,
        observedAt: Long
    ) {
        require(workIds.size <= AuthorIndex.MAX_INDEX_BATCH)
        val assertion = AuthorIdentity.fromAssertion(
            canonicalId = canonicalId,
            displayName = displayName,
            aliases = aliases,
            sourceId = sourceId,
            observedAt = observedAt
        )
        val searchRows = assertion.aliases
            .sortedByDescending { it.normalizedAlias == assertion.author.normalizedName }
            .flatMap { claim ->
                searchAliasRows(
                    authorId = claim.authorId,
                    rawAlias = claim.rawAlias,
                    sourceId = claim.sourceId,
                    observedAt = claim.observedAt
                )
            }
            .distinctBy { Triple(it.authorId, it.normalizedAlias, it.sourceId) }
            .take(AuthorIdentity.MAX_ALIASES)
        dao.applyAuthorIndexRows(
            authors = listOf(
                AuthorFacetEntity(
                    assertion.author.id,
                    assertion.author.displayName,
                    assertion.author.normalizedName,
                    observedAt
                )
            ),
            aliases = searchRows,
            works = workIds.map { WorkFacetEntity(it, canonicalId, observedAt) }
        )
    }

    private fun searchAliasRows(
        authorId: String,
        rawAlias: String,
        sourceId: String,
        observedAt: Long
    ): List<AuthorAliasEntity> = AuthorIdentity.searchKeys(rawAlias).map { searchKey ->
        AuthorAliasEntity(authorId, searchKey, rawAlias.take(AuthorIdentity.MAX_NAME_LENGTH), sourceId, observedAt)
    }

    private suspend fun ensureBackfilled() {
        if (backfillChecked) return
        val hasMore = backfillMutex.withLock {
            if (backfillChecked) return
            val page = dao.worksMissingCanonicalAuthor(BACKFILL_BATCH_SIZE)
            indexWorks(page, sourceId = "local-backfill")
            backfillChecked = true
            page.size == BACKFILL_BATCH_SIZE
        }
        if (hasMore) {
            backfillScope.launch {
                // The first local read has a strict bounded-work contract:
                // it repairs one page and returns. Without a scheduling
                // boundary an IO dispatcher can begin the next page before
                // that caller observes its result, making latency and the
                // visible count race each other. One dispatcher tick keeps
                // continuation truly background while preserving immediate
                // eventual repair (including virtual-time tests).
                delay(BACKFILL_CONTINUATION_DELAY_MS)
                while (repairBackfillPage()) Unit
            }
        }
    }

    private suspend fun repairBackfillPage(): Boolean = backfillMutex.withLock {
        val page = dao.worksMissingCanonicalAuthor(BACKFILL_BATCH_SIZE)
        indexWorks(page, sourceId = "local-backfill")
        page.size == BACKFILL_BATCH_SIZE
    }

    companion object {
        const val BACKFILL_BATCH_SIZE = AuthorIndex.MAX_INDEX_BATCH
        private const val BACKFILL_CONTINUATION_DELAY_MS = 1L
        private val BACKFILL_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
