package com.slukhayka.audiobooks.data.identity

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec-40 #276 (t2) / #1738 — bind and restore meet at the SAME derived
 * document id, and a pre-#1738 document keyed by the raw ANDROID_ID is not
 * part of that path. The fake documents seam stands in for Firestore so the
 * derivation, not the SDK, is what these tests pin.
 */
class FirestoreDeviceBindingsTest {

    private val androidId = "9a3f1c7e55d21b04"
    private val uid = "uid-listener-1"
    private val code = RecoveryCodec.encode("x7k2p9q1@slukhayka.local", "s3cret-password")

    @Test
    fun `bind and restore round-trip through the derived document`() = runBlocking {
        val documents = FakeBindingDocuments()
        FirestoreDeviceBindings(documents) { androidId }.bind(uid, code)

        val derivedId = DeviceBindingDocumentId.of(androidId)!!
        assertEquals(setOf(derivedId), documents.stored.keys)
        assertEquals(uid, documents.stored.getValue(derivedId)["uid"])
        // The cipher semantics are untouched: the raw id still opens the seal.
        assertEquals(
            code,
            DeviceBindingCipher.open(
                androidId,
                documents.stored.getValue(derivedId)["cred"] as String
            )
        )

        val restored = FirestoreDeviceBindings(documents) { androidId }.restoreCredentials()

        assertEquals(
            StoredCredentials(
                uid = uid,
                email = "x7k2p9q1@slukhayka.local",
                password = "s3cret-password",
                nickname = null
            ),
            restored
        )
    }

    @Test
    fun `a pre-1738 document under the raw ANDROID_ID is not read`() = runBlocking {
        val documents = FakeBindingDocuments(
            mutableMapOf<String, Map<String, Any?>>(
                androidId to mapOf(
                    "uid" to uid,
                    "cred" to DeviceBindingCipher.seal(androidId, code)
                )
            )
        )

        assertNull(FirestoreDeviceBindings(documents) { androidId }.restoreCredentials())

        // A later bind writes the derived key and leaves the legacy document alone.
        FirestoreDeviceBindings(documents) { androidId }.bind(uid, code)
        assertTrue(documents.stored.containsKey(androidId))
        assertTrue(documents.stored.containsKey(DeviceBindingDocumentId.of(androidId)))
    }

    @Test
    fun `another device's binding does not open`() = runBlocking {
        val documents = FakeBindingDocuments()
        FirestoreDeviceBindings(documents) { "9a3f1c7e55d21b05" }.bind(uid, code)

        assertNull(FirestoreDeviceBindings(documents) { androidId }.restoreCredentials())
    }

    @Test
    fun `a malformed binding decodes to null`() = runBlocking {
        val derivedId = DeviceBindingDocumentId.of(androidId)!!
        val malformed: List<Map<String, Any?>> = listOf(
            mapOf("uid" to uid),
            mapOf("uid" to "", "cred" to "x"),
            mapOf(
                "uid" to uid,
                "cred" to "not-a-seal"
            )
        )

        for (bad in malformed) {
            val documents = FakeBindingDocuments(mutableMapOf(derivedId to bad))
            assertNull(FirestoreDeviceBindings(documents) { androidId }.restoreCredentials())
        }
    }

    @Test
    fun `a Firestore failure is an honest miss and never throws`() = runBlocking {
        val offline = object : DeviceBindingDocuments {
            override suspend fun read(documentId: String): Map<String, Any?>? =
                throw IllegalStateException("offline")

            override suspend fun write(documentId: String, values: Map<String, Any?>) =
                throw IllegalStateException("offline")
        }
        val bindings = FirestoreDeviceBindings(offline) { androidId }

        assertNull(bindings.restoreCredentials())
        bindings.bind(uid, code) // must not throw
    }

    @Test
    fun `a device without a usable id neither reads nor writes`() = runBlocking {
        val documents = FakeBindingDocuments()
        val bindings = FirestoreDeviceBindings(documents) { null }

        assertFalse(bindings.hasDeviceId())
        assertNull(bindings.restoreCredentials())
        bindings.bind(uid, code)

        assertTrue(documents.stored.isEmpty())
    }

    private class FakeBindingDocuments(
        val stored: MutableMap<String, Map<String, Any?>> = mutableMapOf()
    ) : DeviceBindingDocuments {
        override suspend fun read(documentId: String): Map<String, Any?>? = stored[documentId]

        override suspend fun write(documentId: String, values: Map<String, Any?>) {
            stored[documentId] = values
        }
    }
}
