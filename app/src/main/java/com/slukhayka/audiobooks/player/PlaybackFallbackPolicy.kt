package com.slukhayka.audiobooks.player

/**
 * #504 — the pure playback-fallback decision: when a chapter's stream dies
 * with a PROVEN remote verdict, the player may swap in the SAME chapter from
 * a direct source of the SAME narration instead of stopping. In the
 * [StreamHealPolicy] convention the decision is a pure function (JVM-tested
 * in isolation) and the wiring stays thin (AudioPlayerManager swaps the
 * track URL and re-prepares the same chapter index).
 *
 * Deliberate narrowing of the ticket's "(403/челлендж/мрежа)" trigger list,
 * answering the #479 blocker ("не маскувати локальний баг") without doing
 * #479 itself: only 403/404 attempt — a Cloudflare challenge surfaces as
 * 403 through OkHttpDataSource, while a network error WITHOUT a status
 * code can be a dead radio, airplane mode or a local bug, so it stays on
 * the honest path exactly like the heal policy treats it. Decoder errors,
 * timeouts and anything local never attempt either. Every swap is recorded
 * in the playback event log with the code and both source ids, so a masked
 * bug would still leave a signed trail.
 *
 * Budget mirrors [StreamHealPolicy.MAX_HEAL_ATTEMPTS]: one fallback swap per
 * user-initiated chapter prepare — a dead fallback URL falls through to the
 * honest failure instead of looping.
 */
object PlaybackFallbackPolicy {

    /** One fallback swap per chapter prepare — no fallback chains. */
    const val MAX_FALLBACK_ATTEMPTS = 1

    /**
     * Whether a stream failure may attempt the fallback: only a proven
     * remote 403/404, and only while this chapter prepare has not spent its
     * single fallback yet.
     */
    fun shouldAttempt(responseCode: Int?, fallbackAttempts: Int): Boolean =
        (responseCode == StreamHealPolicy.HTTP_FORBIDDEN || responseCode == StreamHealPolicy.HTTP_NOT_FOUND) &&
            fallbackAttempts < MAX_FALLBACK_ATTEMPTS
}
