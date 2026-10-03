package com.slukhayka.audiobooks.data.identity

import android.content.Context
import android.provider.Settings

/**
 * Device id for the legacy local backup cipher (ADR-0055), not cloud
 * authentication. ANDROID_ID is app-scoped since Android 8, survives
 * uninstall and resets on factory reset. It is not a secret. IMEI and
 * every other hardware identifier are forbidden by policy.
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
