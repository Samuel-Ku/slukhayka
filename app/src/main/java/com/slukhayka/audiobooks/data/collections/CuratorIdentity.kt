package com.slukhayka.audiobooks.data.collections

import java.security.MessageDigest

/**
 * Spec-51 (#691) — the public identity of a curator.
 *
 * A listener's raw uid must NEVER travel: public document ids are derived from
 * `sha256(uid)`, so the Firestore rules and the published documents can be
 * inspected without exposing who the listener is. The vector is pinned by
 * [CuratorIdentityTest] — changing the algorithm silently would re-identify
 * every existing curator, so the test is the guard, not a formality.
 */
object CuratorIdentity {

    /**
     * @return the lowercase hex SHA-256 of [uid], or "" for a blank uid — a
     * blank identity is never publishable, and an empty author id is the honest
     * "nothing to publish" rather than a hash of nothing.
     */
    fun authorId(uid: String?): String {
        if (uid.isNullOrBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(uid.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    /** Only a real identity can own published documents. */
    fun isPublishable(uid: String?): Boolean = authorId(uid).isNotEmpty()
}
