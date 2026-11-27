package com.slukhayka.audiobooks.data.merge

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.PreservedWorkMergeParticipant
import com.slukhayka.audiobooks.data.db.PreservedWorkMergeBook
import com.slukhayka.audiobooks.data.db.WorkIdentityRedirectRow
import com.slukhayka.audiobooks.data.db.WorkMergeOperationEntity
import com.slukhayka.audiobooks.data.db.WorkMergeOperationState
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Opaque preparation belongs to the command module that captured it. */
class ManualWorkMergeCommandPreview internal constructor(
    val preview: ManualWorkMergePreview,
    internal val owner: ManualWorkMergeCommands,
    internal val workIds: List<String>,
    internal val version: String,
)

/** Original participant Work IDs selected by the listener for known metadata fields. */
data class ManualWorkMergeMetadataChoices(
    val seriesTitleFromWorkId: String? = null,
    val seriesUrlFromWorkId: String? = null,
    val seriesIndexFromWorkId: String? = null,
    val coverFromWorkId: String? = null,
)

/** Original claim selectors for one exact sourceId/sourceUrl pair. */
data class ManualWorkMergeSourceClaimMetadataChoice(
    val sourceId: String,
    val sourceUrl: String,
    val streamOnlyFromClaimId: String? = null,
    val coverFromClaimId: String? = null,
    val durationFromClaimId: String? = null,
)

/** Original choices captured independently of subsequent edits to the caller collection. */
class ManualWorkMergeSourceMetadataChoices(items: List<ManualWorkMergeSourceClaimMetadataChoice> = emptyList()) {
    internal val items: List<ManualWorkMergeSourceClaimMetadataChoice> = immutableMergeList(items)
}

data class ManualWorkMergeApprovedPlan(
    val prepared: ManualWorkMergeCommandPreview,
    val operationId: String,
    val canonicalTitle: String,
    val canonicalAuthor: String,
    val metadataChoices: ManualWorkMergeMetadataChoices = ManualWorkMergeMetadataChoices(),
    val sourceMetadataChoices: ManualWorkMergeSourceMetadataChoices = ManualWorkMergeSourceMetadataChoices(),
)

sealed interface ManualWorkMergeApplyResult {
    data class Applied(val operationId: String, val canonicalWorkId: String) : ManualWorkMergeApplyResult
    data class NeedsDecision(val reason: String) : ManualWorkMergeApplyResult
}

sealed interface ManualWorkMergeUndoResult {
    data class Undone(val operationId: String) : ManualWorkMergeUndoResult
    data class NeedsDecision(val reason: String) : ManualWorkMergeUndoResult
}

/**
 * Transaction boundary for approved manual Work identity operations.
 * Remaining carrier policies and writer composition must precede UI exposure.
 */
class ManualWorkMergeCommands(
    private val database: AudiobookDatabase,
    private val gate: ManualWorkMergeGate,
) {
    init { gate.requireDatabase(database) }

    suspend fun prepareManualWorkMerge(a: String, b: String): ManualWorkMergeCommandPreview =
        database.withTransaction {
            currentCoroutineContext().ensureActive()
            val preview = ManualWorkMerge(database.audiobookDao()).prepareManualWorkMerge(a, b)
            val ids = immutableMergeList(listOf(a, b).sortedWith(::compareMergeUtf8))
            ManualWorkMergeCommandPreview(preview, this@ManualWorkMergeCommands, ids, version(preview))
        }

    suspend fun applyManualWorkMerge(plan: ManualWorkMergeApprovedPlan): ManualWorkMergeApplyResult {
        currentCoroutineContext().ensureActive()
        if (plan.prepared.owner !== this) return ManualWorkMergeApplyResult.NeedsDecision("Preparation belongs to another command module")
        if (plan.operationId.isBlank() || plan.operationId.length > 256) return ManualWorkMergeApplyResult.NeedsDecision("Invalid operation identity")
        if (!approvalTextIsWellFormed(plan)) return ManualWorkMergeApplyResult.NeedsDecision("Approval text is not valid Unicode")
        val canonical = MergeKey.keyFor(plan.canonicalTitle, plan.canonicalAuthor)
        if (canonical.isBlank() || canonical.length > 512) return ManualWorkMergeApplyResult.NeedsDecision("Canonical title and author are required")
        val sourceChoices = plan.sourceMetadataChoices.items
        if (sourceChoices.map { it.sourceId to it.sourceUrl }.distinct().size != sourceChoices.size ||
            sourceChoices.any { sourceChoiceIds(it).all { claimId -> claimId == null } }) {
            return ManualWorkMergeApplyResult.NeedsDecision("Source metadata choices must identify unique pairs and selected fields")
        }
        val books = participantEntryIds(plan.prepared.preview)
        val physicalBooks = participantPhysicalBookIds(plan.prepared.preview)
        return when (val admission = gate.withIdleIdentities(plan.prepared.workIds + canonical, physicalBooks) {
            database.withTransaction {
                val identity = database.workIdentityDao()
                val prior = identity.operationById(plan.operationId)
                if (prior != null) {
                    if (!ManualWorkMergeJournal.supportsVersion(prior.payloadVersion)) {
                        return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Unknown journal format")
                    }
                    val proof = JSONObject(prior.approvedIdentity)
                    if (prior.payloadVersion >= 3) {
                        try {
                            val proofFormat = ManualWorkMergeBookProof.requireFormat(proof, prior.payloadVersion)
                            val savedBooks = if (proofFormat == ManualWorkMergeBookProof.FORMAT) ManualWorkMergeBookProof.read(proof) else null
                            if (savedBooks != null) {
                                require(savedBooks == physicalBooks)
                                require(validBookRows(plan.operationId, savedBooks, identity.preservedBooks(plan.operationId)))
                            }
                            val savedScope = ManualWorkMergeOperationScope.decode(proof.getJSONArray("operationScope"))
                            require(savedScope.digest() == proof.getString("scopeDigest"))
                            val savedJournal = ManualWorkMergeJournal(database, currentCoroutineContext())
                            val savedBefore = savedJournal.decode(prior.beforeImages)
                            require(savedBefore.formatVersion == prior.payloadVersion && proof.getString("beforeDigest") == bytesDigest(prior.beforeImages))
                            if (savedBefore.formatVersion == 4) {
                                require(identity.preservedParticipants(plan.operationId).all { it.autoMergeExcluded })
                                require(validFormat4Protection(savedBefore, savedJournal, plan.prepared.workIds,
                                    identity.preservedParticipants(plan.operationId).map { it.bookId to it.editionId }, savedBooks)) {
                                    "Journal and actual Book protection disagree"
                                }
                            }
                            savedScope.validateOriginals(plan.prepared.workIds, canonical, books,
                                identity.preservedParticipants(plan.operationId).map { it.editionId }, savedBefore, savedJournal)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (invalid: RuntimeException) {
                            return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Operation scope is invalid")
                        }
                    }
                    val expectedApproval = if (proof.getInt("format") == ManualWorkMergeBookProof.FORMAT) {
                        ManualWorkMergeBookProof.approval(approval(plan, prior.beforeImages), ManualWorkMergeBookProof.read(proof))
                    } else approval(plan, prior.beforeImages)
                    if (prior.state == WorkMergeOperationState.APPLIED.name &&
                        prior.canonicalWorkId == canonical && proof.getString("approval") == expectedApproval) {
                        return@withTransaction ManualWorkMergeApplyResult.Applied(prior.operationId, prior.canonicalWorkId)
                    }
                    return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Operation identity is already used")
                }
                val roots = plan.prepared.workIds
                val preview = ManualWorkMerge(database.audiobookDao()).prepareManualWorkMerge(roots[0], roots[1])
                if (version(preview) != plan.prepared.version || participantEntryIds(preview) != books || participantPhysicalBookIds(preview) != physicalBooks) {
                    return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Prepared library state changed")
                }
                if (preview.participants.size != 2 || preview.conflicts.isNotEmpty()) {
                    return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Participant conflicts need a decision")
                }
                if (!supportsMergeCarriers(preview)) {
                    return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Additional carrier policies are not implemented")
                }
                val sql = database.openHelper.writableDatabase
                val existingCanonical = sql.query(SimpleSQLiteQuery(
                    "SELECT id, mergeKey FROM works WHERE id = ? OR mergeKey = ?", arrayOf(canonical, canonical),
                )).use {
                    if (!it.moveToFirst()) false else {
                        if (it.getString(0) != canonical || it.getString(1) != canonical ||
                            canonical !in roots || it.moveToNext()) {
                            return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Canonical identity needs a participant decision")
                        }
                        true
                    }
                }
                val oldKeys = (roots + preview.participants.map { it.work.mergeKey }).filter { it.isNotBlank() }.distinct()
                if (readAliases((oldKeys + canonical).distinct(), (roots + canonical).distinct()).isNotEmpty()) {
                    return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Existing alias policies need a decision")
                }
                val aliases = preview.participants.flatMap { participant -> listOf(
                    WorkIdentityRedirectRow("WORK_ID", participant.work.id, canonical, plan.operationId),
                    WorkIdentityRedirectRow("MERGE_KEY", participant.work.mergeKey, canonical, plan.operationId),
                ) }.filter { it.oldKey.isNotBlank() && it.oldKey != canonical }.distinctBy { it.namespace to it.oldKey }
                for (alias in aliases) if (identity.redirectByAlias(alias.namespace, alias.oldKey) != null) {
                    return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("An old identity already has an alias")
                }
                val context = currentCoroutineContext()
                val journal = ManualWorkMergeJournal(database, context)
                val scope = (roots + canonical).distinct()
                val editionIds = preview.participants.flatMap { it.editions }.map { it.edition.id }.distinct()
                // New Book policy proof3 always has full raw Edition coverage, including empty sets.
                val formatFloor = 4
                val before = journal.capture(scope, books, editionIds, formatFloor)
                val capturedPasses = before.rows.getValue("readthroughs")
                if (capturedPasses.any { journal.text(it, "workId", before.encoding) !in roots } ||
                    capturedPasses.map { journal.text(it, "id", before.encoding) }.toSet() !=
                    preview.snapshot.library.readthroughs.map { it.id }.toSet()) {
                    return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Readthrough ownership needs a participant decision")
                }
                val catalogPlan = try {
                    ManualWorkMergeCatalogPlan.prepare(preview, before, canonical, journal)
                } catch (invalid: IllegalArgumentException) {
                    return@withTransaction ManualWorkMergeApplyResult.NeedsDecision(invalid.message ?: "Catalog policies need a decision")
                }
                val catalogEditions = try {
                    ManualWorkMergeCatalogEditionPlan.prepare(preview, canonical, before, journal)
                } catch (invalid: IllegalArgumentException) {
                    return@withTransaction ManualWorkMergeApplyResult.NeedsDecision(invalid.message ?: "Edition ownership needs a decision")
                }
                val preparedScope = try {
                    ManualWorkMergeOperationScope.capture(preview.snapshot, canonical).also {
                        it.validateOriginals(roots, canonical, books, editionIds, before, journal)
                    }
                } catch (invalid: IllegalArgumentException) {
                    return@withTransaction ManualWorkMergeApplyResult.NeedsDecision(invalid.message ?: "Operation scope needs a decision")
                }
                // Future canonical keys and original provider predicates can expose rows absent
                // from the two-participant preview. Never adopt unsupported personal data as baseline.
                val expanded = ManualWorkMergeSnapshotReader.readForOperation(database.audiobookDao(), preparedScope)
                val expectedMissingCanonical = ManualWorkMergeConflict.UnresolvedReference("works", canonical, "Work", canonical)
                if (expanded.conflicts.any { existingCanonical || it != expectedMissingCanonical } ||
                    expanded.personal.corrections.isNotEmpty() || expanded.personal.preferences.isNotEmpty() ||
                    expanded.personal.feedSnapshots.isNotEmpty() || expanded.personal.tombstones.isNotEmpty()) {
                    return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Expanded operation dependencies need a decision")
                }
                if (expanded.library.entries.any { it.id == canonical } || expanded.library.books.any { it.id == canonical }) {
                    val participant = preview.participants.singleOrNull { it.work.id == canonical }
                    val entry = preview.snapshot.library.entries.singleOrNull { it.id == canonical }
                    val capturedBooks = preview.snapshot.library.books.filter { it.id == canonical }
                    val expandedBooks = expanded.library.books.filter { it.id == canonical }
                    val book = capturedBooks.singleOrNull()
                    val sameCanonicalEntry = existingCanonical && canonical in roots && participant != null &&
                        entry != null && entry.workId == canonical &&
                        expanded.library.entries.filter { it.id == canonical } == listOf(entry)
                    // An Entry does not imply a physical Book, even at the canonical Work ID.
                    val entryOnly = capturedBooks.isEmpty() && expandedBooks.isEmpty()
                    val provenLibrary = participant?.editions?.any { edition ->
                        manualMergeEditionOwner(preview, canonical, edition) ==
                            ManualWorkMergeEditionOwner.Library(canonical, canonical)
                    } == true
                    val samePhysicalBook = book != null && expandedBooks == listOf(book) &&
                        (provenLibrary || (isUnmaterializedBook(preview.snapshot.library, canonical) &&
                            isUnmaterializedBook(expanded.library, canonical)))
                    if (!sameCanonicalEntry || !(entryOnly || samePhysicalBook)) {
                        return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Canonical identity collides with a Library namespace")
                    }
                }
                val operationScope = if (before.formatVersion >= 3) preparedScope else null
                val beforeBytes = journal.encode(before)
                val rootRows = before.rows.getValue("works")
                check(rootRows.size == 2)
                rootRows.forEach { check(it.cells[8].type == "integer") }
                val knownTimes = rootRows.filter { it.cells[8].number > 0 }
                val selected = knownTimes.minByOrNull { it.cells[8].number } ?: rootRows.first()
                val canonicalBase = if (existingCanonical) rootRows.single {
                    journal.text(it, "id", before.encoding) == canonical
                } else selected
                val enrichedCells = canonicalBase.cells.toMutableList()
                if (knownTimes.isNotEmpty()) enrichedCells[8] = selected.cells[8]
                val optionalColumns = listOf("seriesTitle", "seriesUrl", "seriesIndex", "coverImageUrl")
                val choices = metadataChoiceIds(plan.metadataChoices)
                for ((offset, column) in optionalColumns.withIndex()) {
                    val index = offset + 4
                    val known = linkedMapOf<String, ManualWorkMergeJournal.Cell>()
                    for (row in rootRows) {
                        val value = row.cells[index]
                        val workId = journal.text(row, "id", before.encoding)
                        when {
                            value.type == "null" -> Unit
                            column == "seriesIndex" -> {
                                if (value.type != "integer" || value.number !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                                    return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Work metadata storage needs a decision")
                                }
                                known[workId] = value
                            }
                            value.type != "text" -> return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Work metadata storage needs a decision")
                            journal.text(row, column, before.encoding).isNotBlank() -> known[workId] = value
                        }
                    }
                    val chosenWorkId = choices[offset]
                    val value = if (chosenWorkId == null) {
                        known.values.firstOrNull() ?: continue
                    } else {
                        known[chosenWorkId] ?: return@withTransaction ManualWorkMergeApplyResult.NeedsDecision(
                            "Metadata choice must select a known original Work value",
                        )
                    }
                    if (chosenWorkId == null && known.values.any { !journal.sameCell(value, it) }) {
                        return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Optional Work metadata needs a decision")
                    }
                    // Keep the original SQL value; before-images retain each donor's fields.
                    enrichedCells[index] = value
                }
                val canonicalRow = journal.withText(canonicalBase.copy(cells = enrichedCells), mapOf(
                    "id" to canonical, "mergeKey" to canonical,
                    "title" to plan.canonicalTitle, "author" to plan.canonicalAuthor,
                ), before.encoding)
                val originalClaims = before.rows.getValue("work_sources")
                val originalClaimIds = originalClaims.map { journal.text(it, "id", before.encoding) }.toSet()
                val claimGroups = originalClaims.groupBy { row ->
                    journal.text(row, "sourceId", before.encoding) to journal.text(row, "sourceUrl", before.encoding)
                }
                val pairChoices = sourceChoices.associateBy { it.sourceId to it.sourceUrl }
                if (pairChoices.keys.any { it !in claimGroups }) {
                    return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Source metadata choice must identify an original exact pair")
                }
                val claims = mutableListOf<ManualWorkMergeJournal.Row>()
                for ((pair, rows) in claimGroups) {
                    rows.forEach { check(it.cells[7].type == "integer") }
                    val selectedClaim = rows.minBy { it.cells[7].number }
                    val claimCells = selectedClaim.cells.toMutableList()
                    val selectors = pairChoices[pair]?.let(::sourceChoiceIds) ?: listOf(null, null, null)
                    for (index in 4..6) {
                        val known = linkedMapOf<String, ManualWorkMergeJournal.Cell>()
                        for (row in rows) {
                            val value = row.cells[index]
                            val claimId = journal.text(row, "id", before.encoding)
                            when (index) {
                                4 -> {
                                    if (value.type != "integer" || value.number !in 0L..1L) {
                                        return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Source streamOnly storage needs a decision")
                                    }
                                    known[claimId] = value
                                }
                                5 -> when {
                                    value.type == "null" -> Unit
                                    value.type != "text" -> return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Source cover storage needs a decision")
                                    journal.text(row, "coverImageUrl", before.encoding).isNotBlank() -> known[claimId] = value
                                }
                                6 -> when {
                                    value.type == "null" -> Unit
                                    value.type != "integer" || value.number < 0 -> return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Source duration storage needs a decision")
                                    else -> known[claimId] = value
                                }
                            }
                        }
                        val chosenClaimId = selectors[index - 4]
                        val value = if (chosenClaimId == null) {
                            known.values.firstOrNull() ?: continue
                        } else {
                            known[chosenClaimId] ?: return@withTransaction ManualWorkMergeApplyResult.NeedsDecision(
                                "Source metadata choice must select a known original value from its exact pair",
                            )
                        }
                        if (chosenClaimId == null && known.values.any { !journal.sameCell(value, it) }) {
                            return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Source claim metadata needs a decision")
                        }
                        claimCells[index] = value
                    }
                    val nextId = "$canonical|${pair.first}|${Integer.toHexString(pair.second.hashCode())}"
                    claims += journal.withText(selectedClaim.copy(cells = claimCells), mapOf("id" to nextId, "workId" to canonical), before.encoding)
                }
                val newClaimIds = claims.map { journal.text(it, "id", before.encoding) }
                // Equal URL hashes never prove that distinct source pairs are identical.
                if (newClaimIds.distinct().size != newClaimIds.size) {
                    return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Source claim identities collide")
                }
                for (nextId in newClaimIds) {
                    sql.query(SimpleSQLiteQuery("SELECT id FROM work_sources WHERE id = ?", arrayOf(nextId))).use {
                        if (it.moveToFirst() && nextId !in originalClaimIds) {
                            return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Canonical source claim already exists")
                        }
                    }
                }
                val protected = preview.participants.flatMap { participant -> participant.editions.mapNotNull { edition ->
                    (manualMergeEditionOwner(preview, participant.work.id, edition) as? ManualWorkMergeEditionOwner.Library)?.let { owner ->
                        PreservedWorkMergeParticipant(plan.operationId, owner.bookId, edition.edition.id, true)
                    }
                } }.distinctBy { it.bookId to it.editionId }
                context.ensureActive()
                if (existingCanonical) check(journal.updateById(canonicalRow, canonical) == 1)
                else journal.insert(canonicalRow)
                for (row in before.rows.getValue("library_entries")) {
                    val id = journal.text(row, "id", before.encoding)
                    check(journal.updateById(journal.withText(row, mapOf("workId" to canonical), before.encoding), id) == 1)
                }
                // Preserve each pass and its opaque progress journal; only the Work identity moves.
                for (row in before.rows.getValue("readthroughs")) {
                    val id = journal.text(row, "id", before.encoding)
                    check(journal.updateById(journal.withText(row, mapOf("workId" to canonical), before.encoding), id) == 1)
                }
                // The before-image retains every original claim, including deduplicated donors.
                for (previousId in originalClaimIds) check(execute("DELETE FROM work_sources WHERE id = ?", previousId) == 1)
                for (row in claims) journal.insert(row)
                catalogEditions.apply(journal)
                catalogPlan.apply(journal)
                for (root in roots) {
                    execute("DELETE FROM works_fts WHERE workId = ?", root)
                    if (root != canonical) check(execute("DELETE FROM works WHERE id = ?", root) == 1)
                }
                database.audiobookDao().refreshWorkSearchIndex(canonical)
                identity.insertRedirects(aliases)
                identity.insertPreservedParticipants(protected)
                identity.insertPreservedBooks(physicalBooks.map { PreservedWorkMergeBook(plan.operationId, it, true) })
                val after = journal.capture(scope, books, editionIds, before.formatVersion)
                check(after.formatVersion == before.formatVersion)
                val afterBytes = journal.encode(after)
                val afterFingerprint = if (operationScope == null) database.audiobookDao().readManualWorkMergeRows(scope).storageFingerprint
                    else ManualWorkMergeStorageFingerprint.forOperation(database.audiobookDao(), operationScope)
                val proof = JSONObject().put("format", ManualWorkMergeBookProof.FORMAT)
                    .put("approval", ManualWorkMergeBookProof.approval(approval(plan, beforeBytes), physicalBooks))
                    .put("afterFingerprint", afterFingerprint).put("beforeDigest", bytesDigest(beforeBytes))
                    .put("afterDigest", bytesDigest(afterBytes))
                    .put("preserved", JSONArray(protected.map { JSONArray(listOf(it.bookId, it.editionId)) }))
                    .put("canonicalTitle", plan.canonicalTitle)
                    .put("canonicalAuthor", plan.canonicalAuthor)
                    .put("approvalVersion", if (sourceChoices.isNotEmpty()) 3 else if (metadataChoiceIds(plan.metadataChoices).all { it == null }) 1 else 2)
                    .put("metadataChoices", JSONObject()
                        .put("seriesTitleFromWorkId", plan.metadataChoices.seriesTitleFromWorkId ?: JSONObject.NULL)
                        .put("seriesUrlFromWorkId", plan.metadataChoices.seriesUrlFromWorkId ?: JSONObject.NULL)
                        .put("seriesIndexFromWorkId", plan.metadataChoices.seriesIndexFromWorkId ?: JSONObject.NULL)
                        .put("coverFromWorkId", plan.metadataChoices.coverFromWorkId ?: JSONObject.NULL))
                    .put("sourceMetadataChoices", JSONArray(sortedSourceChoices(plan.sourceMetadataChoices).map { choice ->
                        JSONObject().put("sourceId", choice.sourceId).put("sourceUrl", choice.sourceUrl)
                            .put("streamOnlyFromClaimId", choice.streamOnlyFromClaimId ?: JSONObject.NULL)
                            .put("coverFromClaimId", choice.coverFromClaimId ?: JSONObject.NULL)
                            .put("durationFromClaimId", choice.durationFromClaimId ?: JSONObject.NULL)
                    }))
                if (operationScope != null) {
                    proof.put("operationScope", operationScope.encode()).put("scopeDigest", operationScope.digest())
                }
                ManualWorkMergeBookProof.write(proof, physicalBooks)
                identity.insertOperation(WorkMergeOperationEntity(
                    operationId = plan.operationId, state = WorkMergeOperationState.APPLIED.name,
                    canonicalWorkId = canonical, participantWorkIds = JSONArray(roots).toString(),
                    approvedIdentity = proof.toString(), payloadVersion = before.formatVersion,
                    beforeImages = beforeBytes, afterImages = afterBytes,
                ))
                context.ensureActive()
                ManualWorkMergeApplyResult.Applied(plan.operationId, canonical)
            }
        }) {
            is IdleBookAdmission.Admitted -> admission.value
            IdleBookAdmission.ActiveParticipants -> ManualWorkMergeApplyResult.NeedsDecision("A participant has active work")
        }
    }

    suspend fun undoManualWorkMerge(operationId: String): ManualWorkMergeUndoResult {
        currentCoroutineContext().ensureActive()
        require(operationId.isNotBlank()) { "Blank operation identity" }
        val initial = database.workIdentityDao().operationById(operationId)
            ?: return ManualWorkMergeUndoResult.NeedsDecision("Operation not found")
        if (initial.state == WorkMergeOperationState.UNDONE.name) return ManualWorkMergeUndoResult.Undone(operationId)
        val lockRoots = try {
            val values = JSONArray(initial.participantWorkIds)
            (0 until values.length()).map(values::getString).also { roots ->
                require(roots.size == 2 && roots.distinct().size == 2 && roots.none(String::isBlank))
                require(initial.canonicalWorkId.isNotBlank())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: RuntimeException) {
            return ManualWorkMergeUndoResult.NeedsDecision("Invalid participant journal")
        }
        val protected = database.workIdentityDao().preservedParticipants(operationId)
        val protectedBooks = database.workIdentityDao().preservedBooks(operationId)
        return when (val admission = gate.withIdleIdentities(lockRoots + initial.canonicalWorkId,
            protected.map { it.bookId } + protectedBooks.map { it.bookId }) {
            database.withTransaction {
                val identity = database.workIdentityDao()
                val operation = identity.operationById(operationId)
                    ?: return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Operation disappeared")
                if (operation.state == WorkMergeOperationState.UNDONE.name) return@withTransaction ManualWorkMergeUndoResult.Undone(operationId)
                if (operation.participantWorkIds != initial.participantWorkIds ||
                    operation.canonicalWorkId != initial.canonicalWorkId) {
                    return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Operation identity changed during admission")
                }
                if (!ManualWorkMergeJournal.supportsVersion(operation.payloadVersion) || operation.state != WorkMergeOperationState.APPLIED.name) {
                    return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Unknown journal format or operation state")
                }
                val rootsJson = JSONArray(operation.participantWorkIds)
                val roots = (0 until rootsJson.length()).map(rootsJson::getString)
                if (roots.size != 2 || roots.distinct().size != 2) return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Invalid participant journal")
                val currentProtected = identity.preservedParticipants(operationId)
                if (currentProtected != protected || identity.preservedBooks(operationId) != protectedBooks)
                    return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Participant protection changed")
                val context = currentCoroutineContext()
                val journal = ManualWorkMergeJournal(database, context)
                val proof = JSONObject(operation.approvedIdentity)
                val proofFormat = try { ManualWorkMergeBookProof.requireFormat(proof, operation.payloadVersion) }
                    catch (invalid: RuntimeException) { return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Unknown approval proof format") }
                val savedBookIds = if (proofFormat == ManualWorkMergeBookProof.FORMAT) {
                    try { ManualWorkMergeBookProof.read(proof) }
                    catch (invalid: RuntimeException) { return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Book policy proof is invalid") }
                } else null
                if (savedBookIds != null && !validBookRows(operationId, savedBookIds, protectedBooks))
                    return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Book policy changed")
                if (proof.getString("beforeDigest") != bytesDigest(operation.beforeImages) ||
                    proof.getString("afterDigest") != bytesDigest(operation.afterImages)) {
                    return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Journal payload integrity changed")
                }
                val savedProtected = proof.getJSONArray("preserved")
                val savedProtectedPairs = (0 until savedProtected.length()).map { index ->
                    val pair = savedProtected.getJSONArray(index)
                    require(pair.length() == 2)
                    pair.getString(0) to pair.getString(1)
                }
                if (operation.payloadVersion == 4 && savedProtectedPairs.distinct().size != savedProtectedPairs.size) {
                    return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Duplicate preserved Edition proof")
                }
                val expectedProtected = savedProtectedPairs.toSet()
                if (currentProtected.any { !it.autoMergeExcluded } ||
                    currentProtected.map { it.bookId to it.editionId }.toSet() != expectedProtected) {
                    return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Preserved Edition identities changed")
                }
                val before = journal.decode(operation.beforeImages)
                val after = journal.decode(operation.afterImages)
                if (before.formatVersion != operation.payloadVersion || after.formatVersion != operation.payloadVersion) {
                    return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Journal payload versions disagree")
                }
                val beforeWorks = before.rows.getValue("works")
                val beforeWorkIds = beforeWorks.map { journal.text(it, "id", before.encoding) }
                if (beforeWorks.size != roots.size || beforeWorkIds.toSet() != roots.toSet()) {
                    return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Journal Work identities disagree with participants")
                }
                val originalCanonical = beforeWorks.firstOrNull {
                    journal.text(it, "id", before.encoding) == operation.canonicalWorkId
                }
                if (originalCanonical != null && journal.text(originalCanonical, "mergeKey", before.encoding) != operation.canonicalWorkId) {
                    return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Original canonical identity changed")
                }
                val books = before.rows.getValue("library_entries").map { journal.text(it, "id", before.encoding) }
                if (before.formatVersion < 4) {
                    if (books.toSet() != protected.map { it.bookId }.toSet()) return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Journal and protection disagree")
                } else if (!validFormat4Protection(before, journal, roots, savedProtectedPairs, savedBookIds)) {
                    return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Journal and actual Book protection disagree")
                }
                val scope = (roots + operation.canonicalWorkId).distinct()
                val operationScope = if (operation.payloadVersion >= 3) {
                    try {
                        ManualWorkMergeOperationScope.decode(proof.getJSONArray("operationScope")).also {
                            require(it.digest() == proof.getString("scopeDigest")) { "Operation scope checksum changed" }
                            it.validateOriginals(roots, operation.canonicalWorkId, books, protected.map { item -> item.editionId }, before, journal)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (invalid: RuntimeException) {
                        return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Operation scope is invalid")
                    }
                } else null
                val current = journal.capture(scope, books, operationScope?.editionIds.orEmpty(), operation.payloadVersion)
                val currentFingerprint = if (operationScope == null) database.audiobookDao().readManualWorkMergeRows(scope).storageFingerprint
                    else ManualWorkMergeStorageFingerprint.forOperation(database.audiobookDao(), operationScope)
                if (!journal.encode(current).contentEquals(operation.afterImages) ||
                    currentFingerprint != proof.getString("afterFingerprint")) {
                    return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Applied dependencies changed")
                }
                val expectedAliases = before.rows.getValue("works").flatMap { row -> listOf(
                    WorkIdentityRedirectRow("WORK_ID", journal.text(row, "id", before.encoding), operation.canonicalWorkId, operationId),
                    WorkIdentityRedirectRow("MERGE_KEY", journal.text(row, "mergeKey", before.encoding), operation.canonicalWorkId, operationId),
                ) }.filter { it.oldKey.isNotBlank() && it.oldKey != operation.canonicalWorkId }.distinctBy { it.namespace to it.oldKey }
                val aliasKeys = (expectedAliases.map { it.oldKey } + operation.canonicalWorkId).distinct()
                if (readAliases(aliasKeys, scope).toSet() != expectedAliases.toSet()) {
                    return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("New alias dependencies prevent Undo")
                }
                for (alias in expectedAliases) if (identity.redirectByAlias(alias.namespace, alias.oldKey) != alias) {
                    return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Operation aliases changed")
                }
                context.ensureActive()
                if (originalCanonical != null) check(journal.updateById(originalCanonical, operation.canonicalWorkId) == 1)
                for (row in beforeWorks) {
                    if (journal.text(row, "id", before.encoding) != operation.canonicalWorkId) journal.insert(row)
                }
                if (before.formatVersion == 4) ManualWorkMergeCatalogEditionPlan.restore(journal, before)
                for (row in before.rows.getValue("library_entries")) {
                    check(journal.updateById(row, journal.text(row, "id", before.encoding)) == 1)
                }
                for (row in before.rows.getValue("readthroughs")) {
                    check(journal.updateById(row, journal.text(row, "id", before.encoding)) == 1)
                }
                for (row in after.rows.getValue("work_sources")) {
                    check(execute("DELETE FROM work_sources WHERE id = ?", journal.text(row, "id", after.encoding)) == 1)
                }
                for (row in before.rows.getValue("work_sources")) journal.insert(row)
                ManualWorkMergeCatalogPlan.restore(before, after, journal)
                execute("DELETE FROM works_fts WHERE workId = ?", operation.canonicalWorkId)
                for (row in before.rows.getValue("works_fts")) journal.insert(row)
                if (originalCanonical == null) check(execute("DELETE FROM works WHERE id = ?", operation.canonicalWorkId) == 1)
                identity.deleteOperationRedirects(operationId)
                check(identity.markUndone(operationId) == 1)
                context.ensureActive()
                ManualWorkMergeUndoResult.Undone(operationId)
            }
        }) {
            is IdleBookAdmission.Admitted -> admission.value
            IdleBookAdmission.ActiveParticipants -> ManualWorkMergeUndoResult.NeedsDecision("A participant has active work")
        }
    }

    private suspend fun validFormat4Protection(
        before: ManualWorkMergeJournal.Image, journal: ManualWorkMergeJournal,
        roots: List<String>, pairs: List<Pair<String, String>>, protectedBookIds: List<String>? = null,
    ): Boolean {
        fun text(row: ManualWorkMergeJournal.Row, column: String) = journal.text(row, column, before.encoding)
        val entries = before.rows.getValue("library_entries")
        val entryIds = entries.map { text(it, "id") }
        val ownerByEntry = entries.associate { text(it, "id") to text(it, "workId") }
        if (entryIds.distinct().size != entryIds.size || ownerByEntry.values.any { it !in roots } ||
            pairs.distinct().size != pairs.size) return false
        val actualBooks = mutableSetOf<String>()
        for (id in entryIds) if (database.audiobookDao().getAudiobookById(id) != null) actualBooks += id
        val editions = before.rows.getValue("editions")
        val editionIds = editions.map { text(it, "id") }
        if (editionIds.distinct().size != editionIds.size) return false
        val expected = mutableSetOf<Pair<String, String>>()
        for (edition in editions) {
            val owner = text(edition, "workId")
            if (owner in actualBooks && ownerByEntry[owner] in roots &&
                (owner !in roots || protectedBookIds?.contains(owner) == true)) {
                expected += owner to text(edition, "id")
            } else if (owner !in roots ||
                (ownerByEntry[owner] != null && ownerByEntry[owner] != owner) ||
                database.audiobookDao().getAudiobookById(owner) != null) {
                // Entry→same Work is catalog ownership; an actual root Book still conflicts.
                return false
            }
        }
        return expected == pairs.toSet() && if (protectedBookIds == null) {
            expected.map { it.first }.toSet() == actualBooks
        } else {
            protectedBookIds.distinct().size == protectedBookIds.size &&
                protectedBookIds.all { it in entryIds } && actualBooks == protectedBookIds.toSet()
        }
    }

    private fun participantEntryIds(preview: ManualWorkMergePreview): List<String> = preview.snapshot.library.entries
        .filter { it.workId in preview.participants.map { participant -> participant.work.id } }
        .map { it.id }.distinct().sortedWith(::compareMergeUtf8)

    private fun participantPhysicalBookIds(preview: ManualWorkMergePreview): List<String> {
        val entryIds = participantEntryIds(preview).toSet()
        return preview.snapshot.library.books.map { it.id }.filter { it in entryIds }
            .distinct().sortedWith(::compareMergeUtf8)
    }

    private fun supportsMergeCarriers(preview: ManualWorkMergePreview): Boolean {
        val snapshot = preview.snapshot
        val roots = preview.participants.map { it.work.id }.toSet()
        // Fail closed while the remaining carrier policies are implemented; never orphan them.
        return preview.participants.all { participant ->
            participant.editions.all { edition ->
                manualMergeEditionOwner(preview, participant.work.id, edition) != null
            }
        } &&
            supportsActualAudioEdges(preview) &&
            snapshot.library.entries.all { it.workId in roots && it.downloadState in setOf("IDLE", "PAUSED") } &&
            snapshot.personal.corrections.isEmpty() && snapshot.personal.preferences.isEmpty() &&
            snapshot.personal.feedSnapshots.isEmpty() && supportsReadthroughs(preview) &&
            snapshot.library.books.all { it.sourceTreeUri == null && !it.isDownloaded } &&
            snapshot.library.tracks.all { it.localFilePath == null && it.contentHash == null && !it.isDownloaded && remote(it.url) } &&
            snapshot.library.sources.all { remote(it.url) }
    }

    private fun supportsActualAudioEdges(preview: ManualWorkMergePreview): Boolean {
        val library = preview.snapshot.library
        val entries = library.entries.associateBy { it.id }
        val roots = preview.participants.map { it.work.id }.toSet()
        val books = library.books.map { it.id }.toSet()
        val sources = library.sources.associateBy { it.id }
        return library.books.all { book ->
            entries[book.id]?.workId in roots && (preview.participants.any { participant ->
                participant.editions.any { edition ->
                    (manualMergeEditionOwner(preview, participant.work.id, edition) as? ManualWorkMergeEditionOwner.Library)?.bookId == book.id
                }
            } || isUnmaterializedBook(library, book.id))
        } &&
            library.sources.all { it.bookId in books } &&
            library.chapters.all { it.bookId in books } &&
            library.progress.all { it.bookId in books } &&
            library.bookmarks.all { it.bookId in books } &&
            library.settings.all { it.bookId in books } &&
            library.events.all { it.bookId in books } &&
            library.failures.all { it.bookId in books } &&
            library.submissions.all { it.bookId in books } &&
            library.tracks.all { sources[it.sourceId]?.bookId in books }
    }

    private fun isUnmaterializedBook(rows: ManualWorkMergeLibraryRows, bookId: String): Boolean {
        return rows.editions.none { it.workId == bookId } &&
            rows.sources.none { it.bookId == bookId } && rows.chapters.none { it.bookId == bookId } &&
            rows.progress.none { it.bookId == bookId } && rows.bookmarks.none { it.bookId == bookId } &&
            rows.events.none { it.bookId == bookId } && rows.failures.none { it.bookId == bookId } &&
            rows.submissions.none { it.bookId == bookId } &&
            rows.settings.none { it.bookId == bookId && it.sourceKey.isNotBlank() }
    }

    private fun validBookRows(
        operationId: String, books: List<String>, rows: List<PreservedWorkMergeBook>,
    ): Boolean = rows.map { it.bookId }.distinct().size == rows.size &&
        rows.all { it.operationId == operationId && it.autoMergeExcluded } &&
        rows.map { it.bookId }.toSet() == books.toSet()

    private fun supportsReadthroughs(preview: ManualWorkMergePreview): Boolean {
        val entries = preview.snapshot.library.entries.associateBy { it.id }
        val editions = preview.participants.flatMap { it.editions }.associateBy { it.edition.id }
        val roots = preview.participants.map { it.work.id }.toSet()
        return preview.snapshot.library.readthroughs.all { pass ->
            val entry = entries[pass.libraryEntryId]
            pass.workId in roots && entry?.workId == pass.workId && when (pass.format) {
                "AUDIO" -> pass.editionId?.let { editionId ->
                    val edition = editions[editionId]
                    // The personal Entry owns the reading, not the physical narration.
                    // Resolve the selected Edition independently and require the same Work.
                    edition != null && manualMergeEditionOwner(preview, pass.workId, edition) != null
                } == true
                "PAPER", "EBOOK" -> pass.editionId == null
                else -> false
            }
        }
    }

    private fun remote(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")

    private fun readAliases(keys: List<String>, targets: List<String>): List<WorkIdentityRedirectRow> {
        require(keys.isNotEmpty() && targets.isNotEmpty())
        val first = keys.joinToString(", ") { "?" }
        val second = targets.joinToString(", ") { "?" }
        return database.openHelper.readableDatabase.query(SimpleSQLiteQuery(
            "SELECT namespace, oldKey, canonicalWorkId, operationId FROM work_identity_redirects WHERE oldKey IN ($first) OR canonicalWorkId IN ($second) ORDER BY namespace, oldKey",
            (keys + targets).toTypedArray(),
        )).use { cursor -> buildList {
            while (cursor.moveToNext()) add(WorkIdentityRedirectRow(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)))
        } }
    }

    private suspend fun version(preview: ManualWorkMergePreview): String {
        val roots = preview.participants.map { it.work.id }
        val keys = (roots + preview.participants.map { it.work.mergeKey }).filter { it.isNotBlank() }.distinct()
        val books = participantEntryIds(preview)
        val evidence = mutableListOf(preview.fingerprint, preview.storageFingerprint)
        val sql = database.openHelper.readableDatabase
        if (keys.isNotEmpty()) {
            val placeholders = keys.joinToString(", ") { "?" }
            sql.query(SimpleSQLiteQuery("SELECT namespace, oldKey, canonicalWorkId, operationId FROM work_identity_redirects WHERE oldKey IN ($placeholders) OR canonicalWorkId IN ($placeholders) ORDER BY namespace, oldKey", (keys + keys).toTypedArray())).use {
                while (it.moveToNext()) for (index in 0..3) evidence += it.getString(index)
            }
        }
        if (books.isNotEmpty()) {
            val placeholders = books.joinToString(", ") { "?" }
            sql.query(SimpleSQLiteQuery("SELECT operationId, bookId, editionId, autoMergeExcluded FROM work_merge_preserved_participants WHERE bookId IN ($placeholders) ORDER BY operationId, bookId, editionId", books.toTypedArray())).use {
                while (it.moveToNext()) for (index in 0..3) evidence += it.getString(index)
            }
            sql.query(SimpleSQLiteQuery("SELECT operationId, bookId, autoMergeExcluded FROM work_merge_preserved_books WHERE bookId IN ($placeholders) ORDER BY operationId, bookId", books.toTypedArray())).use {
                while (it.moveToNext()) for (index in 0..2) evidence += it.getString(index)
            }
        }
        return digest(evidence)
    }

    private fun bytesDigest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun metadataChoiceIds(choices: ManualWorkMergeMetadataChoices): List<String?> = listOf(
        choices.seriesTitleFromWorkId, choices.seriesUrlFromWorkId,
        choices.seriesIndexFromWorkId, choices.coverFromWorkId,
    )

    private fun approvalTextIsWellFormed(plan: ManualWorkMergeApprovedPlan): Boolean = buildList {
        add(plan.operationId)
        add(plan.canonicalTitle)
        add(plan.canonicalAuthor)
        addAll(metadataChoiceIds(plan.metadataChoices).filterNotNull())
        for (choice in plan.sourceMetadataChoices.items) {
            add(choice.sourceId)
            add(choice.sourceUrl)
            addAll(sourceChoiceIds(choice).filterNotNull())
        }
    }.all { Charsets.UTF_8.newEncoder().canEncode(it) }

    private fun sourceChoiceIds(choice: ManualWorkMergeSourceClaimMetadataChoice): List<String?> = listOf(
        choice.streamOnlyFromClaimId, choice.coverFromClaimId, choice.durationFromClaimId,
    )

    private fun sortedSourceChoices(choices: ManualWorkMergeSourceMetadataChoices): List<ManualWorkMergeSourceClaimMetadataChoice> =
        choices.items.sortedWith { first, second ->
            val source = compareMergeUtf8(first.sourceId, second.sourceId)
            if (source != 0) source else compareMergeUtf8(first.sourceUrl, second.sourceUrl)
        }

    private fun approval(plan: ManualWorkMergeApprovedPlan, before: ByteArray): String {
        val workApproval = workApproval(plan, before)
        val choices = sortedSourceChoices(plan.sourceMetadataChoices)
        // Source-free plans retain the byte-exact existing v1/v2 Work approval.
        if (choices.isEmpty()) return workApproval
        return digest(buildList {
            add("ManualWorkMergeApproval/v3")
            add(workApproval)
            add(choices.size.toString())
            for (choice in choices) {
                add(choice.sourceId)
                add(choice.sourceUrl)
                for (claimId in sourceChoiceIds(choice)) {
                    add(if (claimId == null) "absent" else "present")
                    if (claimId != null) add(claimId)
                }
            }
        })
    }

    private fun workApproval(plan: ManualWorkMergeApprovedPlan, before: ByteArray): String {
        val identity = listOf(
            plan.operationId, plan.prepared.version, plan.canonicalTitle, plan.canonicalAuthor,
            hex(MessageDigest.getInstance("SHA-256").digest(before)),
        )
        val choices = metadataChoiceIds(plan.metadataChoices)
        // Existing operations with no choices retain their exact v1 approval identity.
        if (choices.all { it == null }) return digest(listOf("ManualWorkMergeApproval/v1") + identity)
        return digest(buildList {
            add("ManualWorkMergeApproval/v2")
            addAll(identity)
            for (workId in choices) {
                add(if (workId == null) "absent" else "present")
                if (workId != null) add(workId)
            }
        })
    }

    private fun digest(values: List<String>): String {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { stream ->
            stream.writeInt(values.size)
            values.forEach { value -> val bytes = value.toByteArray(Charsets.UTF_8); stream.writeInt(bytes.size); stream.write(bytes) }
        }
        return hex(MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun execute(sql: String, argument: String): Int = database.openHelper.writableDatabase.compileStatement(sql).use {
        it.bindString(1, argument); it.executeUpdateDelete()
    }
}
