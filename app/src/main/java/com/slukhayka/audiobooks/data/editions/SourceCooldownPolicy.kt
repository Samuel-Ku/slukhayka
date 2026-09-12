package com.slukhayka.audiobooks.data.editions

import java.io.File

/** #530 — how a Source has been failing lately (never deletion, only a pause). */
data class SourceFailureRecord(
    val sourceId: String,
    val consecutiveFailures: Int,
    val lastFailedAt: Long,
    val cooldownUntil: Long,
    /** #530 AC4 — the Source's own availability history: its last success. */
    val lastSuccessAt: Long? = null
)

/**
 * #530 — the BOUNDED cooldown of a failed Source. After a failure the Source is
 * skipped as a fallback candidate for a doubling, capped window, so a broken
 * source can never drive a fallback loop — but it is never deleted, and once
 * the window passes it is eligible again. A success clears the record outright.
 */
object SourceCooldownPolicy {

    /** One failure parks the source for a minute. */
    const val FIRST_FAILURE_MS: Long = 60_000L

    /** Bounded by design: at most half an hour, however many failures pile up. */
    const val MAX_COOLDOWN_MS: Long = 30L * 60_000L

    /** The cooldown after [consecutiveFailures] failures (≤0 → none). */
    fun cooldownFor(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 0) return 0L
        var value = FIRST_FAILURE_MS
        repeat(consecutiveFailures - 1) {
            value = (value * 2).coerceAtMost(MAX_COOLDOWN_MS)
        }
        return value.coerceAtMost(MAX_COOLDOWN_MS)
    }

    /** The record after ONE more failure of [sourceId]. */
    fun recordFailure(sourceId: String, previous: SourceFailureRecord?, now: Long): SourceFailureRecord {
        val failures = (previous?.consecutiveFailures ?: 0) + 1
        val cooldown = cooldownFor(failures)
        return SourceFailureRecord(
            sourceId = sourceId,
            consecutiveFailures = failures,
            lastFailedAt = now,
            cooldownUntil = now + cooldown,
            // The success history survives a later failure.
            lastSuccessAt = previous?.lastSuccessAt
        )
    }

    /**
     * A success clears the cooldown immediately but KEEPS the Source's
     * availability history (its last success) — the record is never deleted.
     */
    fun recordSuccess(sourceId: String, previous: SourceFailureRecord?, now: Long): SourceFailureRecord =
        SourceFailureRecord(
            sourceId = sourceId,
            consecutiveFailures = 0,
            lastFailedAt = previous?.lastFailedAt ?: 0L,
            cooldownUntil = 0L,
            lastSuccessAt = now
        )

    /** True while the Source is parked and must be skipped as a candidate. */
    fun isCoolingDown(record: SourceFailureRecord?, now: Long): Boolean =
        record != null && now < record.cooldownUntil
}

/**
 * #530 — the persisted cooldown records, so a failed Source stays parked across
 * a restart instead of being retried by every launch. A plain TSV, best-effort:
 * a missing or damaged file is an empty map, never a crash.
 */
class SourceCooldownStore(private val file: File) {

    fun load(): Map<String, SourceFailureRecord> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 4 || parts[0].isBlank()) return@mapNotNull null
                val failures = parts[1].toIntOrNull() ?: return@mapNotNull null
                val lastFailedAt = parts[2].toLongOrNull() ?: return@mapNotNull null
                val cooldownUntil = parts[3].toLongOrNull() ?: return@mapNotNull null
                val lastSuccessAt = parts.getOrNull(4)?.toLongOrNull()
                parts[0] to SourceFailureRecord(parts[0], failures, lastFailedAt, cooldownUntil, lastSuccessAt)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Records one failure of [sourceId] and persists the new window. */
    fun recordFailure(sourceId: String, now: Long): SourceFailureRecord {
        if (sourceId.isBlank()) {
            return SourceFailureRecord("", 1, now, now + SourceCooldownPolicy.FIRST_FAILURE_MS)
        }
        val records = load().toMutableMap()
        val record = SourceCooldownPolicy.recordFailure(sourceId, records[sourceId], now)
        records[sourceId] = record
        save(records)
        return record
    }

    /** A success clears the record; the Source is immediately eligible again. */
    fun recordSuccess(sourceId: String, now: Long) {
        if (sourceId.isBlank()) return
        val records = load().toMutableMap()
        records[sourceId] = SourceCooldownPolicy.recordSuccess(sourceId, records[sourceId], now)
        save(records)
    }

    fun record(sourceId: String): SourceFailureRecord? = load()[sourceId]

    /** The Source may be offered as a candidate only when it is not parked. */
    fun isEligible(sourceId: String, now: Long): Boolean =
        !SourceCooldownPolicy.isCoolingDown(load()[sourceId], now)

    private fun save(records: Map<String, SourceFailureRecord>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                records.values.joinToString("\n") { record ->
                    listOf(
                        record.sourceId,
                        record.consecutiveFailures.toString(),
                        record.lastFailedAt.toString(),
                        record.cooldownUntil.toString(),
                        record.lastSuccessAt?.toString().orEmpty()
                    ).joinToString("\t")
                }
            )
        }
    }
}
