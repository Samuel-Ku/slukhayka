package com.slukhayka.audiobooks.player

import com.slukhayka.audiobooks.data.metadata.MetadataAssertions
import com.slukhayka.audiobooks.data.source.SourceAccessCandidate
import com.slukhayka.audiobooks.data.source.SourceAccessMode
import com.slukhayka.audiobooks.data.source.SourceAccessPolicy
import java.io.File

/**
 * #471 (spec #462, Implementation Decision 8) — the pure «розумний retry»
 * decision behind the player's «Повторити» tap, in the [StreamHealPolicy]
 * convention: the decision is a pure function (JVM-tested in isolation) and
 * the wiring stays thin (MainViewModel composes the re-resolve).
 *
 * The tap walks the same Source Access Order as every other opener
 * (CONTEXT.md, ADR-0026):
 *
 *  1. **LOCAL** — the chapter's downloaded file exists → play from it. The
 *     local copy always wins over knocking on the dead remote (the regression
 *     contract: «скачана книга грає після смерті remote»).
 *  2. **Re-resolve** — otherwise the book's sources are re-resolved once
 *     through the LOCAL → DIRECT → UNKNOWN chain (with the #469 cross-source
 *     search before giving up), bounded by [SmartRetryMemo] — ADR-0019: a
 *     retry attempt sequence is never a loop. A BROWSER source is never
 *     opened implicitly.
 *  3. **Honest failure** — nothing found → the honest «Книга недоступна»
 *     state. The UI offers explicit browser doors for ANY BROWSER source
 *     ([browserDoorSourceIds] generalizes the 4read-only door of the old
 *     recovery).
 */
object SmartRetryPolicy {

    /**
     * The «file with real content» threshold — the same one [buildMediaItem],
     * the heal gate and the READY listener already apply, so the retry's
     * local verdict can never disagree with what the engine would play.
     */
    const val LOCAL_MIN_BYTES = 100L

    sealed interface Decision {
        /** (a) the downloaded chapter file exists — play from it. */
        data object PlayLocal : Decision

        /** (b) one bounded re-resolve of the book's sources may run. */
        data object ReResolve : Decision

        /** (c) honest «Книга недоступна» + the explicit browser doors. */
        data object Unavailable : Decision
    }

    /**
     * The retry decision. The local file wins UNCONDITIONALLY — even when the
     * re-resolve memo already spent its window; a re-resolve runs only while
     * the memo allows it ([SmartRetryMemo.canAttempt]).
     */
    fun decide(localFileReady: Boolean, canReResolve: Boolean): Decision = when {
        localFileReady -> Decision.PlayLocal
        canReResolve -> Decision.ReResolve
        else -> Decision.Unavailable
    }

    /**
     * Whether [localFilePath] points at a playable local copy: the file
     * exists with real content ([LOCAL_MIN_BYTES]).
     */
    fun localFileReady(localFilePath: String?): Boolean {
        if (localFilePath.isNullOrBlank()) return false
        val file = File(localFilePath)
        return file.exists() && file.length() > LOCAL_MIN_BYTES
    }

    /**
     * The distinct BROWSER-mode source ids of a book, in the given order —
     * the explicit browser doors of the honest failure. DIRECT and UNKNOWN
     * sources never get a door: they are re-resolved, not browsed, and the
     * browser stays an explicit listener action (ADR-0026).
     */
    fun browserDoorSourceIds(sourceIds: Collection<String>): List<String> =
        sourceIds
            .filter { it.isNotBlank() && SourceAccessPolicy.modeFor(it) == SourceAccessMode.BROWSER }
            .distinct()

    /**
     * #519 — the same-narration verdict behind the alternative-source offer:
     * true only when BOTH claims are real (normalized — the «4read…»
     * placeholder is an absent claim, never evidence of a voice) and agree
     * case-insensitively. A blank stored narrator is an UNKNOWN narration →
     * false: the candidate is never silently trusted, the listener confirms
     * (or the alternative is offered as a confirmation, never auto-started).
     */
    fun isSameNarration(storedNarrator: String, candidateNarrator: String): Boolean {
        val stored = MetadataAssertions.normalizeClaimedText(storedNarrator) ?: return false
        val candidate = MetadataAssertions.normalizeClaimedText(candidateNarrator) ?: return false
        return stored.equals(candidate, ignoreCase = true)
    }

    /**
     * #519 — the DIRECT-mode alternatives a search result offers beyond the
     * sources the book already has (LOCAL→DIRECT→UNKNOWN order preserved;
     * only resolvable URLs). The list is a HINT for the explicit next action —
     * it never triggers an import or a source switch by itself.
     */
    fun alternativeSourceCandidates(
        bookSourceIds: Collection<String>,
        resultSourceIds: Collection<String>,
        urlOf: (String) -> String
    ): List<String> {
        val known = bookSourceIds.toSet()
        val resultCandidates = resultSourceIds
            .filter { it.isNotBlank() && it !in known && urlOf(it).isNotBlank() }
            .map { SourceAccessCandidate(it, url = urlOf(it)) }
        return SourceAccessPolicy.order(resultCandidates)
            .filter { it.accessMode == SourceAccessMode.DIRECT }
            .map { it.sourceId }
            .distinct()
    }
}
