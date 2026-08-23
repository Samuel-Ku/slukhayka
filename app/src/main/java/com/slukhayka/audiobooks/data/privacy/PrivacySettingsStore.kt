package com.slukhayka.audiobooks.data.privacy

import android.content.Context

/**
 * Spec-38 T2 (#254) — persistence of the listener's privacy choice. An
 * interface so the pure tests and the door never touch Android storage; the
 * SharedPreferences impl follows the repo's tiny-prefs pattern
 * ([com.slukhayka.audiobooks.player.PlaybackSettings] precedent) — the
 * choice survives restarts, nothing else lives here.
 */
interface PrivacySettingsStore {
    fun load(): PrivacyPrefs
    fun save(prefs: PrivacyPrefs)
}

class SharedPreferencesPrivacySettingsStore(context: Context) : PrivacySettingsStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): PrivacyPrefs = PrivacyPrefs(
        routeMode = prefs.getString(KEY_ROUTE_MODE, null)
            ?.let { stored ->
                RouteMode.entries.firstOrNull { it.name == stored }
            }
            ?: RouteMode.DIRECT,
        proxyAddress = prefs.getString(KEY_PROXY_ADDRESS, null).orEmpty(),
        // Spec-38 T4 (#256): absent key = the ticket's own default — увімкнений.
        dohEnabled = prefs.getBoolean(KEY_DOH_ENABLED, true)
    )

    override fun save(prefs: PrivacyPrefs) {
        this.prefs.edit()
            .putString(KEY_ROUTE_MODE, prefs.routeMode.name)
            .putString(KEY_PROXY_ADDRESS, prefs.proxyAddress)
            .putBoolean(KEY_DOH_ENABLED, prefs.dohEnabled)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "network_privacy_settings"
        const val KEY_ROUTE_MODE = "route_mode"
        const val KEY_PROXY_ADDRESS = "proxy_address"
        const val KEY_DOH_ENABLED = "doh_enabled"
    }
}
