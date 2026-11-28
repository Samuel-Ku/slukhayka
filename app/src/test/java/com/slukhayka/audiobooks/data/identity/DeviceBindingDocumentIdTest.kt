package com.slukhayka.audiobooks.data.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-40 #276 (t2) / #1738 — the derived `device_bindings` document id:
 * stable for one device, distinct across devices, 64 lowercase hex with no
 * path separator, and never the raw ANDROID_ID.
 */
class DeviceBindingDocumentIdTest {

    private val androidId = "9a3f1c7e55d21b04"

    @Test
    fun `the derivation is stable for the same device`() {
        assertEquals(
            DeviceBindingDocumentId.of(androidId),
            DeviceBindingDocumentId.of(androidId)
        )
    }

    @Test
    fun `the golden vector pins the namespace and the input form`() {
        // sha256("slukhayka/device-bindings/v1" + NUL + raw), computed outside
        // this code; changing the namespace or the separator must change this.
        assertEquals(
            "c31fdccc8c3e3502f819f15774dfbe7f617e54b0db6a38c5135dac4631a1ee7b",
            DeviceBindingDocumentId.of(androidId)
        )
    }

    @Test
    fun `the id is 64 lowercase hex and carries no path separator`() {
        val id = DeviceBindingDocumentId.of(androidId)!!

        assertEquals(64, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(id.contains('/'))
    }

    @Test
    fun `the raw ANDROID_ID never becomes the document id`() {
        for (raw in listOf("9a3f1c7e55d21b04", "0000000000000000", "0123456789abcdef")) {
            assertNotEquals(raw, DeviceBindingDocumentId.of(raw))
        }
    }

    @Test
    fun `different devices get different documents`() {
        assertNotEquals(
            DeviceBindingDocumentId.of("9a3f1c7e55d21b04"),
            DeviceBindingDocumentId.of("9a3f1c7e55d21b05")
        )
    }

    @Test
    fun `a blank id has no document`() {
        assertNull(DeviceBindingDocumentId.of(""))
        assertNull(DeviceBindingDocumentId.of("   "))
    }
}
