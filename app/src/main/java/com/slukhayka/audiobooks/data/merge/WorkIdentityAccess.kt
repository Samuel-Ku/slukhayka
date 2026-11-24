package com.slukhayka.audiobooks.data.merge

import androidx.room.withTransaction
import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.ChapterEntity
import com.slukhayka.audiobooks.data.db.SourceTrackEntity
import com.slukhayka.audiobooks.data.db.BookRow
import com.slukhayka.audiobooks.data.db.LibraryEntryEntity
import com.slukhayka.audiobooks.data.db.EditionEntity
import com.slukhayka.audiobooks.data.db.SourceEntity
import com.slukhayka.audiobooks.data.db.TombstoneEntity
import java.util.Collections
import com.slukhayka.audiobooks.data.db.WorkSourceEntity
import com.slukhayka.audiobooks.data.db.WorkEntity
import com.slukhayka.audiobooks.data.LanguageCode
import com.slukhayka.audiobooks.data.imports.ChapterOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.slukhayka.audiobooks.data.metadata.MetadataAssertions

/** Opaque Work IDs and bibliographic merge keys are separate namespaces. */
sealed interface WorkIdentityInput {
    val value: String
    data class WorkId(override val value: String) : WorkIdentityInput
    data class MergeKey(override val value: String) : WorkIdentityInput
}

sealed interface WorkResolution {
    data class Resolved(val work: WorkEntity) : WorkResolution
    data class Missing(val input: WorkIdentityInput) : WorkResolution
    data class Refused(val reason: IdentityRefusal) : WorkResolution
}

enum class IdentityRefusal { MalformedIdentity, Cycle, Dangling, ConflictingAlias, AliasLimit }

sealed interface IdentityWriteRefusal {
    data class Alias(val reason: IdentityRefusal) : IdentityWriteRefusal
    data object AmbiguousSource : IdentityWriteRefusal
    data object DanglingSource : IdentityWriteRefusal
    data object ConflictingSource : IdentityWriteRefusal
    data object StaleAdmission : IdentityWriteRefusal
    data object Tombstoned : IdentityWriteRefusal
}

class WorkIdentityWriteRefused(val refusal: IdentityWriteRefusal) : IllegalStateException("Work identity write refused: $refusal")

internal data class ExistingSourceIdentity(
    val source: SourceEntity, val book: BookRow, val edition: EditionEntity,
    val firstMaterialization: Boolean = false,
)
internal data class SourceWorkIdentity(
    val work: WorkEntity?,
    val existingSource: ExistingSourceIdentity?,
    val importBook: BookRow? = null,
    val importEdition: EditionEntity? = null,
)

/** Resolves stored identity in the same Room instance used by manual commands. */
class WorkIdentityAccess(
    private val database: AudiobookDatabase,
    private val gate: ManualWorkMergeGate,
) {
    init { gate.requireDatabase(database) }

    internal fun requireDao(expected: AudiobookDao) = gate.requireDao(expected)

    suspend fun resolve(input: WorkIdentityInput): WorkResolution = database.withTransaction {
        resolveStored(input)
    }

    internal suspend fun resolveSource(
        mergeKey: String,
        sourceId: String,
        sourceUrl: String,
        narrator: String,
        language: String,
        fetchedChapterTitles: List<String>? = null,
    ): SourceWorkIdentity = database.withTransaction {
        resolveSourceStored(mergeKey, sourceId, sourceUrl, narrator, language, fetchedChapterTitles)
    }

    /** Book-resource evidence is private to this Access and never aliases a Work ID. */
    internal class SourceImportAdmission internal constructor(
        internal val owner: WorkIdentityAccess,
        internal val sourceId: String,
        internal val sourceUrl: String,
        internal val scope: SourceImportScope?,
        internal val startTombstones: Map<String, TombstoneEntity>,
        internal val activityScope: SourceActivityScope? = null,
    )

    /** Catalog claims establish a Work lease without inventing a physical Source/Edition. */
    internal data class SourceActivityScope(
        val workId: String,
        val claims: List<WorkSourceEntity>,
        val bookIds: List<String>,
    )

    internal data class SourceImportScope(
        val workId: String?,
        val source: SourceEntity?,
        val edition: EditionEntity?,
        val fallbackBookId: String?,
        val fallbackEdition: EditionEntity?,
        val chapters: List<ChapterEntity>,
        val tracks: List<SourceTrackEntity>,
        val bookIds: List<String>,
    )

    /** Every attempt captures deletion state before profile/network work, including unknown identities. */
    internal suspend fun <T> withKnownSourceImport(
        sourceId: String, sourceUrl: String,
        action: suspend (SourceImportAdmission?) -> T,
    ): T {
        val admission = database.withTransaction {
            val tombstones = readTombstonesAtStart()
            val identity = resolveExactSourceStored(sourceId, sourceUrl)
            SourceImportAdmission(this@WorkIdentityAccess, sourceId, sourceUrl,
                if (identity.existingSource == null) null else importScope(identity), tombstones,
                sourceActivityScope(sourceId, sourceUrl, identity))
        }
        val knownScope = admission.activityScope ?: return action(admission)
        val activity = gate.registerIdentityActivity(listOf(knownScope.workId), knownScope.bookIds)
        try {
            gate.withIdentityWrite(listOf(knownScope.workId), knownScope.bookIds) {
                database.withTransaction {
                    val identity = resolveExactSourceStored(sourceId, sourceUrl)
                    if (admission.scope != null) requireImportScope(admission, identity)
                    requireActivityScope(admission, identity)
                }
            }
            return action(admission)
        } finally {
            activity.close()
        }
    }

    /** Existing owner evidence stays nullable; a new Work target is a separate write role. */
    internal data class AdmittedSourceImport(
        val identity: SourceWorkIdentity,
        val creationWorkId: String?,
    )

    private data class SourceImportWriteScope(
        val source: SourceImportScope,
        val creationWorkId: String?,
    ) {
        val workIds: List<String> get() = listOfNotNull(source.workId, creationWorkId).distinct()
    }

    /** Rows and permitted observed-old deletion restore are one transaction; publish only afterwards. */
    internal suspend fun <T> writeSourceImport(
        admission: SourceImportAdmission?,
        mergeKey: String, sourceId: String, sourceUrl: String,
        narrator: String, language: String, fetchedChapterTitles: List<String>,
        prospectiveBookId: String, fallbackEditionIds: List<String>, restoreObservedTombstones: Boolean,
        rows: suspend (AdmittedSourceImport) -> T,
        afterCommit: suspend (T) -> Unit,
    ): T {
        val attempt = admission ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.StaleAdmission)
        if (attempt.owner !== this || attempt.sourceId != sourceId || attempt.sourceUrl != sourceUrl) {
            throw WorkIdentityWriteRefused(IdentityWriteRefusal.StaleAdmission)
        }
        val discoveredAfterFetch = attempt.scope == null
        val (scoped, writeScope) = database.withTransaction {
            val identity = resolveImportSourceStored(mergeKey, sourceId, sourceUrl, narrator, language, fetchedChapterTitles, fallbackEditionIds)
            val scoped = if (!discoveredAfterFetch) attempt else SourceImportAdmission(
                this@WorkIdentityAccess, sourceId, sourceUrl, importScope(identity, prospectiveBookId),
                attempt.startTombstones, attempt.activityScope)
            requireImportScope(scoped, identity, if (discoveredAfterFetch) prospectiveBookId else null)
            scoped to SourceImportWriteScope(checkNotNull(scoped.scope), importCreationWorkId(identity, mergeKey))
        }
        // Unknown identity gets no prefetch lease; its actual creation role is known only after detail.
        val activity = if (discoveredAfterFetch)
            gate.registerIdentityActivity(writeScope.workIds, writeScope.source.bookIds) else null
        try {
            val committed = gate.withIdentityWrite(writeScope.workIds, writeScope.source.bookIds) {
                database.withTransaction {
                    val identity = resolveImportSourceStored(mergeKey, sourceId, sourceUrl, narrator, language, fetchedChapterTitles, fallbackEditionIds)
                    requireImportScope(scoped, identity, if (discoveredAfterFetch) prospectiveBookId else null)
                    val current = SourceImportWriteScope(
                        importScope(identity, if (discoveredAfterFetch) prospectiveBookId else null),
                        importCreationWorkId(identity, mergeKey))
                    if (current != writeScope) throw WorkIdentityWriteRefused(IdentityWriteRefusal.StaleAdmission)
                    if (scoped.activityScope != null) {
                        requireActivityScope(scoped, resolveExactSourceStored(sourceId, sourceUrl))
                        if (identity.work?.id != scoped.activityScope.workId) {
                            throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
                        }
                    }
                    admitTombstones(scoped, identity, mergeKey, prospectiveBookId, restoreObservedTombstones)
                    val result = rows(AdmittedSourceImport(identity, writeScope.creationWorkId))
                    currentCoroutineContext().ensureActive()
                    result
                }
            }
            afterCommit(committed)
            return committed
        } finally {
            activity?.close()
        }
    }

    private suspend fun importCreationWorkId(identity: SourceWorkIdentity, mergeKey: String): String? {
        val selectedBook = identity.existingSource?.book ?: identity.importBook
        if (selectedBook != null || identity.work != null || mergeKey.isBlank()) return null
        // An unoccupied raw PK is mandatory even if its MergeKey namespace resolves Missing.
        if (database.audiobookDao().getWorkById(mergeKey) != null) {
            throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
        }
        when (val target = resolveStored(WorkIdentityInput.WorkId(mergeKey))) {
            is WorkResolution.Missing -> return mergeKey
            is WorkResolution.Resolved -> throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
            is WorkResolution.Refused -> throw WorkIdentityWriteRefused(IdentityWriteRefusal.Alias(target.reason))
        }
    }

    /** Exact opaque claim rows are evidence, never an Edition-ID recipe or source-wide lease. */
    private suspend fun sourceActivityScope(
        sourceId: String, sourceUrl: String, exact: SourceWorkIdentity,
    ): SourceActivityScope? {
        val dao = database.audiobookDao()
        val claims = dao.getWorkSourcesByIdentity(sourceId, sourceUrl)
        if (claims.size > MAX_KNOWN_SOURCE_CLAIMS) {
            throw WorkIdentityWriteRefused(IdentityWriteRefusal.AmbiguousSource)
        }
        val owners = linkedSetOf<String>()
        exact.work?.id?.let(owners::add)
        for (claim in claims) {
            val work = resolvedWork(WorkIdentityInput.WorkId(claim.workId))
                ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.DanglingSource)
            owners += work.id
        }
        if (owners.size > 1) throw WorkIdentityWriteRefused(IdentityWriteRefusal.AmbiguousSource)
        val owner = owners.singleOrNull() ?: return null
        val work = resolvedWork(WorkIdentityInput.WorkId(owner))
            ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.DanglingSource)
        val books = importScope(exact.copy(work = work)).bookIds
        return SourceActivityScope(owner, claims.toList(), books)
    }

    private suspend fun requireActivityScope(admission: SourceImportAdmission, exact: SourceWorkIdentity) {
        if (admission.owner !== this || admission.activityScope !=
            sourceActivityScope(admission.sourceId, admission.sourceUrl, exact)) {
            throw WorkIdentityWriteRefused(IdentityWriteRefusal.StaleAdmission)
        }
    }

    internal sealed interface CatalogPosterTarget {
        data class ExistingWork(val work: WorkEntity) : CatalogPosterTarget
        data class CreateWork(val id: String, val mergeKey: String) : CatalogPosterTarget
        data class BookOnly(val bookId: String) : CatalogPosterTarget

        val entryWorkId: String get() = when (this) {
            is ExistingWork -> work.id
            is CreateWork -> id
            is BookOnly -> bookId
        }

        /** A different stored URL may carry an owner the incoming exact pair has not resolved. */
        fun requireRepairSourceUrl(book: BookRow, sourceUrl: String) {
            if (book.sourceUrl.isNotBlank() && book.sourceUrl != sourceUrl) {
                throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
            }
        }

        /** Only a metadata partial write with no actual Entry may use this repair. */
        suspend fun requireMetadataOnlyRepair(dao: AudiobookDao, book: BookRow, mergeKey: String, sourceUrl: String) {
            requireRepairSourceUrl(book, sourceUrl)
            val books = listOf(book.id)
            if (MergeKey.keyFor(book.title, book.author) != mergeKey || !book.sourceTreeUri.isNullOrBlank() ||
                dao.manualMergeEditions(books, emptyList()).isNotEmpty() ||
                dao.manualMergeSources(books, emptyList()).isNotEmpty() ||
                dao.manualMergeChapters(books, emptyList()).isNotEmpty() ||
                dao.manualMergeProgress(books, emptyList()).isNotEmpty() ||
                dao.manualMergeBookmarks(books, emptyList()).isNotEmpty() ||
                dao.manualMergeReadthroughs(emptyList(), books, emptyList()).isNotEmpty() ||
                dao.manualMergeEvents(books).isNotEmpty() || dao.manualMergeFailures(books).isNotEmpty() ||
                dao.manualMergeSubmissions(books, emptyList()).isNotEmpty()) {
                throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
            }
        }

        /** Raw legacy metadata, not the nullable joined Work projection, establishes this repair. */
        fun matchesLegacySelfAnchor(book: BookRow, entry: LibraryEntryEntity, mergeKey: String): Boolean {
            if (entry.id != book.id || entry.workId != book.id ||
                MergeKey.keyFor(book.title, book.author) != mergeKey) return false
            return when (this) {
                is ExistingWork -> mergeKey.isNotBlank() && work.mergeKey == mergeKey
                is CreateWork -> mergeKey.isNotBlank() && this.mergeKey == mergeKey
                is BookOnly -> bookId == book.id && mergeKey.isBlank()
            }
        }
    }

    private data class CatalogPosterScope(
        val target: CatalogPosterTarget,
        val identity: SourceWorkIdentity,
        val source: SourceImportScope,
        val posterBookId: String,
        val posterBook: BookRow?,
        val posterEntry: LibraryEntryEntity?,
    ) {
        val workIds: List<String> get() = when (val owner = target) {
            is CatalogPosterTarget.ExistingWork -> listOf(owner.work.id)
            is CatalogPosterTarget.CreateWork -> listOf(owner.id)
            is CatalogPosterTarget.BookOnly -> emptyList()
        }
        val bookIds: List<String> get() = (source.bookIds + posterBookId).distinct()
    }

    /** A poster owns metadata rows only; a blank key never invents a Work or audio topology. */
    internal suspend fun <T> writeCatalogPoster(
        mergeKey: String, sourceId: String, sourceUrl: String, posterBookId: String,
        rows: suspend (CatalogPosterTarget) -> T,
    ): T {
        val discovered = database.withTransaction {
            catalogPosterScope(mergeKey, sourceId, sourceUrl, posterBookId)
        }
        return gate.withIdentityWrite(discovered.workIds, discovered.bookIds) {
            database.withTransaction {
                val current = catalogPosterScope(mergeKey, sourceId, sourceUrl, posterBookId)
                if (current != discovered) throw WorkIdentityWriteRefused(IdentityWriteRefusal.StaleAdmission)
                val keys = relevantTombstoneKeys(current.identity, mergeKey, posterBookId) + posterBookId
                if (keys.toList().chunked(128).any { database.audiobookDao().manualMergeTombstones(it).isNotEmpty() }) {
                    throw WorkIdentityWriteRefused(IdentityWriteRefusal.Tombstoned)
                }
                val result = rows(current.target)
                currentCoroutineContext().ensureActive()
                result
            }
        }
    }

    private suspend fun catalogPosterScope(
        mergeKey: String, sourceId: String, sourceUrl: String, posterBookId: String,
    ): CatalogPosterScope {
        val dao = database.audiobookDao()
        // The old physical deletion marker remains a nullable no-op even when ownership also conflicts.
        if (dao.isBookTombstoned(posterBookId)) throw WorkIdentityWriteRefused(IdentityWriteRefusal.Tombstoned)
        val identity = if (sourceUrl.isBlank()) {
            SourceWorkIdentity(mergeKey.takeIf(String::isNotBlank)?.let {
                resolvedWork(WorkIdentityInput.MergeKey(it))
            }, null)
        } else {
            val exact = resolveSourceStored(mergeKey, sourceId, sourceUrl, "", "", null)
            val owner = sourceActivityScope(sourceId, sourceUrl, exact)?.workId?.let { id ->
                resolvedWork(WorkIdentityInput.WorkId(id))
                    ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.DanglingSource)
            }
            if (owner == null) exact else exact.copy(work = owner)
        }
        val target = identity.work?.let(CatalogPosterTarget::ExistingWork) ?: if (mergeKey.isNotBlank()) {
            val id = importCreationWorkId(identity, mergeKey)
                ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.StaleAdmission)
            CatalogPosterTarget.CreateWork(id, mergeKey)
        } else {
            requireMissingPosterWorkId(posterBookId)
            CatalogPosterTarget.BookOnly(posterBookId)
        }
        val posterBook = dao.getAudiobookById(posterBookId)
        val posterEntry = dao.libraryEntryById(posterBookId)
        val legacySelfAnchor = posterBook != null && posterEntry != null &&
            target.matchesLegacySelfAnchor(posterBook, posterEntry, mergeKey) && dao.getWorkById(posterBookId) == null
        val source = if (legacySelfAnchor) {
            target.requireRepairSourceUrl(checkNotNull(posterBook), sourceUrl)
            requireMissingPosterWorkId(posterBookId)
            importScope(identity)
        } else if (posterBook != null && posterEntry == null) {
            target.requireMetadataOnlyRepair(dao, posterBook, mergeKey, sourceUrl)
            if (database.workIdentityDao().bookAutoMergeExcluded(posterBookId)) {
                throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
            }
            importScope(identity)
        } else {
            if (posterEntry != null) {
                val owner = resolvedWork(WorkIdentityInput.WorkId(posterEntry.workId))
                    ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.DanglingSource)
                if (target !is CatalogPosterTarget.ExistingWork || owner.id != target.work.id) {
                    throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
                }
            }
            importScope(identity, posterBookId)
        }
        return CatalogPosterScope(target, identity, source, posterBookId, posterBook, posterEntry)
    }

    private suspend fun requireMissingPosterWorkId(id: String) {
        if (database.audiobookDao().getWorkById(id) != null) {
            throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
        }
        when (val resolution = resolveStored(WorkIdentityInput.WorkId(id))) {
            is WorkResolution.Missing -> Unit
            is WorkResolution.Resolved -> throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
            is WorkResolution.Refused -> throw WorkIdentityWriteRefused(IdentityWriteRefusal.Alias(resolution.reason))
        }
    }

    /** Frozen local rows/resources for one catalog item, never an I/O lease or a Book-ID guess. */
    private data class CatalogWriteScope(
        val targetWorkId: String,
        val source: SourceImportScope,
    )

    /** One admitted catalog row transaction; publish outcomes only after this returns. */
    internal suspend fun <T> writeCatalogSource(
        mergeKey: String, sourceId: String, sourceUrl: String,
        narrator: String, language: String, unmergeableWorkId: String,
        block: suspend (SourceWorkIdentity) -> T,
    ): T {
        val discovered = database.withTransaction {
            val identity = resolveCatalogSourceStored(mergeKey, sourceId, sourceUrl, narrator, language, unmergeableWorkId)
            catalogWriteScope(identity, mergeKey, unmergeableWorkId)
        }
        return gate.withIdentityWrite(listOf(discovered.targetWorkId), discovered.source.bookIds) {
            database.withTransaction {
                val identity = resolveCatalogSourceStored(mergeKey, sourceId, sourceUrl, narrator, language, unmergeableWorkId)
                val current = catalogWriteScope(identity, mergeKey, unmergeableWorkId)
                if (current != discovered) throw WorkIdentityWriteRefused(IdentityWriteRefusal.StaleAdmission)
                val keys = relevantTombstoneKeys(identity, mergeKey, current.targetWorkId)
                if (keys.toList().chunked(128).any { database.audiobookDao().manualMergeTombstones(it).isNotEmpty() }) {
                    throw WorkIdentityWriteRefused(IdentityWriteRefusal.Tombstoned)
                }
                val result = block(identity)
                currentCoroutineContext().ensureActive()
                result
            }
        }
    }

    private suspend fun catalogWriteScope(
        identity: SourceWorkIdentity, mergeKey: String, unmergeableWorkId: String,
    ): CatalogWriteScope {
        val target = identity.work?.id ?: mergeKey.ifBlank { unmergeableWorkId }
        if (target.isBlank()) throw WorkIdentityWriteRefused(IdentityWriteRefusal.StaleAdmission)
        // Catalog creates a real Work at target; only existing actual Book rows are Book resources.
        return CatalogWriteScope(target, importScope(identity))
    }

    private suspend fun resolveCatalogSourceStored(
        mergeKey: String, sourceId: String, sourceUrl: String,
        narrator: String, language: String, unmergeableWorkId: String,
    ): SourceWorkIdentity {
        val exact = resolveSourceStored(mergeKey, sourceId, sourceUrl, narrator, language, null)
        // Actual source/key evidence decides ownership before any proposed creation resource.
        val activity = sourceActivityScope(sourceId, sourceUrl, exact)
        val owner = activity?.workId?.let { id ->
            resolvedWork(WorkIdentityInput.WorkId(id))
                ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.DanglingSource)
        }
        if (owner != null) return exact.copy(work = owner)
        if (mergeKey.isBlank()) {
            // A stable blank-key ID is a new row resource, never evidence for adopting its occupant.
            when (val creation = resolveStored(WorkIdentityInput.WorkId(unmergeableWorkId))) {
                is WorkResolution.Missing -> Unit
                is WorkResolution.Resolved -> throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
                is WorkResolution.Refused -> throw WorkIdentityWriteRefused(IdentityWriteRefusal.Alias(creation.reason))
            }
        }
        return exact
    }

    private suspend fun readTombstonesAtStart(): Map<String, TombstoneEntity> {
        val dao = database.audiobookDao()
        val ids = dao.getTombstoneBookIds()
        if (ids.size > MAX_START_TOMBSTONES || ids.distinct().size != ids.size ||
            ids.any { it.isBlank() || it.length > MAX_TOMBSTONE_KEY_LENGTH } ||
            ids.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } > MAX_START_TOMBSTONE_BYTES) {
            throw WorkIdentityWriteRefused(IdentityWriteRefusal.StaleAdmission)
        }
        val rows = ids.chunked(128).flatMap { dao.manualMergeTombstones(it) }
        val map = rows.associateBy { it.bookId }
        if (map.size != ids.size || map.keys != ids.toSet()) {
            throw WorkIdentityWriteRefused(IdentityWriteRefusal.StaleAdmission)
        }
        return Collections.unmodifiableMap(HashMap(map))
    }

    /** Resolve every reverse alias in its own namespace; only Work-ID aliases extend the frontier. */
    private suspend fun relevantTombstoneKeys(
        identity: SourceWorkIdentity, mergeKey: String, unresolvedTargetKey: String,
    ): Set<String> {
        val canonical = identity.work?.id ?: mergeKey.takeIf { it.isNotBlank() } ?: unresolvedTargetKey
        val selectedBook = identity.existingSource?.book ?: identity.importBook
        val keys = linkedSetOf(canonical)
        listOfNotNull(identity.work?.mergeKey, mergeKey.takeIf { it.isNotBlank() },
            selectedBook?.id, selectedBook?.workId, selectedBook?.mergeKey,
            unresolvedTargetKey.takeIf { selectedBook == null })
            .filter { it.isNotBlank() }.forEach(keys::add)
        val visited = linkedSetOf(canonical)
        var frontier = listOf(canonical)
        val aliases = linkedSetOf<Pair<String, String>>()
        while (frontier.isNotEmpty()) {
            val next = linkedSetOf<String>()
            for (batch in frontier.chunked(128)) {
                val rows = database.workIdentityDao().redirectsTargeting(batch)
                if (rows.size > MAX_RELEVANT_ALIASES) {
                    throw WorkIdentityWriteRefused(IdentityWriteRefusal.Alias(IdentityRefusal.AliasLimit))
                }
                for (row in rows) {
                    val input = when (row.namespace) {
                        "WORK_ID" -> WorkIdentityInput.WorkId(row.oldKey)
                        "MERGE_KEY" -> WorkIdentityInput.MergeKey(row.oldKey)
                        else -> throw WorkIdentityWriteRefused(IdentityWriteRefusal.Alias(IdentityRefusal.MalformedIdentity))
                    }
                    val resolved = resolvedWork(input)
                        ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.Alias(IdentityRefusal.Dangling))
                    if (resolved.id != canonical) throw WorkIdentityWriteRefused(IdentityWriteRefusal.Alias(IdentityRefusal.ConflictingAlias))
                    aliases += row.namespace to row.oldKey
                    if (aliases.size > MAX_RELEVANT_ALIASES) {
                        throw WorkIdentityWriteRefused(IdentityWriteRefusal.Alias(IdentityRefusal.AliasLimit))
                    }
                    keys += row.oldKey
                    if (row.namespace == "WORK_ID" && visited.add(row.oldKey)) next += row.oldKey
                }
            }
            frontier = next.toList()
        }
        return keys
    }

    private suspend fun admitTombstones(
        admission: SourceImportAdmission, identity: SourceWorkIdentity,
        mergeKey: String, prospectiveBookId: String, restoreObserved: Boolean,
    ) {
        val dao = database.audiobookDao()
        val keys = relevantTombstoneKeys(identity, mergeKey, prospectiveBookId)
        val current = keys.toList().chunked(128).flatMap { dao.manualMergeTombstones(it) }.associateBy { it.bookId }
        for (key in keys) {
            val row = current[key]
            if (row != admission.startTombstones[key]) {
                throw WorkIdentityWriteRefused(if (row != null) IdentityWriteRefusal.Tombstoned else IdentityWriteRefusal.StaleAdmission)
            }
            if (!restoreObserved && row != null) throw WorkIdentityWriteRefused(IdentityWriteRefusal.Tombstoned)
        }
        // No differing/technical state is cleared. The caller's complete row body shares this transaction.
        if (restoreObserved) for (key in current.keys) dao.deleteTombstone(key)
    }

    private suspend fun requireImportScope(
        admission: SourceImportAdmission, identity: SourceWorkIdentity, prospectiveBookId: String? = null,
    ) {
        if (admission.owner !== this || admission.scope != importScope(identity, prospectiveBookId)) {
            throw WorkIdentityWriteRefused(IdentityWriteRefusal.StaleAdmission)
        }
    }

    private suspend fun importScope(identity: SourceWorkIdentity, prospectiveBookId: String? = null): SourceImportScope {
        val dao = database.audiobookDao()
        val entries = identity.work?.let { dao.manualMergeEntries(listOf(it.id)) }.orEmpty()
        // Entry IDs become Book lock keys only after the actual Book row has been established.
        val books = entries.mapNotNull { entry -> dao.getAudiobookById(entry.id)?.id }.toMutableList()
        val existing = identity.existingSource
        if (existing != null) books += existing.book.id
        identity.importBook?.let { books += it.id }
        if (prospectiveBookId != null) {
            val occupant = dao.getAudiobookById(prospectiveBookId)
            if (occupant != null) {
                val entry = dao.libraryEntryById(occupant.id)
                    ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.DanglingSource)
                val owner = resolvedWork(WorkIdentityInput.WorkId(entry.workId))
                    ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.DanglingSource)
                if (identity.work?.id != owner.id) {
                    throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
                }
            }
            books += prospectiveBookId
        }
        return SourceImportScope(identity.work?.id, existing?.source, existing?.edition,
            identity.importBook?.id, identity.importEdition,
            existing?.let { dao.getChaptersListForBook(it.book.id).toList() }.orEmpty(),
            existing?.let { dao.getTracksForSourceSync(it.source.id).toList() }.orEmpty(),
            books.distinct().sortedWith(::compareMergeUtf8))
    }

    /** Resolve the import's existing-rendition fallback BEFORE acquiring its Book resources. */
    private suspend fun resolveImportSourceStored(
        mergeKey: String, sourceId: String, sourceUrl: String,
        narrator: String, language: String, fetchedChapterTitles: List<String>,
        fallbackEditionIds: List<String>,
    ): SourceWorkIdentity {
        val identity = resolveSourceStored(mergeKey, sourceId, sourceUrl, narrator, language, fetchedChapterTitles)
        if (identity.existingSource != null) return identity
        val dao = database.audiobookDao()
        var fallback: BookRow? = null
        var matchedEditionId: String? = null
        for (editionId in fallbackEditionIds) {
            fallback = dao.findBookByEditionId(editionId)
            if (fallback != null) {
                matchedEditionId = editionId
                break
            }
        }
        val book = fallback ?: return identity
        val entry = dao.libraryEntryById(book.id)
            ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.DanglingSource)
        val owner = resolvedWork(WorkIdentityInput.WorkId(entry.workId))
            ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.DanglingSource)
        // A recipe-shaped Edition ID is a lookup only, never proof of Work equivalence.
        if (identity.work?.id != owner.id) {
            throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
        }
        val matchedId = matchedEditionId
            ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.DanglingSource)
        val edition = dao.getEditionById(matchedId)
            ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.DanglingSource)
        if (edition.workId != book.id) throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
        return identity.copy(importBook = book, importEdition = edition)
    }

    /** Resource discovery proves Source/Book/Entry/Edition anchors, not fetched topology claims. */
    private suspend fun resolveExactSourceStored(sourceId: String, sourceUrl: String): SourceWorkIdentity {
        val dao = database.audiobookDao()
        val sources = dao.getSourcesByIdentity(sourceId, sourceUrl)
        if (sources.isEmpty()) return SourceWorkIdentity(null, null)
        if (sources.size != 1) throw WorkIdentityWriteRefused(IdentityWriteRefusal.AmbiguousSource)
        val source = sources.single()
        val book = dao.getAudiobookById(source.bookId)
            ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.DanglingSource)
        val entry = dao.libraryEntryById(book.id)
            ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.DanglingSource)
        val owner = resolvedWork(WorkIdentityInput.WorkId(entry.workId))
            ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.DanglingSource)
        val edition = source.editionId?.let { dao.getEditionById(it) }
            ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.DanglingSource)
        if (edition.workId != book.id) throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
        return SourceWorkIdentity(owner, ExistingSourceIdentity(source, book, edition))
    }

    private suspend fun resolveSourceStored(
        mergeKey: String, sourceId: String, sourceUrl: String,
        narrator: String, language: String, fetchedChapterTitles: List<String>?,
    ): SourceWorkIdentity {
        val work = if (mergeKey.isBlank()) null else resolvedWork(WorkIdentityInput.MergeKey(mergeKey))
        val exact = resolveExactSourceStored(sourceId, sourceUrl)
        val existing = exact.existingSource ?: return SourceWorkIdentity(work, null)
        if (work != null && work.id != exact.work?.id) {
            throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
        }
        val firstMaterialization = validateStoredTopology(existing)
        val claimedNarrator = MetadataAssertions.normalizeClaimedText(narrator)
        val storedNarrator = MetadataAssertions.normalizeClaimedText(existing.edition.narrator)
        val claimedLanguage = LanguageCode.normalize(language)
        val storedLanguage = LanguageCode.normalize(existing.edition.language.orEmpty())
        if ((claimedNarrator != null && storedNarrator != null && claimedNarrator != storedNarrator) ||
            (claimedLanguage != null && storedLanguage != null && claimedLanguage != storedLanguage)) {
            throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
        }
        if (fetchedChapterTitles != null && !firstMaterialization) validateFetchedTopology(existing, fetchedChapterTitles)
        return exact.copy(existingSource = existing.copy(firstMaterialization = firstMaterialization))
    }

    private suspend fun validateStoredTopology(existing: ExistingSourceIdentity): Boolean {
        val dao = database.audiobookDao()
        val chapters = dao.getChaptersListForBook(existing.book.id)
        val editionChapters = dao.getChaptersListForEdition(existing.edition.id)
        val tracks = dao.getTracksForSourceSync(existing.source.id)
        val progress = dao.getPlaybackProgressSyncByEdition(existing.edition.id)
        if (chapters.isEmpty()) {
            validateFirstMaterialization(existing, editionChapters, tracks)
            return true
        }
        val indices = chapters.indices.toList()
        if (
            chapters.any { it.editionId != existing.edition.id } ||
            chapters.map { it.chapterIndex } != indices ||
            editionChapters.size != chapters.size ||
            editionChapters.map { it.id }.toSet() != chapters.map { it.id }.toSet() ||
            tracks.map { it.trackIndex } != indices ||
            tracks.any { it.url.isBlank() } ||
            (progress != null && progress.bookId != existing.book.id)) {
            throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
        }
        return false
    }

    /** Empty headers alone say nothing about listening. Reject only actual contradictory target anchors/history. */
    private suspend fun validateFirstMaterialization(
        existing: ExistingSourceIdentity, editionChapters: List<ChapterEntity>, tracks: List<SourceTrackEntity>,
    ) {
        val dao = database.audiobookDao()
        val bookId = existing.book.id
        val editionId = existing.edition.id
        val sources = (dao.getSourcesForBookSync(bookId) + dao.getSourcesForEditionSync(editionId)).distinctBy { it.id }
        val foreignEdition = dao.manualMergeEditions(listOf(bookId), emptyList()).any { it.id != editionId }
        val physicalRows = sources.any { dao.getTracksForSourceSync(it.id).isNotEmpty() }
        val passes = dao.manualMergeReadthroughs(emptyList(), listOf(bookId), listOf(editionId))
        var incompatiblePass = false
        for (pass in passes) {
            // A personal AUDIO Entry may select this physical Edition without being its Book.
            // Its typed Entry owner must still agree with the admitted physical Book's Work.
            val entry = dao.libraryEntryById(pass.libraryEntryId)
            val compatibleOwner = entry != null && entry.workId == existing.book.workId &&
                pass.workId == entry.workId
            val compatiblePass = if (pass.format == "AUDIO") {
                compatibleOwner && pass.editionId == editionId &&
                    pass.state in setOf("PLANNED", "IN_PROGRESS") && pass.finishedAt == null &&
                    pass.unit == "SECONDS" && pass.unitValue == 0 && pass.journalJson == "[]"
            } else {
                compatibleOwner && pass.libraryEntryId == bookId &&
                    pass.format in setOf("PAPER", "EBOOK") && pass.editionId == null
            }
            if (!compatiblePass) incompatiblePass = true
        }
        if (editionChapters.isNotEmpty() || tracks.isNotEmpty() || foreignEdition || physicalRows ||
            sources.any { it.bookId != bookId || it.editionId != editionId } ||
            dao.manualMergeProgress(listOf(bookId), listOf(editionId)).isNotEmpty() ||
            dao.manualMergeBookmarks(listOf(bookId), listOf(editionId)).isNotEmpty() || incompatiblePass ||
            dao.manualMergeEvents(listOf(bookId)).isNotEmpty() || dao.manualMergeFailures(listOf(bookId)).isNotEmpty() ||
            dao.getCorrectionsForMergeKey(ChapterOrder.key(bookId)).isNotEmpty()) {
            throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
        }
        // Rewind/timer settings do not refer to a chapter and survive unchanged. Global stats/sessions have no Book owner.
    }

    private suspend fun validateFetchedTopology(existing: ExistingSourceIdentity, fetchedTitles: List<String>) {
        val dao = database.audiobookDao()
        val chapters = dao.getChaptersListForBook(existing.book.id)
        val originalIds = ChapterOrder.originalIds(dao, existing.book.id, chapters)
            ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
        val byId = chapters.associateBy { it.id }
        if (originalIds.size != fetchedTitles.size || originalIds.indices.any { index ->
                val stored = byId[originalIds[index]]?.title?.trim()
                    ?: throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
                val fetched = fetchedTitles[index].trim()
                stored.isNotBlank() && (fetched.isBlank() || !stored.equals(fetched, ignoreCase = true))
            }) {
            throw WorkIdentityWriteRefused(IdentityWriteRefusal.ConflictingSource)
        }
    }

    private suspend fun resolvedWork(input: WorkIdentityInput): WorkEntity? = when (val result = resolveStored(input)) {
        is WorkResolution.Resolved -> result.work
        is WorkResolution.Missing -> null
        is WorkResolution.Refused -> throw WorkIdentityWriteRefused(IdentityWriteRefusal.Alias(result.reason))
    }

    private suspend fun resolveStored(input: WorkIdentityInput): WorkResolution {
        val visited = mutableSetOf<WorkIdentityInput>()
        var current = input
        var followed = 0
        val dao = database.audiobookDao()
        val identity = database.workIdentityDao()
        while (true) {
            if (current.value.isBlank()) return WorkResolution.Refused(IdentityRefusal.MalformedIdentity)
            if (!visited.add(current)) return WorkResolution.Refused(IdentityRefusal.Cycle)
            val matches = when (val key = current) {
                is WorkIdentityInput.WorkId -> listOfNotNull(dao.getWorkById(key.value))
                is WorkIdentityInput.MergeKey -> identity.worksForMergeKey(key.value)
            }
            if (matches.size > 1) return WorkResolution.Refused(IdentityRefusal.ConflictingAlias)
            val namespace = when (current) {
                is WorkIdentityInput.WorkId -> "WORK_ID"
                is WorkIdentityInput.MergeKey -> "MERGE_KEY"
            }
            val redirect = identity.redirectByAlias(namespace, current.value)
            if (redirect == null) {
                return matches.singleOrNull()?.let { WorkResolution.Resolved(it) }
                    ?: if (followed == 0) WorkResolution.Missing(input)
                    else WorkResolution.Refused(IdentityRefusal.Dangling)
            }
            if (matches.any { it.id != redirect.canonicalWorkId }) {
                return WorkResolution.Refused(IdentityRefusal.ConflictingAlias)
            }
            if (followed == MAX_REDIRECTS) return WorkResolution.Refused(IdentityRefusal.AliasLimit)
            followed++
            // Redirect targets are Work IDs; never retry them as bibliographic merge keys.
            current = WorkIdentityInput.WorkId(redirect.canonicalWorkId)
        }
    }

    private companion object {
        const val MAX_KNOWN_SOURCE_CLAIMS = 4096
        const val MAX_REDIRECTS = 64
        const val MAX_RELEVANT_ALIASES = 4096
        const val MAX_START_TOMBSTONES = 10_000
        const val MAX_TOMBSTONE_KEY_LENGTH = 8192
        const val MAX_START_TOMBSTONE_BYTES = 4L * 1024 * 1024
    }
}
