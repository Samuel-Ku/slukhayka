package com.slukhayka.audiobooks.data.availability

import android.content.Context
import android.content.SharedPreferences
import com.slukhayka.audiobooks.data.collections.MiniJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ADR-0042 §1 (spec-56, ticket #728) — the persisted last availability
 * verdict per Work (mergeKey). Local and never synced, exactly like the
 * refusal and the Source Watch: it is a convenience projection of checks
 * that already happen, not a new source of truth.
 *
 * SharedPreferences-backed with synchronous writes and a [verdicts] flow, so
 * a card shows the last observed state after a restart; a Work with no
 * recorded verdict simply has no entry.
 */
class LibraryAvailabilityStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("library_availability_prefs", Context.MODE_PRIVATE)

    private val _verdicts = MutableStateFlow(read())

    /** mergeKey → the last observed verdict. */
    val verdicts: StateFlow<Map<String, AvailabilityVerdict>> = _verdicts.asStateFlow()

    /** The last observed verdict of the Work, or null when never checked. */
    fun verdictFor(mergeKey: String): AvailabilityVerdict? =
        if (mergeKey.isBlank()) null else verdicts.value[mergeKey]

    /** Records a fresh verdict for the Work, replacing any previous one. */
    fun record(
        mergeKey: String,
        status: AvailabilityStatus,
        sourceId: String = "",
        observedAtMs: Long
    ) {
        if (mergeKey.isBlank()) return
        val next = verdicts.value + (mergeKey to AvailabilityVerdict(status, sourceId, observedAtMs))
        prefs.edit().putString(KEY_VERDICTS, encode(next)).apply()
        _verdicts.value = next
    }

    private fun read(): Map<String, AvailabilityVerdict> =
        runCatching { decode(prefs.getString(KEY_VERDICTS, null)) }.getOrDefault(emptyMap())

    private fun encode(verdicts: Map<String, AvailabilityVerdict>): String =
        verdicts.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
            "\"${escape(key)}\":\"${escape("${value.status.name}|${value.sourceId}|${value.observedAtMs}")}\""
        }

    private fun decode(raw: String?): Map<String, AvailabilityVerdict> {
        if (raw.isNullOrBlank()) return emptyMap()
        val parsed = MiniJson.parse(raw) as? Map<*, *> ?: return emptyMap()
        return parsed.entries.mapNotNull { (key, value) ->
            val mergeKey = key as? String ?: return@mapNotNull null
            val encoded = value as? String ?: return@mapNotNull null
            val parts = encoded.split('|')
            if (mergeKey.isBlank() || parts.size != 3) return@mapNotNull null
            val status = runCatching { AvailabilityStatus.valueOf(parts[0]) }.getOrNull()
                ?: return@mapNotNull null
            val observedAt = parts[2].toLongOrNull() ?: return@mapNotNull null
            mergeKey to AvailabilityVerdict(status, parts[1], observedAt)
        }.toMap()
    }

    private fun escape(value: String): String = buildString {
        for (c in value) when {
            c == '"' || c == '\\' -> append('\\').append(c)
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
    }

    private companion object {
        const val KEY_VERDICTS = "verdicts"
    }
}
