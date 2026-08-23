package com.slukhayka.audiobooks.data.identity

import java.util.Base64

/**
 * Spec-40 #276 (t2) — the pure recovery-code codec: the stored credential
 * pair (generated email + password) encoded as `SLK1.<base64url(email:pw)>`.
 *
 * The versioned prefix lets the format evolve without mis-decoding older
 * codes; the separator is `:` which cannot occur inside an email address.
 * A garbage, foreign-prefixed, or implausible code decodes to null — a
 * miss, never a crash (the repo's degrade-never rule).
 */
object RecoveryCodec {

    const val PREFIX = "SLK1"

    private const val MAX_EMAIL_LENGTH = 254
    private const val MAX_PASSWORD_LENGTH = 256

    /** Encodes one credential pair into a copyable recovery code. */
    fun encode(email: String, password: String): String {
        val payload = "$email:$password"
        return PREFIX + "." +
            Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    /**
     * Decodes a recovery code back to (email, password), or null when the
     * input is not a code this version can honour.
     */
    fun decode(code: String): kotlin.Pair<String, String>? {
        if (!code.startsWith("$PREFIX.")) return null
        val payload = try {
            String(Base64.getUrlDecoder().decode(code.substring(PREFIX.length + 1)), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val separator = payload.indexOf(':')
        if (separator <= 0 || separator == payload.length - 1) return null
        val email = payload.substring(0, separator)
        val password = payload.substring(separator + 1)
        // Plausibility bounds — a decoded blob that cannot be real
        // credentials is rejected outright instead of failing sign-in later.
        if (email.length > MAX_EMAIL_LENGTH || password.length > MAX_PASSWORD_LENGTH) return null
        return email to password
    }
}
