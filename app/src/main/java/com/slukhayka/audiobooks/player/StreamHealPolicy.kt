package com.slukhayka.audiobooks.player

import androidx.media3.datasource.HttpDataSource

/**
 * Spec-32 T4 (#234) — the pure self-healing decision for a failed stream:
 * a 404/403 during playback means the source page moved or the CDN blocked
 * the file, so the player re-fetches the page ONCE, swaps in the fresh URL
 * and retries; any other status (server error, network, timeouts) surfaces
 * the honest failure immediately, and an exhausted heal budget never retries
 * a second time in the same chapter prepare.
 *
 * The 404/403 → refetch → retry decision is deliberately a pure function
 * (JVM-tested in isolation, spec-32 Testing Decisions): [shouldHeal] decides
 * from the status + the attempt counter, and [responseCodeOf] extracts the
 * status from the PlaybackException cause chain (the real ExoPlayer failure
 * is wrapped several layers deep — `PlaybackException` →
 * `HttpDataSourceException` → `InvalidResponseCodeException`).
 */
object StreamHealPolicy {

    /** One retry with the fresh URL per chapter prepare — no heal loops. */
    const val MAX_HEAL_ATTEMPTS = 1

    /** HTTP Forbidden — the CDN refused the stream (blocked/moved file). */
    const val HTTP_FORBIDDEN = 403

    /** HTTP Not Found — the stream file moved or was deleted. */
    const val HTTP_NOT_FOUND = 404

    /**
     * #812 — HTTP Gone: підпис у збереженому URL протермінувався.
     *
     * Саме це джерело й повертає сьогодні: сторінка книги віддає 200 і свіжий
     * M3U, а збережена в застосунку підписана адреса — 410. Без 410 у списку
     * застосунок не перечитував сторінку й чесно здавався, хоч лік існує.
     */
    const val HTTP_GONE = 410

    /**
     * The HTTP status of the stream failure, or null when the error chain
     * carries none (timeouts, network failures, decoder errors). Walks the
     * whole cause chain so a wrapped ExoPlayer failure still yields its code.
     */
    fun responseCodeOf(error: Throwable?): Int? {
        var current = error
        while (current != null) {
            if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
            current = current.cause
        }
        return null
    }

    /**
     * Whether a stream failure heals: only a 404/403 — a moved or blocked
     * file — and only while the heal budget for the current chapter prepare
     * is not exhausted.
     *
     * #528 — a [SubstitutedStreamException] heals on the same budget: the CDN
     * answered 200 with a body that cannot be the chapter, and the page's
     * fresh URL is the same remedy as for a file that moved. The budget is
     * shared, so the two causes can never stack into a loop.
     */
    fun shouldHeal(
        responseCode: Int?,
        healAttempts: Int,
        substituted: Boolean = false
    ): Boolean =
        (substituted || responseCode == HTTP_FORBIDDEN || responseCode == HTTP_NOT_FOUND ||
            responseCode == HTTP_GONE) &&
            healAttempts < MAX_HEAL_ATTEMPTS

    /**
     * The mirror of [shouldHeal] for the honest failure path: a 404/403 that
     * already spent the whole budget is a dead file — the player reports
     * «book unavailable» instead of a generic stream error. Kept next to
     * [shouldHeal] so the two can never drift apart (the budget is one).
     *
     * #528 deliberately does NOT count a substitution here. A substituted
     * body means the source is alive and answering — it answered wrong.
     * Calling that «книга недоступна» would be a claim we cannot support, so
     * it keeps the generic honest failure.
     */
    fun budgetExhausted(responseCode: Int?, healAttempts: Int): Boolean =
        (responseCode == HTTP_FORBIDDEN || responseCode == HTTP_NOT_FOUND ||
            responseCode == HTTP_GONE) &&
            healAttempts >= MAX_HEAL_ATTEMPTS

    /** Does this failure's cause chain carry a refused substituted body? */
    fun isSubstituted(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            if (current is SubstitutedStreamException) return true
            current = current.cause
        }
        return false
    }
}

/**
 * #528 — the CDN answered with a body that cannot be the chapter we asked
 * for.
 *
 * Thrown only when the chapter's duration is corroborated by its siblings
 * ([com.slukhayka.audiobooks.data.duration.ChapterDurationConsensus]), so an
 * honest file is never refused on an expectation the substitution itself
 * poisoned.
 */
class SubstitutedStreamException(
    val expectedSeconds: Long,
    val observedSeconds: Long,
    val streamUri: String?
) : java.io.IOException(
    "served body implies ${observedSeconds}s for a ${expectedSeconds}s chapter: $streamUri"
)
