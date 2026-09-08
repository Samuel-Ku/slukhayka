package com.slukhayka.audiobooks.data.identity

import kotlin.random.Random

/**
 * Spec-40 #275 (t1) — the in-memory [ListenerIdentity] behind the seam's
 * JVM tests: same contract as the Firebase implementation (idempotent
 * ensure, persisted nickname), zero Android/Firebase dependencies.
 */
class FakeListenerIdentity(
    private val random: Random = Random.Default,
    /** Seeds the profile directly (tests that pin a concrete uid, e.g. sync gates). */
    seededProfile: ListenerProfile? = null,
) : ListenerIdentity {

    private var profile: ListenerProfile? = seededProfile

    /** Simulates a cold start: forgets everything before the next ensure(). */
    fun reset() {
        profile = null
    }

    override suspend fun ensure(): ListenerProfile {
        // Idempotent by contract: the first call mints one identity, every
        // later call returns the SAME one unchanged.
        return profile ?: ListenerProfile(
            uid = "fake-" + random.nextInt(1_000_000),
            nickname = Nicknames.generate(random)
        ).also { profile = it }
    }

    override suspend fun current(): ListenerProfile? = profile

    override suspend fun setNickname(nickname: String) {
        val clean = nickname.trim().take(LocalOnlyIdentity.NICKNAME_MAX_LENGTH)
        if (clean.isEmpty()) return
        val base = profile ?: ensure()
        profile = base.copy(nickname = clean)
    }

    override suspend fun recoveryCode(): String? = null

    override suspend fun restoreFromCode(code: String): ListenerProfile? = null
}
