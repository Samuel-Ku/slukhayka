package com.slukhayka.audiobooks.data.ingest

import com.slukhayka.audiobooks.data.db.AudiobookDao
import com.slukhayka.audiobooks.data.db.SubmissionStateEntity

/**
 * Spec-53 T3 — the restart-safe carrier of listener-submission states.
 * The flow reads it on every verdict, so a store shared across a process
 * restart still settles an awaiting submission. The in-memory
 * implementation backs JVM tests; the Room one is production.
 */
data class SubmissionState(
    val sourceId: String,
    val url: String,
    val bookId: String,
    val metadataJson: String,
    val channelId: String,
    val state: State,
    val reason: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    enum class State { AWAITING_PLAY, PUBLISHED, REFUSED }
}

interface SubmissionStateStore {
    suspend fun save(row: SubmissionState)
    suspend fun bySourceId(sourceId: String): SubmissionState?
    suspend fun awaiting(): List<SubmissionState>
    suspend fun updateState(sourceId: String, state: SubmissionState.State, reason: String?, updatedAt: Long)
}

/** Process-local fallback (tests, store-less composition) — multi-slot. */
class InMemorySubmissionStateStore : SubmissionStateStore {
    private val rows = LinkedHashMap<String, SubmissionState>()
    override suspend fun save(row: SubmissionState) {
        rows[row.sourceId] = row
    }
    override suspend fun bySourceId(sourceId: String): SubmissionState? = rows[sourceId]
    override suspend fun awaiting(): List<SubmissionState> =
        rows.values.filter { it.state == SubmissionState.State.AWAITING_PLAY }
    override suspend fun updateState(
        sourceId: String,
        state: SubmissionState.State,
        reason: String?,
        updatedAt: Long
    ) {
        rows[sourceId]?.let { rows[sourceId] = it.copy(state = state, reason = reason, updatedAt = updatedAt) }
    }
}

/** The production store: Room-backed, survives restarts. */
class RoomSubmissionStateStore(private val dao: AudiobookDao) : SubmissionStateStore {
    override suspend fun save(row: SubmissionState) {
        dao.upsertSubmissionState(row.toEntity())
    }
    override suspend fun bySourceId(sourceId: String): SubmissionState? =
        dao.submissionStateBySourceId(sourceId)?.toModel()
    override suspend fun awaiting(): List<SubmissionState> =
        dao.awaitingSubmissionStates().map { it.toModel() }
    override suspend fun updateState(
        sourceId: String,
        state: SubmissionState.State,
        reason: String?,
        updatedAt: Long
    ) {
        dao.updateSubmissionState(sourceId, state.name, reason, updatedAt)
    }

    private fun SubmissionState.toEntity() = SubmissionStateEntity(
        sourceId = sourceId,
        url = url,
        bookId = bookId,
        metadataJson = metadataJson,
        channelId = channelId,
        state = state.name,
        reason = reason,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun SubmissionStateEntity.toModel() = SubmissionState(
        sourceId = sourceId,
        url = url,
        bookId = bookId,
        metadataJson = metadataJson,
        channelId = channelId,
        state = runCatching { SubmissionState.State.valueOf(state) }
            .getOrDefault(SubmissionState.State.REFUSED),
        reason = reason,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
