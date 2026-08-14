package com.example.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.example.App
import com.example.MainActivity

/**
 * Background playback host & Automotive / Android Auto media library service
 * (spec-21 Track A / T1; audit PERF-002 / PERF-021).
 *
 * Migrated from [androidx.media3.session.MediaSessionService] to
 * [MediaLibraryService] to support vehicle head-unit browsing (recent books,
 * favorites, catalogue) via [AudiobookLibrarySessionCallback] while preserving
 * full foreground playback survival, lock screen controls, and headset buttons.
 */
class PlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val sessionActivity = PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val callback = AudiobookLibrarySessionCallback(
            context = this,
            repository = App.instance.repository,
            playerManager = App.instance.playerManager
        )
        mediaLibrarySession = MediaLibrarySession.Builder(this, App.instance.playerManager.player, callback)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaLibrarySession

    override fun onDestroy() {
        // Release only the session; the shared player stays with App.playerManager.
        mediaLibrarySession?.run {
            release()
            mediaLibrarySession = null
        }
        super.onDestroy()
    }

    companion object {
        /**
         * Intent that starts this service and makes Media3 attach the session.
         */
        fun playIntent(context: Context): Intent =
            Intent(context, PlaybackService::class.java)
                .setAction("android.intent.action.PLAY")
    }
}
