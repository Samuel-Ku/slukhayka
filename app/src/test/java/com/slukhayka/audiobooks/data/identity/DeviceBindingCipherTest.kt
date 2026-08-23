package com.slukhayka.audiobooks.data.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec-40 #276 (t2) — the device-binding seal: the same phone decrypts,
 * another device and tampered ciphertext do not, and nothing throws.
 */
class DeviceBindingCipherTest {

    private val deviceId = "9a3f1c7e55d21b04"
    private val code = RecoveryCodec.encode("x7k2p9q1@slukhayka.local", "s3cret-password")

    @Test
    fun `the same device opens its own seal`() {
        val sealed = DeviceBindingCipher.seal(deviceId, code)

        assertEquals(code, DeviceBindingCipher.open(deviceId, sealed))
    }

    @Test
    fun `another device cannot open the seal`() {
        val sealed = DeviceBindingCipher.seal(deviceId, code)

        assertNull(DeviceBindingCipher.open("different-device-id", sealed))
        assertNull(DeviceBindingCipher.open("", sealed))
    }

    @Test
    fun `tampered ciphertext decodes to null`() {
        val sealed = DeviceBindingCipher.seal(deviceId, code)
        val bytes = java.util.Base64.getUrlDecoder().decode(sealed)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        val tampered = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        assertNull(DeviceBindingCipher.open(deviceId, tampered))
    }

    @Test
    fun `garbage input decodes to null without throwing`() {
        assertNull(DeviceBindingCipher.open(deviceId, ""))
        assertNull(DeviceBindingCipher.open(deviceId, "!!!not-base64!!!"))
        assertNull(DeviceBindingCipher.open(deviceId, "AAAA"))
    }

    @Test
    fun `the seal never contains plain secret material`() {
        val sealed = DeviceBindingCipher.seal(deviceId, code)

        assertNotEquals(code, sealed)
        org.junit.Assert.assertFalse(sealed.contains("slukhayka.local"))
    }

    @Test
    fun `seals are randomized by the IV`() {
        val a = DeviceBindingCipher.seal(deviceId, code)
        val b = DeviceBindingCipher.seal(deviceId, code)

        assertNotEquals(a, b)
        assertEquals(code, DeviceBindingCipher.open(deviceId, a))
        assertEquals(code, DeviceBindingCipher.open(deviceId, b))
    }
}
