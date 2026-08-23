package com.slukhayka.audiobooks.data.identity

/**
 * Spec-40 #275 (t1) — the listener identity of Слухайка. The app has NO
 * login screens: on first launch a profile is bootstrapped silently
 * (Firebase Anonymous Auth immediately elevated to permanent generated
 * email/password credentials), and the only visible trace is the nickname
 * in ⚙️ Профіль. The seam exists so the UI, the tests and future consumers
 * never touch FirebaseAuth directly — the same deep-module rule as every
 * other data.* package.
 */
data class ListenerProfile(
    val uid: String,
    val nickname: String
)

interface ListenerIdentity {
    /**
     * Silent bootstrap, idempotent, never throws outward. On a fresh install
     * it creates the account (anonymous auth linked with generated
     * credentials) and persists them locally; on later launches it returns
     * the existing profile unchanged. A Firebase-less environment degrades
     * to a local-only identity so the app keeps working.
     */
    suspend fun ensure(): ListenerProfile

    /** The current profile, or null before the first [ensure] succeeded. */
    suspend fun current(): ListenerProfile?

    /** Persists a new public nickname (the listener's own choice). */
    suspend fun setNickname(nickname: String)

    /**
     * Spec-40 #276 (t2): the recovery code encoding this listener's stored
     * credential pair — null when no credentials exist (or the feature is
     * unavailable).
     */
    suspend fun recoveryCode(): String?

    /**
     * Spec-40 #276 (t2): signs in from a recovery code and adopts that
     * profile; null when the code is garbage or the sign-in fails. Never
     * throws outward.
     */
    suspend fun restoreFromCode(code: String): ListenerProfile?
}
