package com.slukhayka.audiobooks.data.identity

import android.content.Context
import android.provider.Settings
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Spec-40 #276 (t2) — device ids for the silent same-phone restore. ONLY
 * ANDROID_ID (Settings.Secure; app-scoped since Android 8, survives
 * uninstall, resets on factory reset). IMEI and every other hardware
 * identifier are forbidden by the ticket and by policy.
 */
object DeviceIds {

    fun androidId(context: Context): String? =
        runCatching {
            Settings.Secure.getString(
                context.applicationContext.contentResolver,
                Settings.Secure.ANDROID_ID
            )
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" /* pre-2.3 bug value */ }
}

/**
 * Spec-40 #276 (t2) — the two document operations [FirestoreDeviceBindings]
 * needs, as a seam: production wires them to Firestore, the pure tests to a
 * map. It exists so bind and restore can be proven to address the SAME
 * derived document id without a Firebase runtime.
 */
internal interface DeviceBindingDocuments {
    /** The document's fields, or null when it does not exist. */
    suspend fun read(documentId: String): Map<String, Any?>?

    suspend fun write(documentId: String, values: Map<String, Any?>)
}

/**
 * Spec-40 #276 (t2) / #1738 — the thin Firestore layer for `device_bindings`:
 * a derived document id → `{ uid, cred }`, where `cred` is
 * [DeviceBindingCipher]-sealed. The id comes from
 * [DeviceBindingDocumentId], never from the raw ANDROID_ID: reads are public
 * by design for the fresh-install lookup, so the public key must not be the
 * device identifier itself. A listener writes ONLY its own uid (enforced
 * again by the rules); the sealed payload keeps the credential pair useful
 * on THIS phone alone. Thin glue like every other Firestore store:
 * best-effort and silent.
 */
class FirestoreDeviceBindings internal constructor(
    private val documents: DeviceBindingDocuments,
    private val deviceIdProvider: () -> String?
) {

    /** Production wiring: the real Firestore and the device's raw ANDROID_ID. */
    constructor(
        firestore: FirebaseFirestore,
        deviceIdProvider: () -> String? = { null }
    ) : this(FirestoreBindingDocuments(firestore), deviceIdProvider)

    /** True when this device has a usable id to bind with. */
    fun hasDeviceId(): Boolean = documentId() != null

    /**
     * Reads THIS device's binding and opens its sealed credentials. Returns
     * the previous install's record, or null when absent / another device's /
     * undecryptable / still stored under a pre-#1738 raw id — never throws.
     */
    suspend fun restoreCredentials(): StoredCredentials? {
        val deviceId = deviceIdProvider() ?: return null
        val documentId = DeviceBindingDocumentId.of(deviceId) ?: return null
        val data = try {
            documents.read(documentId)
        } catch (e: Exception) {
            null
        } ?: return null

        // Validate defensively even though the rules already do.
        val uid = data[FIELD_UID] as? String ?: return null
        if (uid.isBlank()) return null
        val sealed = data[FIELD_CRED] as? String ?: return null
        // The cipher key is unchanged: the raw id still seals the payload,
        // only the document key is derived.
        val recoveryCode = DeviceBindingCipher.open(deviceId, sealed) ?: return null
        val (email, password) = RecoveryCodec.decode(recoveryCode) ?: return null
        return StoredCredentials(uid = uid, email = email, password = password, nickname = null)
    }

    /**
     * Writes/refreshes this device's binding after a successful bootstrap or
     * sign-in: own uid only, best-effort fire-and-forget (a denied write is
     * dropped silently — Auto Backup still covers the same phone).
     */
    suspend fun bind(uid: String, recoveryCode: String?) {
        val deviceId = deviceIdProvider() ?: return
        val documentId = DeviceBindingDocumentId.of(deviceId) ?: return
        if (recoveryCode == null) return // nothing restorable to bind
        try {
            documents.write(
                documentId,
                mapOf(
                    FIELD_UID to uid,
                    FIELD_CRED to DeviceBindingCipher.seal(deviceId, recoveryCode)
                )
            )
        } catch (e: Exception) {
            // Degrade-never.
        }
    }

    /** The derived document id, or null when this device has no usable id. */
    private fun documentId(): String? =
        deviceIdProvider()?.let(DeviceBindingDocumentId::of)

    private companion object {
        const val FIELD_UID = "uid"
        const val FIELD_CRED = "cred"
    }
}

/** The production [DeviceBindingDocuments] over the real Firestore. */
private class FirestoreBindingDocuments(
    private val firestore: FirebaseFirestore
) : DeviceBindingDocuments {

    override suspend fun read(documentId: String): Map<String, Any?>? {
        val snapshot = firestore.collection(COLLECTION).document(documentId).get().await()
        return if (snapshot.exists()) snapshot.data else null
    }

    override suspend fun write(documentId: String, values: Map<String, Any?>) {
        firestore.collection(COLLECTION).document(documentId).set(values).await()
    }

    private companion object {
        const val COLLECTION = "device_bindings"
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
