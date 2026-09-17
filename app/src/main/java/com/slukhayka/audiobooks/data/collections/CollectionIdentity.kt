package com.slukhayka.audiobooks.data.collections

import java.security.MessageDigest

/**
 * Spec-51 (#694) — the anonymous identity of one collection vote.
 *
 * The raw uid never appears in a public document id: a vote is addressed by
 * `sha256(uid + collectionId)`, so the same person voting on two collections
 * gets two unrelated keys and a vote cannot be cross-linked with a review or
 * with another collection. The vector is PINNED by test — changing the
 * algorithm would re-identify every existing voter.
 */
object CollectionIdentity {

    /**
     * @return the lowercase hex SHA-256 of `uid + collectionId`, or "" when
     * either part is blank — a blank key is never a votable identity.
     */
    fun voterKey(uid: String?, collectionId: String): String {
        if (uid.isNullOrBlank() || collectionId.isBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((uid + collectionId).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
