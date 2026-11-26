package com.slukhayka.audiobooks.data.recommend

import okio.ByteString.Companion.decodeBase64
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Pinned ECDSA P-256 verifier for the published content graph.
 *
 * The worker never signs; a separate delivery process signs the exact graph
 * bytes (`SHA256withECDSA`, DER signature, base64) with a key the model
 * process never has. A blank or malformed pin verifies nothing, so a build
 * without a reviewed public key cannot accept a graph.
 */
class GraphSignatureVerifier(publicKeyBase64: String) {
    private val pinned: PublicKey? = runCatching {
        if (publicKeyBase64.isBlank()) return@runCatching null
        val der = publicKeyBase64.decodeBase64()?.toByteArray() ?: return@runCatching null
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }.getOrNull()

    fun verify(body: String, signatureBase64: String): Boolean {
        val key = pinned ?: return false
        val signature = signatureBase64.decodeBase64()?.toByteArray() ?: return false
        return runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(body.toByteArray(Charsets.UTF_8))
                verify(signature)
            }
        }.getOrDefault(false)
    }
}
