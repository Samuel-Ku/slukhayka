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
 * Spec-40 #276 (t2) — the thin Firestore layer for `device_bindings`:
 * `{ANDROID_ID} → { uid, cred }`, where `cred` is [DeviceBindingCipher]-
 * sealed. A listener writes ONLY its own uid (enforced again by the rules);
 * the lookup happens on a fresh install BEFORE any auth exists, so reads
 * are public by design — the sealed payload keeps that safe. Thin glue like
 * every other Firestore store: no unit tests here, best-effort and silent.
 */
class FirestoreDeviceBindings(
    private val firestore: FirebaseFirestore,
    private val deviceIdProvider: () -> String? = { null }
) {

    /** True when this device has a usable id to bind with. */
    fun hasDeviceId(): Boolean = deviceIdProvider() != null

    /**
     * Reads THIS device's binding and opens its sealed credentials. Returns
     * the previous install's record, or null when absent / another device's /
     * undecryptable — never throws.
     */
    suspend fun restoreCredentials(): StoredCredentials? {
        val deviceId = deviceIdProvider() ?: return null
        val data = try {
            val snapshot = firestore.collection(COLLECTION).document(deviceId).get().await()
            if (!snapshot.exists()) null else snapshot.data
        } catch (e: Exception) {
            null
        } ?: return null

        // Validate defensively even though the rules already do.
        val uid = data[FIELD_UID] as? String ?: return null
        if (uid.isBlank()) return null
        val sealed = data[FIELD_CRED] as? String ?: return null
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
        if (recoveryCode == null) return // nothing restorable to bind
        try {
            firestore.collection(COLLECTION).document(deviceId).set(
                mapOf(
                    FIELD_UID to uid,
                    FIELD_CRED to DeviceBindingCipher.seal(deviceId, recoveryCode)
                )
            )
        } catch (e: Exception) {
            // Degrade-never.
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }

    private companion object {
        const val COLLECTION = "device_bindings"
        const val FIELD_UID = "uid"
        const val FIELD_CRED = "cred"
    }
}
