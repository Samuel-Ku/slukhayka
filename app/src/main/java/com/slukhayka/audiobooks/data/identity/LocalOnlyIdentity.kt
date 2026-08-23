package com.slukhayka.audiobooks.data.identity

import kotlin.random.Random

/**
 * Spec-40 #275 (t1) — the degraded identity used when Firebase is not
 * configured at all (no google-services.json keys) or is unreachable during
 * the very first launch: a purely local profile (`local-…` uid) persisted
 * through the same store. It keeps ⚙️ Профіль functional offline; a later
 * successful [ensure] on [FirebaseListenerIdentity] upgrades the record to
 * a real account while carrying the chosen nickname over.
 */
class LocalOnlyIdentity(
    private val local: LocalCredentialStore,
    private val random: Random = Random.Default
) : ListenerIdentity {

    override suspend fun ensure(): ListenerProfile {
        val stored = local.load()
        val record = if (stored != null && stored.uid.startsWith(LOCAL_UID_PREFIX)) {
            stored
        } else {
            // First run (or upgrading away from a stale foreign record):
            // mint a stable local uid, keep any already-chosen nickname.
            StoredCredentials(
                uid = LOCAL_UID_PREFIX + randomHex(),
                email = null,
                password = null,
                nickname = stored?.nickname
            ).also { local.save(it) }
        }
        val nickname = record.nickname ?: Nicknames.generate(random).also {
            local.save(record.copy(nickname = it))
        }
        return ListenerProfile(record.uid, nickname)
    }

    override suspend fun current(): ListenerProfile? {
        val stored = local.load() ?: return null
        return ListenerProfile(stored.uid, stored.nickname ?: return null)
    }

    override suspend fun setNickname(nickname: String) {
        val clean = nickname.trim().take(NICKNAME_MAX_LENGTH)
        if (clean.isEmpty()) return
        val stored = local.load() ?: ensure().let { local.load() } ?: return
        local.save(stored.copy(nickname = clean))
    }

    /**
     * Spec-40 #276 (t2): the encoded stored pair when one exists — always
     * null here in practice, because the local-only profile has no
     * credentials to encode.
     */
    override suspend fun recoveryCode(): String? {
        val stored = local.load() ?: return null
        val email = stored.email ?: return null
        val password = stored.password ?: return null
        return runCatching { RecoveryCodec.encode(email, password) }.getOrNull()
    }

    /**
     * Spec-40 #276 (t2): offline adoption of a foreign code — the pair is
     * stored so a later Firebase upgrade can claim the same account; the
     * local uid is kept (there is no server identity to switch to).
     */
    override suspend fun restoreFromCode(code: String): ListenerProfile? {
        val (email, password) = RecoveryCodec.decode(code) ?: return null
        val stored = ensure().let { local.load() } ?: return null
        val upgraded = stored.copy(email = email, password = password)
        local.save(upgraded)
        return ListenerProfile(upgraded.uid, upgraded.nickname.orEmpty())
    }

    private fun randomHex(): String = buildString {
        repeat(LOCAL_UID_HEX_LENGTH) { append(HEX[random.nextInt(HEX.length)]) }
    }

    companion object {
        /** Marks records minted without a server account — replaced on upgrade. */
        const val LOCAL_UID_PREFIX = "local-"

        const val NICKNAME_MAX_LENGTH = 40

        private const val LOCAL_UID_HEX_LENGTH = 16
        private const val HEX = "0123456789abcdef"
    }
}
