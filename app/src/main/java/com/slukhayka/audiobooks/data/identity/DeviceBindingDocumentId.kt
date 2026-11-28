package com.slukhayka.audiobooks.data.identity

import java.security.MessageDigest

/**
 * Spec-40 #276 (t2) / #1738 — the `device_bindings` document id for a phone.
 *
 * The collection is publicly readable by design (the fresh-install lookup
 * runs before any auth exists), so the raw ANDROID_ID must not be the key:
 * it would turn the collection into an enumerable register of device
 * identifiers. The key is instead the lowercase SHA-256 hex of the versioned
 * app namespace and the raw id, so the same phone asks for the same document
 * on bind and on restore while the public surface exposes no raw id.
 *
 * Not a secret. ANDROID_ID is a 64-bit value, and anyone who already knows a
 * particular device's raw id can compute its document id — the same input
 * [DeviceBindingCipher] needs for its key. What the derivation removes is the
 * raw identifier from the listable key space (bulk enumeration); it does not
 * make a binding unlinkable to a device whose id is already known.
 *
 * Documents written before this change keep their raw-id key and are not
 * read by the current path; they stay in the collection until a separate
 * cleanup migrates or deletes them. The version suffix is the document-space
 * identity: a new derivation means `v2`, never a silent reinterpretation of
 * `v1`.
 */
object DeviceBindingDocumentId {

    private const val NAMESPACE = "slukhayka/device-bindings/v1"

    /** Separator that cannot occur in the namespace or in ANDROID_ID. */
    private const val SEPARATOR = '\u0000'

    /**
     * The derived document id for [rawDeviceId], or null for a blank id —
     * a blank id carries no device identity, and hashing it would invent one
     * shared by every phone in that state.
     */
    fun of(rawDeviceId: String): String? {
        if (rawDeviceId.isBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$NAMESPACE$SEPARATOR$rawDeviceId".toByteArray(Charsets.UTF_8))
        return hex(digest)
    }

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            for (byte in bytes) {
                val value = byte.toInt() and 0xff
                append(digits[value ushr 4])
                append(digits[value and 0x0f])
            }
        }
    }
}
