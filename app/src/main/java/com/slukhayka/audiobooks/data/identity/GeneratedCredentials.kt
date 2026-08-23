package com.slukhayka.audiobooks.data.identity

import kotlin.random.Random

/**
 * Spec-40 #275 (t1) — the GENERATED permanent credentials behind the silent
 * profile: a random `<rand>@slukhayka.local` email and a random password.
 * Firebase Anonymous Auth alone would expire (Firebase reaps anonymous
 * accounts), so the anonymous session is immediately elevated with these
 * credentials via linkWithCredential — public Firebase API only, no custom
 * backend. The pair never leaves the device except encoded as the t2
 * recovery code / encrypted device binding. Pure: seeded Random makes tests
 * deterministic.
 */
object GeneratedCredentials {

    /** The fake mail domain — never delivers anything, exists for Firebase only. */
    const val EMAIL_DOMAIN = "slukhayka.local"

    const val PASSWORD_LENGTH = 32
    private const val EMAIL_LOCAL_PART_LENGTH = 20
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** One generated (email, password) pair. */
    data class Pair(val email: String, val password: String)

    fun generate(random: Random = Random.Default): Pair = Pair(
        email = "${random.localPart()}@$EMAIL_DOMAIN",
        password = random.token(PASSWORD_LENGTH)
    )

    private fun Random.localPart(): String = buildString {
        repeat(EMAIL_LOCAL_PART_LENGTH) { append(ALPHABET[nextInt(ALPHABET.length)]) }
    }

    private fun Random.token(length: Int): String = buildString {
        repeat(length) { append(ALPHABET[nextInt(ALPHABET.length)]) }
    }
}
