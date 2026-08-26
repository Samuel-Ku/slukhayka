package com.slukhayka.audiobooks.player

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * ADR-0024 (#362): the Cast options the framework reads via the manifest
 * meta-data. The Default Media Receiver serves audio-only casting — no
 * custom web receiver, so no hosted receiver surface to maintain. The sender
 * App ID registration (one-time, Cast Developer Console) is a follow-up; the
 * default receiver id keeps development and smoke tests working meanwhile.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(appContext: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
