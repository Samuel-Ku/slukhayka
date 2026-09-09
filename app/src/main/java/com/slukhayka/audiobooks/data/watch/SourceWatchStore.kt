package com.slukhayka.audiobooks.data.watch

import android.content.Context
import android.content.SharedPreferences
import com.slukhayka.audiobooks.data.collections.MiniJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ADR-0037 §6 (spec-49 T4) — the persisted Source Watch: a local, NEVER
 * synced per-Work relationship («Чекає на джерело») for a Work whose audio
 * currently plays nowhere. The watch costs nothing — it is checked on the
 * union/refresh cycles and the mapping verdicts that already happen, with
 * no polling loop of its own; the appearance notifies locally and the tap
 * imports through the ordinary doors. Silent auto-import never happens:
 * the appearance announces, the listener acts.
 *
 * SharedPreferences-backed like [com.slukhayka.audiobooks.data.source.SourceAudioRefusal]:
 * synchronous writes, the [watched] flow carries every change. The seen
 * (already-notified) state lives in the same store so a notification fires
 * exactly once per source per Work across process restarts — and remains
 * personal, never synced, exactly like the refusal and the Content
 * Language Preference (ADR-0029).
 */
class SourceWatchStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("source_watch_prefs", Context.MODE_PRIVATE)

    private val _watched = MutableStateFlow(read())

    /** mergeKey → workId, the live state the scanner reads. */
    val watched: StateFlow<Map<String, String>> = _watched.asStateFlow()

    /** Whether the Work behind [mergeKey] is watched right now. */
    fun isWatched(mergeKey: String): Boolean = watched.value.containsKey(mergeKey)

    /** Puts (or re-arms) the watch for the Work behind [mergeKey]. */
    fun watch(mergeKey: String, workId: String) {
        if (mergeKey.isBlank() || workId.isBlank()) return
        val next = watched.value + (mergeKey to workId)
        prefs.edit().putString(KEY_WATCHED, encodeWatched(next)).apply()
        _watched.value = next
    }

    /** Drops the watch — the Work found its audio (or the listener gave up). */
    fun unwatch(mergeKey: String) {
        val next = watched.value - mergeKey
        prefs.edit().putString(KEY_WATCHED, encodeWatched(next)).apply()
        _watched.value = next
        if (mergeKey.isNotBlank()) {
            prefs.edit().remove(seenKey(mergeKey)).apply()
        }
    }

    /** Sources already notified for the Work behind [mergeKey]. */
    fun seenFor(mergeKey: String): Set<String> =
        prefs.getStringSet(seenKey(mergeKey), null).orEmpty().toSet()

    /** Persists the appearance so the same source never notifies twice. */
    fun markSeen(mergeKey: String, sourceIds: Set<String>) {
        if (mergeKey.isBlank() || sourceIds.isEmpty()) return
        prefs.edit()
            .putStringSet(seenKey(mergeKey), seenFor(mergeKey) + sourceIds)
            .apply()
    }

    private fun read(): Map<String, String> =
        runCatching { decodeWatched(prefs.getString(KEY_WATCHED, null)) }
            .getOrDefault(emptyMap())

    private fun seenKey(mergeKey: String) = "seen_$mergeKey"

    private fun encodeWatched(watched: Map<String, String>): String =
        watched.entries.joinToString(
            separator = ",",
            prefix = "{",
            postfix = "}"
        ) { (key, value) -> "\"${jsonEscape(key)}\":\"${jsonEscape(value)}\"" }

    private fun decodeWatched(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val parsed = MiniJson.parse(raw) as? Map<*, *> ?: return emptyMap()
        return parsed.entries.mapNotNull { (key, value) ->
            val mergeKey = key as? String ?: return@mapNotNull null
            val workId = value as? String ?: return@mapNotNull null
            if (mergeKey.isBlank() || workId.isBlank()) return@mapNotNull null
            mergeKey to workId
        }.toMap()
    }

    private fun jsonEscape(value: String): String = buildString {
        for (c in value) when {
            c == '"' || c == '\\' -> append('\\').append(c)
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
    }

    private companion object {
        const val KEY_WATCHED = "watched_merge_keys"
    }
}
