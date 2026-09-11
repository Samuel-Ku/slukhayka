package com.slukhayka.audiobooks.data.source

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ADR-0037 (spec-49 T1) — the persisted personal Source Audio Refusal:
 * the listener's decision that one source (by [SourceIds] id) stops
 * supplying AUDIO. The source stays a full metadata source — catalog
 * sections, «Новинки», union, covers, duration enrichment — while its
 * Sources are excluded from every automatic path: selection probing,
 * automatic Play, offline downloads, playback pairing. Existing Source
 * rows stay in place dormant; undoing the refusal wakes them with no
 * re-import. Never a Tombstone (that anchors at the Work and would kill
 * the metadata this preference preserves) and never synced — the refusal
 * is personal, exactly like the Content Language Preference (ADR-0029).
 *
 * Spec-49 T5 — the same store carries the SEPARATE voluntary publish
 * consent: sharing the refusal means adding one anonymous vote per refused
 * source to the shared counter, nothing else. Off by default; revoking it
 * stops contributions while the local refusal itself stands untouched.
 *
 * SharedPreferences-backed; writes are synchronous and immediate, the
 * flows carry every change so the selection coordinator, the catalog
 * pairing, the download gate and the settings screen all react to ONE
 * source of truth. The `local` pseudo-source is never a state: a
 * refusal stops the SOURCE supplying audio, never the listener's own
 * downloaded files.
 *
 * 4read is refused BUILT-IN, not by listener choice: after the August 2026
 * change its "audio" for a clean client is a 52-second artefact — a scam,
 * not the book. No allow/empty write can re-enable it; [ALWAYS_REFUSED]
 * rides every read and write.
 */
class SourceAudioRefusal(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("source_audio_refusal_prefs", Context.MODE_PRIVATE)

    private val _refusedSources = MutableStateFlow(read())
    val refusedSources: StateFlow<Set<String>> = _refusedSources.asStateFlow()

    private val _publishRefusals = MutableStateFlow(readPublish())
    val publishRefusals: StateFlow<Boolean> = _publishRefusals.asStateFlow()

    /** Whether audio of [sourceId] is refused right now. */
    fun isRefused(sourceId: String): Boolean =
        sourceId.isNotBlank() && sourceId in _refusedSources.value

    /** Adds [sourceId] to the refusal set (idempotent). */
    fun refuse(sourceId: String) {
        setRefused(_refusedSources.value + sourceId)
    }

    /** Removes [sourceId] from the refusal set (idempotent). */
    fun allow(sourceId: String) {
        setRefused(_refusedSources.value - sourceId)
    }

    /** Replaces the whole refusal set (normalized — `local` is never a state). */
    fun setRefused(sourceIds: Set<String>) {
        val normalized = normalize(sourceIds)
        prefs.edit().putStringSet(KEY_REFUSED, normalized).apply()
        _refusedSources.value = normalized
    }

    /** Sets the voluntary anonymous-publish consent (idempotent). */
    fun setPublishRefusals(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PUBLISH_REFUSALS, enabled).apply()
        _publishRefusals.value = enabled
    }

    private fun read(): Set<String> =
        normalize(prefs.getStringSet(KEY_REFUSED, null).orEmpty().toSet())

    private fun readPublish(): Boolean =
        prefs.getBoolean(KEY_PUBLISH_REFUSALS, false)

    companion object {
        private const val KEY_REFUSED = "refused_sources"
        private const val KEY_PUBLISH_REFUSALS = "publish_refusals"

        /**
         * The refusal is about a SOURCE supplying audio. The local
         * pseudo-source is the listener's own files — it is never a state.
         */
        fun normalize(sourceIds: Set<String>): Set<String> =
            sourceIds.filter { it.isNotBlank() && it != "local" }.toSet() + ALWAYS_REFUSED

        /**
         * Audio that is never real audio and therefore never playable:
         * currently 4read, whose clean-client stream is a 52-second artefact.
         */
        val ALWAYS_REFUSED: Set<String> = setOf("4read")
    }
}
