package com.slukhayka.audiobooks.data.identity

import android.content.Context

/**
 * Spec-40 #275 (t1) — local persistence of the listener identity. The
 * generated credentials MUST survive restarts and ride Android Auto Backup
 * (spec-40 #276), so they live in a dedicated SharedPreferences file whose
 * backup inclusion is pinned in res/xml (backup_rules + data_extraction_rules).
 * An interface so the pure tests and the implementations never touch Android
 * storage directly (the [com.slukhayka.audiobooks.data.privacy.PrivacySettingsStore]
 * precedent).
 *
 * Security: the password is NEVER stored nor backed up as plaintext. It is
 * sealed at rest with [DeviceBindingCipher] under a key derived from this
 * device's ANDROID_ID — the same key derivation as the `device_bindings`
 * payload, so the same phone re-opens it after reinstall (ANDROID_ID
 * survives uninstall) while a Google-cloud backup restored anywhere else —
 * or read by anyone else — yields only opaque ciphertext. A sealed blob
 * that does not open on this device loads as a null password, which every
 * caller already treats as "no credentials" (backup/device-binding sign-in
 * is skipped, the recovery code stays the cross-device path). The email,
 * uid and nickname ride plaintext: without the password they cannot
 * authenticate anything (the `.local` address delivers nowhere).
 */
data class StoredCredentials(
    val uid: String,
    val email: String?,
    val password: String?,
    val nickname: String?
)

interface LocalCredentialStore {
    fun load(): StoredCredentials?

    fun save(credentials: StoredCredentials)

    fun clear()
}

class SharedPreferencesLocalCredentialStore(
    context: Context,
    private val deviceIdProvider: () -> String? = { DeviceIds.androidId(context.applicationContext) }
) : LocalCredentialStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): StoredCredentials? {
        val uid = prefs.getString(KEY_UID, null) ?: return null
        return StoredCredentials(
            uid = uid,
            email = prefs.getString(KEY_EMAIL, null),
            password = readPassword(),
            nickname = prefs.getString(KEY_NICKNAME, null)
        )
    }

    override fun save(credentials: StoredCredentials) {
        val password = credentials.password
        // Seal first, then write: a failed seal must never wipe a password
        // that is still readable.
        val sealed: String? = if (password != null) {
            val deviceId = deviceIdProvider()
            if (deviceId != null) runCatching { DeviceBindingCipher.seal(deviceId, password) }.getOrNull()
            else null
        } else null
        val editor = prefs.edit()
            .putString(KEY_UID, credentials.uid)
            .putString(KEY_EMAIL, credentials.email)
            .putString(KEY_NICKNAME, credentials.nickname)
        when {
            password == null -> editor.remove(KEY_PASSWORD).remove(KEY_PASSWORD_SEALED)
            sealed != null -> editor.putString(KEY_PASSWORD_SEALED, sealed).remove(KEY_PASSWORD)
            // No device id (or a seal failure): degrade-never keeps
            // the legacy plaintext so sign-in still works on-device.
            else -> editor.putString(KEY_PASSWORD, password).remove(KEY_PASSWORD_SEALED)
        }
        editor.apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * The sealed blob wins; the legacy plaintext key is honoured once (for
     * pre-seal installs and their restored backups) and migrated forward so
     * the next backup carries ciphertext only. An unopenable blob — a backup
     * restored on another device, a factory reset, tampering — is a null
     * password, never a crash.
     */
    private fun readPassword(): String? {
        val deviceId = deviceIdProvider()
        prefs.getString(KEY_PASSWORD_SEALED, null)?.let { sealed ->
            if (deviceId == null) return null
            return DeviceBindingCipher.open(deviceId, sealed)
        }
        val legacy = prefs.getString(KEY_PASSWORD, null) ?: return null
        if (deviceId != null) {
            runCatching {
                prefs.edit()
                    .putString(KEY_PASSWORD_SEALED, DeviceBindingCipher.seal(deviceId, legacy))
                    .remove(KEY_PASSWORD)
                    .apply()
            }
        }
        return legacy
    }

    private companion object {
        /**
         * The file name IS the backup contract (res/xml lists it explicitly,
         * spec-40 #276) — rename only together with the backup rules.
         */
        const val PREFS_NAME = "listener_identity"
        const val KEY_UID = "uid"
        const val KEY_EMAIL = "email"
        /** Pre-seal installs only: read for one last migration, never written when sealing works. */
        const val KEY_PASSWORD = "password"
        /** `DeviceBindingCipher`-sealed password — the only form Auto Backup ever carries. */
        const val KEY_PASSWORD_SEALED = "password_sealed"
        const val KEY_NICKNAME = "nickname"
    }
}
