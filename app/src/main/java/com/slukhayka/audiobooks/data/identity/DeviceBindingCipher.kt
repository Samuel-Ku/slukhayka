package com.slukhayka.audiobooks.data.identity

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals the local, backup-included password on the same device. The device id
 * is an identifier, not a secret: ciphertext must never be published beside
 * it. Public cloud bindings are retired (ADR-0055). This only protects a local
 * backup when its reader does not also know the device id; hardware-backed
 * keys would additionally protect against a compromised backup plus id, but
 * cannot preserve this same-device reinstall contract.
 *
 * AES-GCM authenticates the blob; wrong-device and tampered input return null.
 */
object DeviceBindingCipher {

    private const val SALT = "slukhayka.device-binding.v1"
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS = 60_000
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /** Seals [recoveryCode] for a device with this id → base64url(iv+ciphertext). */
    fun seal(deviceId: String, recoveryCode: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyFor(deviceId))
        val sealed = cipher.iv + cipher.doFinal(recoveryCode.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sealed)
    }

    /**
     * Opens a sealed blob on a device with this id, or null when the device
     * differs or the blob was tampered with — never throws.
     */
    fun open(deviceId: String, sealed: String): String? {
        val bytes = try {
            Base64.getUrlDecoder().decode(sealed)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (bytes.size <= GCM_IV_BYTES) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                keyFor(deviceId),
                GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_IV_BYTES)
            )
            String(cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            // Wrong key (different device) or tampered tag — an honest miss.
            null
        }
    }

    private fun keyFor(deviceId: String): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(
            (deviceId + SALT).toCharArray(),
            SALT.toByteArray(Charsets.UTF_8),
            PBKDF2_ITERATIONS,
            KEY_BITS
        )
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}
