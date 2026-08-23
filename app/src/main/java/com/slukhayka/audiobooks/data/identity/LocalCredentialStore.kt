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

class SharedPreferencesLocalCredentialStore(context: Context) : LocalCredentialStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): StoredCredentials? {
        val uid = prefs.getString(KEY_UID, null) ?: return null
        return StoredCredentials(
            uid = uid,
            email = prefs.getString(KEY_EMAIL, null),
            password = prefs.getString(KEY_PASSWORD, null),
            nickname = prefs.getString(KEY_NICKNAME, null)
        )
    }

    override fun save(credentials: StoredCredentials) {
        prefs.edit()
            .putString(KEY_UID, credentials.uid)
            .putString(KEY_EMAIL, credentials.email)
            .putString(KEY_PASSWORD, credentials.password)
            .putString(KEY_NICKNAME, credentials.nickname)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        /**
         * The file name IS the backup contract (res/xml lists it explicitly,
         * spec-40 #276) — rename only together with the backup rules.
         */
        const val PREFS_NAME = "listener_identity"
        const val KEY_UID = "uid"
        const val KEY_EMAIL = "email"
        const val KEY_PASSWORD = "password"
        const val KEY_NICKNAME = "nickname"
    }
}
