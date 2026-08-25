package com.slukhayka.audiobooks.data.metadata

import java.security.MessageDigest
import kotlin.math.abs

/** Shared bounds mirrored by Firestore rules for duration facts. */
object DurationContractLimits {
    const val MAX_EDITION_ID_LENGTH = 300
    const val MAX_PROVENANCE_LENGTH = 100

    fun isPlausibleEditionId(editionId: String): Boolean =
        editionId.isNotBlank() && editionId.length <= MAX_EDITION_ID_LENGTH

    fun isPlausibleProvenance(provenance: DurationProvenance): Boolean =
        provenance.source.isNotBlank() &&
            provenance.source.length <= MAX_PROVENANCE_LENGTH &&
            provenance.method.isNotBlank() &&
            provenance.method.length <= MAX_PROVENANCE_LENGTH &&
            provenance.derivedAt >= 0L
}

/** The write requested for one observed Edition duration. */
enum class DurationWriteDecision {
    CreateCanonical,
    CreateConflict,
    NoOp
}

/** Pure create-only decision at the shared duration store boundary. */
object DurationObservationPolicy {

    /** Small source/probe drift is evidence for the same duration, not a conflict. */
    const val EQUIVALENT_ABSOLUTE_SECONDS = 60L
    const val EQUIVALENT_RELATIVE_PERCENT = 2L

    fun decide(
        canonicalDocumentExists: Boolean,
        canonicalDurationSeconds: Long?,
        candidateSeconds: Long
    ): DurationWriteDecision {
        if (!DurationSanity.isPlausible(candidateSeconds)) return DurationWriteDecision.NoOp
        val equivalentDifference = canonicalDurationSeconds?.let { canonical ->
            val tolerance = maxOf(
                EQUIVALENT_ABSOLUTE_SECONDS,
                canonical * EQUIVALENT_RELATIVE_PERCENT / 100L
            )
            abs(canonical - candidateSeconds) <= tolerance
        } == true
        if (!canonicalDocumentExists) return DurationWriteDecision.CreateCanonical
        if (canonicalDurationSeconds == null || equivalentDifference) return DurationWriteDecision.NoOp
        return DurationWriteDecision.CreateConflict
    }
}

/** Minimal public fact retained when an observation disagrees with the canonical duration. */
data class DurationConflict(
    val editionId: String,
    val candidateSeconds: Long,
    val provenance: DurationProvenance
)

/** Firestore document contract for [DurationConflict]. */
object DurationConflictCodec {

    private val fields = setOf("editionId", "candidateSeconds", "source", "method", "observedAt")

    fun toMap(conflict: DurationConflict): Map<String, Any> = mapOf(
        "editionId" to conflict.editionId,
        "candidateSeconds" to conflict.candidateSeconds,
        "source" to conflict.provenance.source,
        "method" to conflict.provenance.method,
        "observedAt" to conflict.provenance.derivedAt
    )

    fun fromMap(map: Map<String, Any>): DurationConflict? {
        if (map.keys != fields) return null
        val editionId = map["editionId"] as? String ?: return null
        val candidate = (map["candidateSeconds"] as? Number)?.toLong() ?: return null
        val source = map["source"] as? String ?: return null
        val method = map["method"] as? String ?: return null
        val observedAt = (map["observedAt"] as? Number)?.toLong() ?: return null
        val conflict = DurationConflict(
            editionId = editionId,
            candidateSeconds = candidate,
            provenance = DurationProvenance(source, observedAt, method)
        )
        return conflict.takeIf {
            DurationContractLimits.isPlausibleEditionId(it.editionId) &&
                DurationSanity.isPlausible(it.candidateSeconds) &&
                DurationContractLimits.isPlausibleProvenance(it.provenance)
        }
    }
}

/** Stable identity: at most one conflict per Edition/candidate/method. */
object DurationConflictId {

    fun of(conflict: DurationConflict): String {
        val identity = buildString {
            append(conflict.editionId)
            append('\u0000')
            append(conflict.candidateSeconds)
            append('\u0000')
            append(conflict.provenance.method)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
