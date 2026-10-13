package com.slukhayka.audiobooks.data.merge

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.slukhayka.audiobooks.data.db.AudiobookDatabase
import com.slukhayka.audiobooks.data.db.PreservedWorkMergeParticipant
import com.slukhayka.audiobooks.data.db.WorkIdentityRedirectRow
import com.slukhayka.audiobooks.data.db.WorkMergeOperationEntity
import com.slukhayka.audiobooks.data.db.WorkMergeOperationState
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
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

data class ManualWorkMergeApprovedPlan(
    val prepared: ManualWorkMergeCommandPreview,
    val operationId: String,
    val canonicalTitle: String,
    val canonicalAuthor: String,
    val metadataChoices: ManualWorkMergeMetadataChoices = ManualWorkMergeMetadataChoices(),
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
 * First local library identity tracer. Additional metadata-carrier policies and
 * application writer/resolver composition must be implemented before UI exposure.
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
        val canonical = MergeKey.keyFor(plan.canonicalTitle, plan.canonicalAuthor)
        if (canonical.isBlank() || canonical.length > 512) return ManualWorkMergeApplyResult.NeedsDecision("Canonical title and author are required")
        val books = participantBooks(plan.prepared.preview)
        return gate.withIdleBooks(books) {
            database.withTransaction {
                val identity = database.workIdentityDao()
                val prior = identity.operationById(plan.operationId)
                if (prior != null) {
                    val proof = JSONObject(prior.approvedIdentity)
                    if (prior.state == WorkMergeOperationState.APPLIED.name &&
                        prior.canonicalWorkId == canonical && proof.getString("approval") == approval(plan, prior.beforeImages)) {
                        return@withTransaction ManualWorkMergeApplyResult.Applied(prior.operationId, prior.canonicalWorkId)
                    }
                    return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Operation identity is already used")
                }
                val roots = plan.prepared.workIds
                val preview = ManualWorkMerge(database.audiobookDao()).prepareManualWorkMerge(roots[0], roots[1])
                if (version(preview) != plan.prepared.version || participantBooks(preview) != books) {
                    return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Prepared library state changed")
                }
                if (preview.participants.size != 2 || preview.conflicts.isNotEmpty()) {
                    return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Participant conflicts need a decision")
                }
                if (!supportsLibraryTracer(preview)) {
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
                val before = journal.capture(scope, books)
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
                val claims = mutableListOf<ManualWorkMergeJournal.Row>()
                for ((pair, rows) in claimGroups) {
                    if (rows.any { row -> (4..6).any { index -> !journal.sameCell(row.cells[index], rows.first().cells[index]) } }) {
                        return@withTransaction ManualWorkMergeApplyResult.NeedsDecision("Source claim metadata needs a decision")
                    }
                    rows.forEach { check(it.cells[7].type == "integer") }
                    val selectedClaim = rows.minBy { it.cells[7].number }
                    val nextId = "$canonical|${pair.first}|${Integer.toHexString(pair.second.hashCode())}"
                    claims += journal.withText(selectedClaim, mapOf("id" to nextId, "workId" to canonical), before.encoding)
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
                val protected = preview.participants.flatMap { participant -> participant.editions.map { edition ->
                    PreservedWorkMergeParticipant(plan.operationId, checkNotNull(edition.bookId), edition.edition.id, true)
                } }.distinctBy { it.bookId to it.editionId }
                context.ensureActive()
                if (existingCanonical) check(journal.updateById(canonicalRow, canonical) == 1)
                else journal.insert(canonicalRow)
                for (row in before.rows.getValue("library_entries")) {
                    val id = journal.text(row, "id", before.encoding)
                    check(journal.updateById(journal.withText(row, mapOf("workId" to canonical), before.encoding), id) == 1)
                }
                // The before-image retains every original claim, including deduplicated donors.
                for (previousId in originalClaimIds) check(execute("DELETE FROM work_sources WHERE id = ?", previousId) == 1)
                for (row in claims) journal.insert(row)
                for (root in roots) {
                    execute("DELETE FROM works_fts WHERE workId = ?", root)
                    if (root != canonical) check(execute("DELETE FROM works WHERE id = ?", root) == 1)
                }
                database.audiobookDao().refreshWorkSearchIndex(canonical)
                identity.insertRedirects(aliases)
                identity.insertPreservedParticipants(protected)
                val after = journal.capture(scope, books)
                val afterBytes = journal.encode(after)
                val afterFingerprint = database.audiobookDao().readManualWorkMergeRows(scope).storageFingerprint
                val proof = JSONObject().put("format", 1).put("approval", approval(plan, beforeBytes))
                    .put("afterFingerprint", afterFingerprint).put("beforeDigest", bytesDigest(beforeBytes))
                    .put("afterDigest", bytesDigest(afterBytes))
                    .put("preserved", JSONArray(protected.map { JSONArray(listOf(it.bookId, it.editionId)) }))
                    .put("canonicalTitle", plan.canonicalTitle)
                    .put("canonicalAuthor", plan.canonicalAuthor)
                    .put("approvalVersion", if (metadataChoiceIds(plan.metadataChoices).all { it == null }) 1 else 2)
                    .put("metadataChoices", JSONObject()
                        .put("seriesTitleFromWorkId", plan.metadataChoices.seriesTitleFromWorkId ?: JSONObject.NULL)
                        .put("seriesUrlFromWorkId", plan.metadataChoices.seriesUrlFromWorkId ?: JSONObject.NULL)
                        .put("seriesIndexFromWorkId", plan.metadataChoices.seriesIndexFromWorkId ?: JSONObject.NULL)
                        .put("coverFromWorkId", plan.metadataChoices.coverFromWorkId ?: JSONObject.NULL))
                identity.insertOperation(WorkMergeOperationEntity(
                    operationId = plan.operationId, state = WorkMergeOperationState.APPLIED.name,
                    canonicalWorkId = canonical, participantWorkIds = JSONArray(roots).toString(),
                    approvedIdentity = proof.toString(), payloadVersion = ManualWorkMergeJournal.VERSION,
                    beforeImages = beforeBytes, afterImages = afterBytes,
                ))
                context.ensureActive()
                ManualWorkMergeApplyResult.Applied(plan.operationId, canonical)
            }
        }
    }

    suspend fun undoManualWorkMerge(operationId: String): ManualWorkMergeUndoResult {
        currentCoroutineContext().ensureActive()
        require(operationId.isNotBlank()) { "Blank operation identity" }
        val initial = database.workIdentityDao().operationById(operationId)
            ?: return ManualWorkMergeUndoResult.NeedsDecision("Operation not found")
        if (initial.state == WorkMergeOperationState.UNDONE.name) return ManualWorkMergeUndoResult.Undone(operationId)
        val protected = database.workIdentityDao().preservedParticipants(operationId)
        return gate.withIdleBooks(protected.map { it.bookId }) {
            database.withTransaction {
                val identity = database.workIdentityDao()
                val operation = identity.operationById(operationId)
                    ?: return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Operation disappeared")
                if (operation.state == WorkMergeOperationState.UNDONE.name) return@withTransaction ManualWorkMergeUndoResult.Undone(operationId)
                if (operation.payloadVersion != ManualWorkMergeJournal.VERSION || operation.state != WorkMergeOperationState.APPLIED.name) {
                    return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Unknown journal format or operation state")
                }
                val rootsJson = JSONArray(operation.participantWorkIds)
                val roots = (0 until rootsJson.length()).map(rootsJson::getString)
                if (roots.size != 2 || roots.distinct().size != 2) return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Invalid participant journal")
                val currentProtected = identity.preservedParticipants(operationId)
                if (currentProtected != protected) return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Participant protection changed")
                val context = currentCoroutineContext()
                val journal = ManualWorkMergeJournal(database, context)
                val proof = JSONObject(operation.approvedIdentity)
                if (proof.getInt("format") != 1 || proof.getString("beforeDigest") != bytesDigest(operation.beforeImages) ||
                    proof.getString("afterDigest") != bytesDigest(operation.afterImages)) {
                    return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Journal payload integrity changed")
                }
                val savedProtected = proof.getJSONArray("preserved")
                val expectedProtected = (0 until savedProtected.length()).map { index ->
                    val pair = savedProtected.getJSONArray(index)
                    require(pair.length() == 2)
                    pair.getString(0) to pair.getString(1)
                }.toSet()
                if (currentProtected.any { !it.autoMergeExcluded } ||
                    currentProtected.map { it.bookId to it.editionId }.toSet() != expectedProtected) {
                    return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Preserved Edition identities changed")
                }
                val before = journal.decode(operation.beforeImages)
                val after = journal.decode(operation.afterImages)
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
                if (books.toSet() != protected.map { it.bookId }.toSet()) return@withTransaction ManualWorkMergeUndoResult.NeedsDecision("Journal and protection disagree")
                val scope = (roots + operation.canonicalWorkId).distinct()
                val current = journal.capture(scope, books)
                if (!journal.encode(current).contentEquals(operation.afterImages) ||
                    database.audiobookDao().readManualWorkMergeRows(scope).storageFingerprint != proof.getString("afterFingerprint")) {
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
                for (row in before.rows.getValue("library_entries")) {
                    check(journal.updateById(row, journal.text(row, "id", before.encoding)) == 1)
                }
                for (row in after.rows.getValue("work_sources")) {
                    check(execute("DELETE FROM work_sources WHERE id = ?", journal.text(row, "id", after.encoding)) == 1)
                }
                for (row in before.rows.getValue("work_sources")) journal.insert(row)
                execute("DELETE FROM works_fts WHERE workId = ?", operation.canonicalWorkId)
                for (row in before.rows.getValue("works_fts")) journal.insert(row)
                if (originalCanonical == null) check(execute("DELETE FROM works WHERE id = ?", operation.canonicalWorkId) == 1)
                identity.deleteOperationRedirects(operationId)
                check(identity.markUndone(operationId) == 1)
                context.ensureActive()
                ManualWorkMergeUndoResult.Undone(operationId)
            }
        }
    }

    private fun participantBooks(preview: ManualWorkMergePreview): List<String> = preview.snapshot.library.entries
        .filter { it.workId in preview.participants.map { participant -> participant.work.id } }
        .map { it.id }.distinct().sortedWith(::compareMergeUtf8)

    private fun supportsLibraryTracer(preview: ManualWorkMergePreview): Boolean {
        val snapshot = preview.snapshot
        val catalog = snapshot.catalog
        val roots = preview.participants.map { it.work.id }.toSet()
        // Fail closed while the remaining carrier policies are implemented; never orphan them.
        return preview.participants.all { it.editions.isNotEmpty() && it.editions.all { edition -> edition.bookId != null && edition.edition.workId == edition.bookId } } &&
            snapshot.library.entries.all { it.workId in roots && it.downloadState in setOf("IDLE", "PAUSED") } &&
            catalog.workFacets.isEmpty() && catalog.editionFacets.isEmpty() && catalog.facetSeries.isEmpty() &&
            catalog.seriesMembers.isEmpty() && catalog.genreStates.isEmpty() && catalog.genreAssertions.isEmpty() &&
            catalog.workGenres.isEmpty() && catalog.popularity.isEmpty() && catalog.vectors.isEmpty() &&
            snapshot.personal.corrections.isEmpty() && snapshot.personal.preferences.isEmpty() &&
            snapshot.personal.feedSnapshots.isEmpty() && snapshot.library.readthroughs.isEmpty() &&
            snapshot.library.books.all { it.sourceTreeUri == null && !it.isDownloaded } &&
            snapshot.library.tracks.all { it.localFilePath == null && it.contentHash == null && !it.isDownloaded && remote(it.url) } &&
            snapshot.library.sources.all { remote(it.url) }
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
        val books = participantBooks(preview)
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
        }
        return digest(evidence)
    }

    private fun bytesDigest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun metadataChoiceIds(choices: ManualWorkMergeMetadataChoices): List<String?> = listOf(
        choices.seriesTitleFromWorkId, choices.seriesUrlFromWorkId,
        choices.seriesIndexFromWorkId, choices.coverFromWorkId,
    )

    private fun approval(plan: ManualWorkMergeApprovedPlan, before: ByteArray): String {
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
