package com.slukhayka.audiobooks.data.recommend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class GraphSignatureVerifierTest {
    private val body = """{"schemaVersion":1,"workKeys":["a","b"],"neighbors":[[],[]]}"""
    private val keys = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val signature = Signature.getInstance("SHA256withECDSA").run {
        initSign(keys.private)
        update(body.toByteArray(Charsets.UTF_8))
        Base64.getEncoder().encodeToString(sign())
    }

    @Test fun `accepts the exact body signed by the pinned key`() {
        assertTrue(GraphSignatureVerifier(publicKey).verify(body, signature))
    }

    @Test fun `rejects a tampered body, a tampered signature and a different key`() {
        val verifier = GraphSignatureVerifier(publicKey)
        assertFalse(verifier.verify(body.replace("\"b\"", "\"c\""), signature))
        assertFalse(verifier.verify(body, signature.dropLast(4) + "AAAA"))
        val other = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        assertFalse(GraphSignatureVerifier(Base64.getEncoder().encodeToString(other.public.encoded))
            .verify(body, signature))
    }

    @Test fun `a blank or malformed pin verifies nothing`() {
        assertFalse(GraphSignatureVerifier("").verify(body, signature))
        assertFalse(GraphSignatureVerifier("not-base64!").verify(body, signature))
    }
}
