package com.slukhayka.audiobooks.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.slukhayka.audiobooks.App
import com.slukhayka.audiobooks.MainActivity
import com.slukhayka.audiobooks.data.diagnostics.DiagnosticPlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

    // #805 — the resumption callback resolves the freshest progress row through
    // the database, so the session's future is completed off the session's
    // binder thread. Main.immediate keeps the player itself on the application
    // thread, like every other playback caller.
    private val resumeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        App.instance.crashContextTracker.updatePlaybackService(DiagnosticPlaybackService.STARTED)
        val sessionActivity = PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                // ADR-0052 §6 — the service (playback) notification's tap
                // opens the full player, not merely the app.
                putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, App.instance.playerManager.player)
            .setSessionActivity(sessionActivity)
            .setCallback(PlaybackResumptionCallback())
            .build()
        // ADR-0024 (#362): while a cast session is live, the CastPlayer owns
        // the session (notification + media buttons drive the RECEIVER); when
        // it ends, the local player takes back over. A null argument means
        // «restore local».
        App.instance.castController.sessionPlayerSwapper = { remoteOrNull ->
            mediaSession?.player = remoteOrNull ?: App.instance.playerManager.player
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        App.instance.crashContextTracker.updatePlaybackService(DiagnosticPlaybackService.STOPPED)
        resumeScope.cancel()
        // Release only the session; the shared player stays with App.playerManager.
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    /**
     * #805 — media resumption.
     *
     * After the system kills the process, Media3 rebuilds this session around
     * the shared player, which is empty: a play command from the notification
     * shade then has nothing to resume, so the app showed «Не вдалося
     * відновити відтворення» and «Повторити» had nothing to retry.
     *
     * Media3 asks the app for the queue through this callback. The load rides
     * the SAME path the widget and the in-app «Продовжити слухати» tile use
     * ([PlaybackResume]), so the manager's state — book, chapters, position,
     * heal budget, progress saving — stays coherent; handing Media3 raw media
     * items would leave `playerState` empty. The values handed back are built by
     * [PlaybackResumption], which is unit-tested without a live session.
     *
     * The returned items are what the manager just put on the player; Media3
     * sets them again for the resumption, and that second prepare is the price
     * of letting the manager own the queue.
     */
    private inner class PlaybackResumptionCallback : MediaSession.Callback {
        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            resumeScope.launch {
                result.set(
                    PlaybackResumption.itemsFor(
                        playerManager = App.instance.playerManager,
                        libraryEntries = App.instance.libraryEntries,
                        playableFor = { bookId -> App.instance.sourceCatalog.getPlayableChapters(bookId) },
                        player = session.player
                    )
                )
            }
            return result
        }
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
