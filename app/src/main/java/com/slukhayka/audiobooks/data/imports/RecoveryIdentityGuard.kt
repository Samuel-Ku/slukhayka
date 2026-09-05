package com.slukhayka.audiobooks.data.imports

import com.slukhayka.audiobooks.data.merge.MergeKey
import com.slukhayka.audiobooks.data.metadata.MetadataAssertions
import com.slukhayka.audiobooks.data.source.SourceBookDetail

/**
 * Pure fail-closed guard for browser recovery. A captured page may refresh
 * tracks only when it proves it belongs to the stored Work and Edition.
 */
internal object RecoveryIdentityGuard {
    /**
     * The identity proof that can be checked before comparing Chapter topology.
     * A mismatch must never be attributed to another narration of the same Work.
     */
    fun matchesWorkAndEdition(
        storedTitle: String,
        storedAuthor: String,
        storedNarrator: String,
        storedLanguage: String,
        captured: SourceBookDetail,
        capturedLanguage: String = captured.language
    ): Boolean {
        if (MergeKey.keyFor(storedTitle, storedAuthor) != MergeKey.keyFor(captured.title, captured.author)) return false
        // Legacy catalogue rows may predate the write-time brand scrub.
        // A placeholder is an absent claim, never evidence of another voice.
        val knownNarrator = MetadataAssertions.normalizeClaimedText(storedNarrator)
        val capturedNarrator = MetadataAssertions.normalizeClaimedText(captured.narrator)
        if (knownNarrator != null && capturedNarrator != null &&
            !knownNarrator.equals(capturedNarrator, ignoreCase = true)
        ) return false
        // A source which declares a language must prove the captured page has
        // the same one. Legacy blank rows continue to recover while a
        // language-bearing Edition fails closed on missing or different language.
        return storedLanguage.isBlank() ||
            storedLanguage.trim().equals(capturedLanguage.trim(), ignoreCase = true)
    }

    fun matches(
        storedTitle: String,
        storedAuthor: String,
        storedNarrator: String,
        storedLanguage: String,
        storedChapterTitles: List<String>,
        captured: SourceBookDetail,
        capturedLanguage: String = captured.language
    ): Boolean {
        if (!matchesWorkAndEdition(storedTitle, storedAuthor, storedNarrator, storedLanguage, captured, capturedLanguage)) return false
        if (storedChapterTitles.size != captured.chapters.size) return false
        return storedChapterTitles.indices.all { index ->
            val expected = storedChapterTitles[index].trim()
            val actual = captured.chapters[index].title.trim()
            expected.isBlank() || actual.isNotBlank() && expected.equals(actual, ignoreCase = true)
        }
    }
}
