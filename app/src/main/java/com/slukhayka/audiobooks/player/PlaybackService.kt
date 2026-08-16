package com.slukhayka.audiobooks.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.MainActivity

/**
 * Background playback host (audit CRITICAL finding PERF-002 / PERF-021:
 * "No foreground service / MediaSessionService — on Android 8+ (especially
 * 12+) background audio is killed within minutes").
 *
 * The service wraps the application-scoped player from [App.playerManager] in
 * a [MediaSession]. It deliberately does NOT own the player: the player lives
 * in [AudioPlayerManager] for the process lifetime, so the UI keeps driving it
 * directly (the existing `playerState` StateFlow remains the single source of
 * truth for Compose). Media3's `MediaSessionService` handles the rest:
 *
 *  - moves this service into the foreground and shows the media notification
 *    with transport controls when playback starts (`onUpdateNotification`);
 *  - handles Bluetooth/wired headset media buttons through the session;
 *  - exposes the session to Android Auto / lock-screen / Wear devices via the
 *    `androidx.media3.session.MediaSessionService` intent filter.
 *
 * Known trade-off (documented in docs/audits/...): because the player is not
 * owned by the service, an explicit "swipe app away" that kills the process
 * ends playback. The critical daily-use scenario — app backgrounded, screen
 * off, process alive — is fixed, because the foreground service keeps the
 * process alive for the whole listening session.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

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
        mediaSession = MediaSession.Builder(this, App.instance.playerManager.player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        // Release only the session; the shared player stays with App.playerManager.
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    companion object {
        /**
         * Intent that starts this service and makes Media3 attach the session
         * (the default `onStartCommand` treats `Intent.ACTION_PLAY` as a media
         * action and calls `onGetSession` + `addSession`).
         */
        fun playIntent(context: Context): Intent =
            Intent(context, PlaybackService::class.java)
                // Legacy media action Media3's DefaultActionFactory recognizes
                // (it routes the intent to onGetSession + attaches the session).
                .setAction("android.intent.action.PLAY")
    }
}
